/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafkaconnect;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.streamshub.mcp.common.dto.ConditionInfo;
import io.streamshub.mcp.common.dto.ReplicasInfo;

import java.time.Instant;
import java.util.List;

/**
 * Response containing KafkaConnect cluster information.
 * Used for both list (summary) and get (detail) operations.
 *
 * @param name                  the KafkaConnect cluster name
 * @param namespace             the Kubernetes namespace
 * @param readiness             the cluster readiness status (Ready, NotReady, Error, Unknown)
 * @param replicas              the replica count information with expected and ready counts
 * @param version               the Kafka Connect version
 * @param bootstrapServers      the bootstrap servers this Connect cluster connects to
 * @param restApiUrl            the REST API URL for managing connectors
 * @param connectorPluginsCount the number of available connector plugins
 * @param connectorPlugins      the list of available connector plugins (null for list operations)
 * @param conditions            the list of status conditions
 * @param creationTime          when the cluster was created (null for list operations)
 * @param ageMinutes            the age of the cluster in minutes (null for list operations)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaConnectResponse(
    @JsonProperty("name") String name,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("readiness") String readiness,
    @JsonProperty("replicas") ReplicasInfo replicas,
    @JsonProperty("version") String version,
    @JsonProperty("bootstrap_servers") String bootstrapServers,
    @JsonProperty("rest_api_url") String restApiUrl,
    @JsonProperty("connector_plugins_count") Integer connectorPluginsCount,
    @JsonProperty("connector_plugins") List<ConnectorPluginInfo> connectorPlugins,
    @JsonProperty("conditions") List<ConditionInfo> conditions,
    @JsonProperty("creation_time") Instant creationTime,
    @JsonProperty("age_minutes") Long ageMinutes
) {

    /**
     * Information about a connector plugin available in a KafkaConnect cluster.
     *
     * @param connectorClass the fully qualified class name of the connector
     * @param type           the connector type (source or sink)
     * @param version        the plugin version
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConnectorPluginInfo(
        @JsonProperty("class") String connectorClass,
        @JsonProperty("type") String type,
        @JsonProperty("version") String version
    ) {

        /**
         * Creates a connector plugin info.
         *
         * @param connectorClass the connector class name
         * @param type           the connector type
         * @param version        the plugin version
         * @return a new connector plugin info
         */
        public static ConnectorPluginInfo of(String connectorClass, String type, String version) {
            return new ConnectorPluginInfo(connectorClass, type, version);
        }
    }

    /**
     * Creates a summary response for list operations (no connector plugins detail, creation time, or age).
     *
     * @param name                  the KafkaConnect cluster name
     * @param namespace             the Kubernetes namespace
     * @param readiness             the readiness status
     * @param replicas              the replica counts
     * @param version               the Kafka Connect version
     * @param bootstrapServers      the bootstrap servers
     * @param restApiUrl            the REST API URL
     * @param connectorPluginsCount the number of available connector plugins
     * @param conditions            the status conditions
     * @return a summary response
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static KafkaConnectResponse summary(String name, String namespace, String readiness,
                                                ReplicasInfo replicas, String version,
                                                String bootstrapServers, String restApiUrl,
                                                Integer connectorPluginsCount,
                                                List<ConditionInfo> conditions) {
        return new KafkaConnectResponse(name, namespace, readiness, replicas, version,
            bootstrapServers, restApiUrl, connectorPluginsCount, null, conditions, null, null);
    }

    /**
     * Creates a detailed response for get operations with all fields.
     *
     * @param name                  the KafkaConnect cluster name
     * @param namespace             the Kubernetes namespace
     * @param readiness             the readiness status
     * @param replicas              the replica counts
     * @param version               the Kafka Connect version
     * @param bootstrapServers      the bootstrap servers
     * @param restApiUrl            the REST API URL
     * @param connectorPluginsCount the number of available connector plugins
     * @param connectorPlugins      the list of available connector plugins
     * @param conditions            the status conditions
     * @param creationTime          the creation time
     * @param ageMinutes            the age in minutes
     * @return a detailed response
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static KafkaConnectResponse of(String name, String namespace, String readiness,
                                           ReplicasInfo replicas, String version,
                                           String bootstrapServers, String restApiUrl,
                                           Integer connectorPluginsCount,
                                           List<ConnectorPluginInfo> connectorPlugins,
                                           List<ConditionInfo> conditions,
                                           Instant creationTime, Long ageMinutes) {
        return new KafkaConnectResponse(name, namespace, readiness, replicas, version,
            bootstrapServers, restApiUrl, connectorPluginsCount, connectorPlugins,
            conditions, creationTime, ageMinutes);
    }
}
