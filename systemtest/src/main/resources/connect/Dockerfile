ARG STRIMZI_VERSION
ARG KAFKA_VERSION
FROM quay.io/strimzi/kafka:${STRIMZI_VERSION}-kafka-${KAFKA_VERSION}

USER root

RUN mkdir -p /opt/kafka/plugins/camel-timer-source

ADD https://repo.maven.apache.org/maven2/org/apache/camel/kafkaconnector/camel-timer-source-kafka-connector/4.18.0/camel-timer-source-kafka-connector-4.18.0-package.tar.gz /tmp/plugin.tar.gz

RUN tar -xzf /tmp/plugin.tar.gz -C /opt/kafka/plugins/camel-timer-source && \
    rm /tmp/plugin.tar.gz

USER 1001
