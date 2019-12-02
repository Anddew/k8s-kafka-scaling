FROM streamsets/datacollector:3.10.1

RUN "${SDC_DIST}/bin/streamsets" stagelibs -install=streamsets-datacollector-hdp_3_1-lib,streamsets-datacollector-jython_2_7-lib,streamsets-datacollector-apache-kafka_2_0-lib

COPY ./libs/* /opt/streamsets-datacollector-3.10.1/streamsets-libs/streamsets-datacollector-jython_2_7-lib/lib

COPY ./pipeline/expedia-pipeline.json /tmp
