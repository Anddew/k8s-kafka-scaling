FROM openjdk:8

COPY jar/kafka-streams-app.jar /usr/src/myapp/kafka-streams-app.jar

CMD ["java", "-Dcom.sun.management.jmxremote", "-Dcom.sun.management.jmxremote.rmi.port=5555", "-Dcom.sun.management.jmxremote.port=5555", "-Dcom.sun.management.jmxremote.authenticate=false", "-Dcom.sun.management.jmxremote.ssl=false", "-Djava.rmi.server.hostname=127.0.0.1", "-jar", "/usr/src/myapp/kafka-streams-app.jar"]