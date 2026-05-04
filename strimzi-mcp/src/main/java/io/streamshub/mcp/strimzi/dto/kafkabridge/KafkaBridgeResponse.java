/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafkabridge;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.streamshub.mcp.common.dto.ConditionInfo;
import io.streamshub.mcp.common.dto.ReplicasInfo;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Response containing KafkaBridge information.
 * Used for both list (summary) and get (detail) operations.
 *
 * @param name               the KafkaBridge name
 * @param namespace          the Kubernetes namespace
 * @param readiness          the bridge readiness status (Ready, NotReady, Error, Unknown)
 * @param replicas           the replica count information with expected and ready counts
 * @param bootstrapServers   the Kafka bootstrap servers this bridge connects to
 * @param httpUrl            the HTTP Bridge access URL from status
 * @param httpPort           the configured HTTP port (null for list operations)
 * @param corsAllowedOrigins the CORS allowed origins (null for list operations)
 * @param corsAllowedMethods the CORS allowed methods (null for list operations)
 * @param producerConfig     the bridge producer configuration (null for list operations)
 * @param consumerConfig     the bridge consumer configuration (null for list operations)
 * @param adminClientConfig  the bridge admin client configuration (null for list operations)
 * @param authenticationType the authentication type (null for list operations)
 * @param tlsEnabled         whether TLS is enabled (null for list operations)
 * @param logging            the logging configuration (null for list operations)
 * @param conditions         the list of status conditions
 * @param creationTime       when the bridge was created (null for list operations)
 * @param ageMinutes         the age of the bridge in minutes (null for list operations)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaBridgeResponse(
    @JsonProperty("name") String name,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("readiness") String readiness,
    @JsonProperty("replicas") ReplicasInfo replicas,
    @JsonProperty("bootstrap_servers") String bootstrapServers,
    @JsonProperty("http_url") String httpUrl,
    @JsonProperty("http_port") Integer httpPort,
    @JsonProperty("cors_allowed_origins") List<String> corsAllowedOrigins,
    @JsonProperty("cors_allowed_methods") List<String> corsAllowedMethods,
    @JsonProperty("producer_config") Map<String, Object> producerConfig,
    @JsonProperty("consumer_config") Map<String, Object> consumerConfig,
    @JsonProperty("admin_client_config") Map<String, Object> adminClientConfig,
    @JsonProperty("authentication_type") String authenticationType,
    @JsonProperty("tls_enabled") Boolean tlsEnabled,
    @JsonProperty("logging") String logging,
    @JsonProperty("conditions") List<ConditionInfo> conditions,
    @JsonProperty("creation_time") Instant creationTime,
    @JsonProperty("age_minutes") Long ageMinutes
) {

    /**
     * Creates a summary response for list operations (no detail fields).
     *
     * @param name             the KafkaBridge name
     * @param namespace        the Kubernetes namespace
     * @param readiness        the readiness status
     * @param replicas         the replica counts
     * @param bootstrapServers the bootstrap servers
     * @param httpUrl          the HTTP Bridge access URL
     * @param conditions       the status conditions
     * @return a summary response
     */
    public static KafkaBridgeResponse summary(String name, String namespace, String readiness,
                                               ReplicasInfo replicas, String bootstrapServers,
                                               String httpUrl, List<ConditionInfo> conditions) {
        return new KafkaBridgeResponse(name, namespace, readiness, replicas, bootstrapServers,
            httpUrl, null, null, null, null, null, null, null, null, null, conditions, null, null);
    }

    /**
     * Creates a detailed response for get operations with all fields.
     *
     * @param name               the KafkaBridge name
     * @param namespace          the Kubernetes namespace
     * @param readiness          the readiness status
     * @param replicas           the replica counts
     * @param bootstrapServers   the bootstrap servers
     * @param httpUrl            the HTTP Bridge access URL
     * @param httpPort           the configured HTTP port
     * @param corsAllowedOrigins the CORS allowed origins
     * @param corsAllowedMethods the CORS allowed methods
     * @param producerConfig     the producer configuration
     * @param consumerConfig     the consumer configuration
     * @param adminClientConfig  the admin client configuration
     * @param authenticationType the authentication type
     * @param tlsEnabled         whether TLS is enabled
     * @param logging            the logging configuration
     * @param conditions         the status conditions
     * @param creationTime       the creation time
     * @param ageMinutes         the age in minutes
     * @return a detailed response
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static KafkaBridgeResponse of(String name, String namespace, String readiness,
                                          ReplicasInfo replicas, String bootstrapServers,
                                          String httpUrl, Integer httpPort,
                                          List<String> corsAllowedOrigins,
                                          List<String> corsAllowedMethods,
                                          Map<String, Object> producerConfig,
                                          Map<String, Object> consumerConfig,
                                          Map<String, Object> adminClientConfig,
                                          String authenticationType, Boolean tlsEnabled,
                                          String logging, List<ConditionInfo> conditions,
                                          Instant creationTime, Long ageMinutes) {
        return new KafkaBridgeResponse(name, namespace, readiness, replicas, bootstrapServers,
            httpUrl, httpPort, corsAllowedOrigins, corsAllowedMethods, producerConfig,
            consumerConfig, adminClientConfig, authenticationType, tlsEnabled, logging,
            conditions, creationTime, ageMinutes);
    }
}
