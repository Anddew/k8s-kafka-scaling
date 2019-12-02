# Kafka autoscaling on Kubernetes

### Based on [tutorial](https://blog.softwaremill.com/autoscaling-kafka-streams-applications-with-kubernetes-9aed2e37d3a0).
### Dataset find on [kaggle](https://www.kaggle.com/c/expedia-hotel-recommendations).

1. Install VirtualBox / KVM
2. Install minikube and kubectl
    ```shell script
    minikube start --profile kafka --memory 8192 --cpus 4 --kubernetes-version v1.15.4
    kubectl create namespace kafka
    kubectl config set-context kafka --namespace=kafka
    kubectl config use-context kafka
    ```
3. Open dashboard
    ```shell script
    minikube -p kafka dashboard
    ```

4. Install helm charts

    Install helm
    ```shell script
    helm init
    ```

    Verify Tiller pod is up and running
    ```shell script
    kubectl get pods --namespace kube-system
    ```
    
      * Install confluence kafka chart (also deployed zookeeper quorum)  
        Tip: if zookeeper not started, clone repo by tag v5.2.2 and build chart manually.  
        ```shell script
        helm repo add confluentinc https://confluentinc.github.io/cp-helm-charts/ && \
        helm repo update && \
        helm install --name kafka \
          --namespace kafka \
          --set cp-ksql-server.enabled=false \
          --set cp-control-center.enabled=false \
          --set cp-kafka.customEnv.ADVERTISED_LISTENER_HOST=$(minikube ip -p kafka) \
          -f ./k8s/kafka/expose-kafka-to-host.yaml \
          confluentinc/cp-helm-charts
        ```
        
      * Install prometheus-operator chart
        ```shell script
        helm install --name prometheus-operator \
          -f ./k8s/prometheus/prometheus-operator-values.yml \
          --namespace kafka \
          stable/prometheus-operator
        ```
    
      * Install prometheus-adapter chart
      Check if issue [this](https://github.com/helm/charts/issues/10316#issuecomment-516007356)
        ```shell script
        helm install --name prometheus-adapter \
          -f ./k8s/prometheus/prometheus-adapter-values.yml \
          --namespace kafka \
          stable/prometheus-adapter
        ```

5. Prepare Kafka

      * Create topics: weather_topic, hotels, hotel_weather
        ```shell script
        KAFKA_HOME=/usr/local/kafka
        MINIKUBE_IP=$(minikube ip -p kafka)
        $KAFKA_HOME/bin/kafka-topics.sh \
        --bootstrap-server $MINIKUBE_IP:31090,$MINIKUBE_IP:31091,$MINIKUBE_IP:31092 \
        --create \
        --partitions 3 \
        --replication-factor 3 \
        --topic <topic-name>
        ```
   
6. Prepare Streamsets pipeline

      * Deploy Streamsets
        ```shell script
        kubectl apply -n kafka -f ./k8s/streamsets/streamsets-deploy.yml
        ```
    
      * Import pipeline
        ```shell script
        kubectl exec -it streamsets-0 -- curl -s -XPOST -u admin:admin -v -H 'Content-Type: application/json' -H 'X-Requested-By: Import pipeline' -d "@/tmp/expedia-pipeline.json" http://localhost:18630/rest/v1/pipeline/dummy_id/import?autoGeneratePipelineId=true
        ```
        
      * Expose Streamsets UI to http://localhost:18630
        ```shell script
        kubectl port-forward streamsets-0 18630
        ```
        
      * Run pipeline
  
7. Install Kafka Streams application

      * build fat jar
        ```shell script
        sbt assembly
        ```
      * move jar to docker dir
        ```shell script
        mv -f ./target/scala-2.12/kafka-streams-app.jar ./docker/kafka-scaling/jar
        ```
        
      * build docker image and push to repo
        ```shell script
        docker build ./docker/kafka-scaling --no-cache -t abylinovich/kafka-streams-app:latest && \
        docker push abylinovich/kafka-streams-app:latest
        ```
      
      * deploy app to k8s cluster
        ```shell script   
        kubectl apply -n kafka -f ./k8s/app/jmx-config-map.yaml && \
        kubectl apply -n kafka -f ./k8s/app/deployment.yml
        ```
        
      * Install Horizontal Pod Autoscaler
        ```shell script
        kubectl apply -n kafka -f ./k8s/app/horizontal-pod-autoscaler.yaml
        ```
        
      * Install services
        ```shell script
        kubectl apply -n kafka -f ./k8s/app/service.yml && \
        kubectl apply -n kafka -f ./k8s/app/service-monitor.yml
        ```
  
8. Prepare Hive

      * Check minikube IP (example: 192.168.99.105)
        ```shell script
        minikube ip -p kafka
        ```
        
      * Run HDP sandbox docker containers
      
      * Load weather data into sandbox HDFS
        ```shell script
        docker cp ./dataset sandbox-hdp:/tmp && \
        docker cp ./hdp/prepare-hdfs.sh sandbox-hdp:/tmp && \
        docker exec -it sandbox-hdp  /bin/bash /tmp/prepare-hdfs.sh
        ```
        
      * Load Hive-Kafka handler from  [maven repository](https://mvnrepository.com/artifact/org.apache.hive/kafka-handler)
      * Copy jar file into sandbox container 
        ```shell script
        docker cp ./hdp/kafka-handler.jar sandbox-hdp:/usr/hdp/current/hive-server2/lib
        ```
      * Open bash in HDP docker container
        ```shell script
        docker exec -it sandbox-hdp /bin/bash
        ```
      * Run tool ```beeline```
      * Create Hive external table over HDFS data
        ```hiveql
        CREATE EXTERNAL TABLE weather(
            lng DOUBLE, 
            lat DOUBLE, 
            avg_tmpr_f DOUBLE, 
            avg_tmpr_c DOUBLE, 
            wthr_date STRING) 
        STORED AS PARQUET 
        LOCATION "/tmp/dataset/weather/";
        ```
      * Create Kafka-Hive table
        ```hiveql
        CREATE EXTERNAL TABLE kafka_weather(
            lng DOUBLE,
            lat DOUBLE, 
            avg_tmpr_f DOUBLE, 
            avg_tmpr_c DOUBLE, 
            wthr_date STRING)
        STORED BY 'org.apache.hadoop.hive.kafka.KafkaStorageHandler'
        TBLPROPERTIES (
            "kafka.topic" = "weather_topic", 
            "kafka.bootstrap.servers" = "192.168.99.106:31090,192.168.99.106:31091,192.168.99.106:31092", 
            "kafka.serde.class" = "org.apache.hadoop.hive.serde2.OpenCSVSerde",
            "kafka.write.semantic"="EXACTLY_ONCE"
        );
        ```
        
      * Populate table
        ```hiveql
        INSERT INTO TABLE kafka_weather
        SELECT lng, lat, avg_tmpr_f, avg_tmpr_c, wthr_date, null AS `__key`, null AS `__partition`, -1 AS `__offset`, CURRENT_TIMESTAMP AS `__timestamp` 
        FROM weather;
        ```
    
9. Configure Prometheus and Grafana

      * Forward port to host for Prometheus
        ```shell script
        kubectl port-forward prometheus-prometheus-operator-prometheus-0 9099:9090
        ```
    
      * Find pod name for Grafana and forward port to host
        ```shell script
        kubectl get pods
        kubectl port-forward prometheus-operator-grafana-5cbfbcd8f9-czrrg 3001:3000
        ```
        
      * Obtain password for Grafana (default credentials ```admin/prom-operator```)
        ```shell script
        kubectl get secret prometheus-operator-grafana -o jsonpath='{.data.admin-password}' | base64 --decode ; echo
        ```
    
