/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafkaconnect;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.streamshub.mcp.common.dto.ConditionInfo;

import java.util.List;
import java.util.Map;

/**
 * Response containing KafkaConnector information.
 * Used for both list (summary) and get (detail) operations.
 *
 * @param name            the connector name
 * @param namespace       the Kubernetes namespace
 * @param connectCluster  the parent KafkaConnect cluster name
 * @param className       the connector class name
 * @param tasksMax        the maximum number of tasks
 * @param state           the desired connector state (running, paused, stopped)
 * @param readiness       the readiness status from conditions (Ready, NotReady, Error, Unknown)
 * @param autoRestart     the auto-restart configuration and status
 * @param topics          the list of topics used by the connector
 * @param connectorStatus the connector status from the Connect REST API (null for list operations)
 * @param conditions      the list of status conditions
 * @param config          the connector configuration map (null for list operations)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaConnectorResponse(
    @JsonProperty("name") String name,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("connect_cluster") String connectCluster,
    @JsonProperty("class_name") String className,
    @JsonProperty("tasks_max") Integer tasksMax,
    @JsonProperty("state") String state,
    @JsonProperty("readiness") String readiness,
    @JsonProperty("auto_restart") AutoRestartInfo autoRestart,
    @JsonProperty("topics") List<String> topics,
    @JsonProperty("connector_status") Map<String, Object> connectorStatus,
    @JsonProperty("conditions") List<ConditionInfo> conditions,
    @JsonProperty("config") Map<String, Object> config
) {

    /**
     * Auto-restart configuration and status information.
     *
     * @param enabled         whether auto-restart is enabled
     * @param maxRestarts     the maximum number of restart attempts (null for unlimited)
     * @param restartCount    the number of restarts that have occurred
     * @param lastRestartTime the timestamp of the last restart in ISO 8601 format
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AutoRestartInfo(
        @JsonProperty("enabled") Boolean enabled,
        @JsonProperty("max_restarts") Integer maxRestarts,
        @JsonProperty("restart_count") Integer restartCount,
        @JsonProperty("last_restart_time") String lastRestartTime
    ) {

        /**
         * Creates an auto-restart info.
         *
         * @param enabled         whether auto-restart is enabled
         * @param maxRestarts     the maximum restart attempts
         * @param restartCount    the restart count
         * @param lastRestartTime the last restart timestamp
         * @return a new auto-restart info
         */
        public static AutoRestartInfo of(Boolean enabled, Integer maxRestarts,
                                          Integer restartCount, String lastRestartTime) {
            return new AutoRestartInfo(enabled, maxRestarts, restartCount, lastRestartTime);
        }
    }

    /**
     * Creates a summary response for list operations (no config or connector status).
     *
     * @param name           the connector name
     * @param namespace      the Kubernetes namespace
     * @param connectCluster the parent KafkaConnect cluster name
     * @param className      the connector class name
     * @param tasksMax       the maximum number of tasks
     * @param state          the desired connector state
     * @param readiness      the readiness status
     * @param autoRestart    the auto-restart info
     * @param topics         the topics used by the connector
     * @param conditions     the status conditions
     * @return a summary response
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static KafkaConnectorResponse summary(String name, String namespace, String connectCluster,
                                                   String className, Integer tasksMax, String state,
                                                   String readiness, AutoRestartInfo autoRestart,
                                                   List<String> topics,
                                                   List<ConditionInfo> conditions) {
        return new KafkaConnectorResponse(name, namespace, connectCluster, className, tasksMax,
            state, readiness, autoRestart, topics, null, conditions, null);
    }

    /**
     * Creates a detailed response for get operations with all fields.
     *
     * @param name            the connector name
     * @param namespace       the Kubernetes namespace
     * @param connectCluster  the parent KafkaConnect cluster name
     * @param className       the connector class name
     * @param tasksMax        the maximum number of tasks
     * @param state           the desired connector state
     * @param readiness       the readiness status
     * @param autoRestart     the auto-restart info
     * @param topics          the topics used by the connector
     * @param connectorStatus the connector status from the Connect REST API
     * @param conditions      the status conditions
     * @param config          the connector configuration
     * @return a detailed response
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static KafkaConnectorResponse of(String name, String namespace, String connectCluster,
                                              String className, Integer tasksMax, String state,
                                              String readiness, AutoRestartInfo autoRestart,
                                              List<String> topics,
                                              Map<String, Object> connectorStatus,
                                              List<ConditionInfo> conditions,
                                              Map<String, Object> config) {
        return new KafkaConnectorResponse(name, namespace, connectCluster, className, tasksMax,
            state, readiness, autoRestart, topics, connectorStatus, conditions, config);
    }
}
