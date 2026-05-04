/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Effective configuration of a Kafka cluster assembled from the Kafka CR spec,
 * referenced ConfigMaps, and per-node-pool overrides.
 *
 * @param name               cluster name
 * @param namespace          Kubernetes namespace
 * @param kafkaVersion       Kafka version from the CR spec
 * @param metadataVersion    KRaft metadata version
 * @param brokerConfig       raw broker configuration from spec.kafka.config
 * @param resources          CPU and memory requests/limits for Kafka brokers
 * @param jvmOptions         JVM options for Kafka brokers
 * @param rackAwareness      rack awareness topology configuration
 * @param listeners          listener configurations with TLS and auth details
 * @param authorization      authorization type and configuration
 * @param metricsConfig      metrics configuration with resolved ConfigMap content
 * @param logging            logging configuration with resolved ConfigMap content
 * @param entityOperator     Entity Operator configuration (topic and user operators)
 * @param cruiseControl      Cruise Control configuration
 * @param kafkaExporter      Kafka Exporter configuration
 * @param maintenanceWindows maintenance time windows
 * @param nodePools          per-node-pool configuration overrides
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaEffectiveConfigResponse(
    @JsonProperty("name") String name,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("kafka_version") String kafkaVersion,
    @JsonProperty("metadata_version") String metadataVersion,
    @JsonProperty("broker_config") Map<String, Object> brokerConfig,
    @JsonProperty("resources") ResourcesInfo resources,
    @JsonProperty("jvm_options") JvmOptionsInfo jvmOptions,
    @JsonProperty("rack_awareness") RackInfo rackAwareness,
    @JsonProperty("listeners") List<ListenerConfigInfo> listeners,
    @JsonProperty("authorization") AuthorizationInfo authorization,
    @JsonProperty("metrics_config") MetricsConfigInfo metricsConfig,
    @JsonProperty("logging") LoggingConfigInfo logging,
    @JsonProperty("entity_operator") EntityOperatorInfo entityOperator,
    @JsonProperty("cruise_control") CruiseControlInfo cruiseControl,
    @JsonProperty("kafka_exporter") KafkaExporterInfo kafkaExporter,
    @JsonProperty("maintenance_windows") List<String> maintenanceWindows,
    @JsonProperty("node_pools") List<NodePoolConfigInfo> nodePools
) {

    /**
     * CPU and memory resource requests and limits.
     *
     * @param cpuRequest    requested CPU
     * @param cpuLimit      CPU limit
     * @param memoryRequest requested memory
     * @param memoryLimit   memory limit
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResourcesInfo(
        @JsonProperty("cpu_request") String cpuRequest,
        @JsonProperty("cpu_limit") String cpuLimit,
        @JsonProperty("memory_request") String memoryRequest,
        @JsonProperty("memory_limit") String memoryLimit
    ) {
    }

    /**
     * JVM options including heap sizes and additional flags.
     *
     * @param xms                  initial heap size
     * @param xmx                  maximum heap size
     * @param javaSystemProperties Java system properties
     * @param xxOptions            -XX JVM options
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JvmOptionsInfo(
        @JsonProperty("xms") String xms,
        @JsonProperty("xmx") String xmx,
        @JsonProperty("java_system_properties") List<Map<String, String>> javaSystemProperties,
        @JsonProperty("xx_options") Map<String, String> xxOptions
    ) {
    }

    /**
     * Rack awareness configuration.
     *
     * @param topologyKey Kubernetes topology key for rack distribution
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RackInfo(
        @JsonProperty("topology_key") String topologyKey
    ) {
    }

    /**
     * Detailed listener configuration including TLS and authentication.
     *
     * @param name      listener name
     * @param port      listener port number
     * @param type      listener type (internal, route, loadbalancer, etc.)
     * @param tls       whether TLS is enabled
     * @param authType  authentication type (scram-sha-512, tls, oauth, etc.)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ListenerConfigInfo(
        @JsonProperty("name") String name,
        @JsonProperty("port") Integer port,
        @JsonProperty("type") String type,
        @JsonProperty("tls") Boolean tls,
        @JsonProperty("auth_type") String authType
    ) {
    }

    /**
     * Authorization configuration.
     *
     * @param type authorization type (simple, opa, keycloak, custom)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AuthorizationInfo(
        @JsonProperty("type") String type
    ) {
    }

    /**
     * Metrics configuration with resolved ConfigMap content.
     *
     * @param type           metrics type (e.g., jmxPrometheusExporter)
     * @param configMapName  name of the referenced ConfigMap
     * @param configMapKey   key within the ConfigMap
     * @param content        resolved ConfigMap content, or null if not resolved
     * @param resolutionNote note about resolution failure, if any
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MetricsConfigInfo(
        @JsonProperty("type") String type,
        @JsonProperty("config_map_name") String configMapName,
        @JsonProperty("config_map_key") String configMapKey,
        @JsonProperty("content") String content,
        @JsonProperty("resolution_note") String resolutionNote
    ) {
    }

    /**
     * Logging configuration with support for inline and external (ConfigMap) modes.
     *
     * @param type           logging type (inline or external)
     * @param loggers        logger-level map for inline logging
     * @param configMapName  ConfigMap name for external logging
     * @param configMapKey   ConfigMap key for external logging
     * @param content        resolved ConfigMap content for external logging
     * @param resolutionNote note about resolution failure, if any
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LoggingConfigInfo(
        @JsonProperty("type") String type,
        @JsonProperty("loggers") Map<String, String> loggers,
        @JsonProperty("config_map_name") String configMapName,
        @JsonProperty("config_map_key") String configMapKey,
        @JsonProperty("content") String content,
        @JsonProperty("resolution_note") String resolutionNote
    ) {
    }

    /**
     * Entity Operator configuration including topic and user operator settings.
     *
     * @param topicOperator topic operator configuration
     * @param userOperator  user operator configuration
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EntityOperatorInfo(
        @JsonProperty("topic_operator") SubOperatorInfo topicOperator,
        @JsonProperty("user_operator") SubOperatorInfo userOperator
    ) {
    }

    /**
     * Configuration for an individual sub-operator (topic or user operator).
     *
     * @param watchedNamespace              namespace watched by the operator
     * @param reconciliationIntervalSeconds reconciliation interval in seconds
     * @param resources                     CPU and memory resources
     * @param jvmOptions                    JVM options
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SubOperatorInfo(
        @JsonProperty("watched_namespace") String watchedNamespace,
        @JsonProperty("reconciliation_interval_seconds") Integer reconciliationIntervalSeconds,
        @JsonProperty("resources") ResourcesInfo resources,
        @JsonProperty("jvm_options") JvmOptionsInfo jvmOptions
    ) {
    }

    /**
     * Cruise Control configuration.
     *
     * @param config         Cruise Control configuration map
     * @param brokerCapacity broker capacity settings
     * @param autoRebalance  auto-rebalance configurations
     * @param resources      CPU and memory resources
     * @param jvmOptions     JVM options
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CruiseControlInfo(
        @JsonProperty("config") Map<String, Object> config,
        @JsonProperty("broker_capacity") BrokerCapacityInfo brokerCapacity,
        @JsonProperty("auto_rebalance") List<AutoRebalanceInfo> autoRebalance,
        @JsonProperty("resources") ResourcesInfo resources,
        @JsonProperty("jvm_options") JvmOptionsInfo jvmOptions
    ) {
    }

    /**
     * Cruise Control broker capacity settings.
     *
     * @param cpu             CPU capacity
     * @param inboundNetwork  inbound network capacity
     * @param outboundNetwork outbound network capacity
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BrokerCapacityInfo(
        @JsonProperty("cpu") String cpu,
        @JsonProperty("inbound_network") String inboundNetwork,
        @JsonProperty("outbound_network") String outboundNetwork
    ) {
    }

    /**
     * Cruise Control auto-rebalance configuration.
     *
     * @param mode auto-rebalance mode
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AutoRebalanceInfo(
        @JsonProperty("mode") String mode
    ) {
    }

    /**
     * Kafka Exporter configuration.
     *
     * @param topicRegex        regex for topics to export metrics for
     * @param groupRegex        regex for consumer groups to export metrics for
     * @param topicExcludeRegex regex for topics to exclude
     * @param groupExcludeRegex regex for groups to exclude
     * @param resources         CPU and memory resources
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record KafkaExporterInfo(
        @JsonProperty("topic_regex") String topicRegex,
        @JsonProperty("group_regex") String groupRegex,
        @JsonProperty("topic_exclude_regex") String topicExcludeRegex,
        @JsonProperty("group_exclude_regex") String groupExcludeRegex,
        @JsonProperty("resources") ResourcesInfo resources
    ) {
    }

    /**
     * Per-node-pool configuration overrides including resources and JVM options.
     *
     * @param name        node pool name
     * @param roles       assigned roles (broker, controller)
     * @param replicas    number of replicas
     * @param storageType storage type (persistent-claim, jbod, ephemeral)
     * @param storageSize storage size
     * @param resources   CPU and memory resources
     * @param jvmOptions  JVM options
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NodePoolConfigInfo(
        @JsonProperty("name") String name,
        @JsonProperty("roles") List<String> roles,
        @JsonProperty("replicas") Integer replicas,
        @JsonProperty("storage_type") String storageType,
        @JsonProperty("storage_size") String storageSize,
        @JsonProperty("resources") ResourcesInfo resources,
        @JsonProperty("jvm_options") JvmOptionsInfo jvmOptions
    ) {
    }
}
