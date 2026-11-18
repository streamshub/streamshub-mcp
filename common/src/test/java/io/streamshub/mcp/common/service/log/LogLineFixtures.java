/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service.log;

/**
 * Real-shaped Strimzi 1.2.0 / Kafka 4.3.1 log lines for log filter and error counting tests.
 */
final class LogLineFixtures {

    /** Connect connector config dump: INFO header followed by tab-indented {@code errors.*} keys. */
    static final String CONNECT_CONFIG_DUMP =
        "2026-11-13 10:42:11,123 INFO [mcp-timer-source|worker] SourceConnectorConfig values: "
            + "(org.apache.kafka.connect.runtime.SourceConnectorConfig) [StartAndStopExecutor-connect-1-1]\n"
            + "\terrors.log.enable = false\n\terrors.log.include.messages = false\n"
            + "\terrors.retry.delay.max.ms = 60000\n\terrors.retry.timeout = 0\n\terrors.tolerance = none\n";

    /** Broker config dump line. */
    static final String BROKER_CONFIG_LINE = "\ttransaction.two.phase.commit.enable = false";

    /** Broker ERROR line. */
    static final String ERROR_LINE = "2026-11-13 11:02:44,512 ERROR [ControllerApis nodeId=8] "
        + "Unexpected error handling request "
        + "RequestHeader(apiKey=BROKER_HEARTBEAT, apiVersion=1, clientId=raft-client-3, correlationId=42) "
        + "(kafka.server.ControllerApis) [quorum-controller-8-event-handler]";

    /** Exception line from a stack trace. */
    static final String EXCEPTION_LINE = "org.apache.kafka.common.errors.TimeoutException: "
        + "Timed out waiting for a node assignment. Call: describeTopics";

    /** Stack frame line. */
    static final String STACK_FRAME =
        "\tat org.apache.kafka.clients.admin.KafkaAdminClient$Call.fail(KafkaAdminClient.java:1065)";

    /** Broker WARN line. */
    static final String WARN_LINE = "2026-11-13 11:05:01,002 WARN [RaftManager id=8] Connection to node 3 "
        + "(mcp-cluster-broker-np1-0.mcp-cluster-kafka-brokers.strimzi-kafka.svc/10.128.2.41:9090) "
        + "could not be established. Node may not be available. "
        + "(org.apache.kafka.clients.NetworkClient) [kafka-8-raft-outbound-request-thread]";

    /** Go component (logfmt) error line. */
    static final String GO_ERROR_LINE =
        "level=error ts=2026-11-13T11:00:00.000Z caller=main.go:42 msg=\"cannot connect to broker\"";

    /** JVM out-of-memory error line. */
    static final String OOM_LINE = "java.lang.OutOfMemoryError: Java heap space";

    private LogLineFixtures() {
    }
}
