/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.operator;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;
/**
 * Effective configuration of a Strimzi cluster operator, read from an allow-listed
 * subset of the operator Deployment's environment variables.
 *
 * <p>Only the keys in the allow-list are returned; operator environment can carry
 * credentials, so anything not explicitly allowed is dropped rather than redacted.</p>
 *
 * @param name                        the operator deployment name
 * @param namespace                   the namespace where the operator is deployed
 * @param version                     the operator version (extracted from the image)
 * @param featureGates                the raw {@code STRIMZI_FEATURE_GATES} value (null when unset)
 * @param watchedNamespaces           the namespaces the operator watches, empty when watching all
 * @param watchesAllNamespaces        whether the operator watches all namespaces
 * @param supportedKafkaVersions      Kafka versions parsed from {@code STRIMZI_KAFKA_IMAGES}
 * @param operationTimeoutMs          the raw {@code STRIMZI_OPERATION_TIMEOUT_MS} value
 * @param fullReconciliationIntervalMs the raw {@code STRIMZI_FULL_RECONCILIATION_INTERVAL_MS} value
 * @param leaderElectionEnabled       the raw {@code STRIMZI_LEADER_ELECTION_ENABLED} value
 * @param config                      the allow-listed environment variables as returned, verbatim
 * @param message                     a human-readable summary
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StrimziOperatorConfigResponse(
    @JsonProperty("name") String name,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("version") String version,
    @JsonProperty("feature_gates") String featureGates,
    @JsonProperty("watched_namespaces") List<String> watchedNamespaces,
    @JsonProperty("watches_all_namespaces") boolean watchesAllNamespaces,
    @JsonProperty("supported_kafka_versions") List<String> supportedKafkaVersions,
    @JsonProperty("operation_timeout_ms") String operationTimeoutMs,
    @JsonProperty("full_reconciliation_interval_ms") String fullReconciliationIntervalMs,
    @JsonProperty("leader_election_enabled") String leaderElectionEnabled,
    @JsonProperty("config") Map<String, String> config,
    @JsonProperty("message") String message
) {

    /**
     * Create an operator configuration response.
     *
     * @param name                        the operator deployment name
     * @param namespace                   the namespace
     * @param version                     the operator version
     * @param featureGates                the feature gates value
     * @param watchedNamespaces           the watched namespaces
     * @param watchesAllNamespaces        whether all namespaces are watched
     * @param supportedKafkaVersions      the supported Kafka versions
     * @param operationTimeoutMs          the operation timeout
     * @param fullReconciliationIntervalMs the full reconciliation interval
     * @param leaderElectionEnabled       the leader election flag
     * @param config                      the allow-listed environment variables
     * @return a new StrimziOperatorConfigResponse
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static StrimziOperatorConfigResponse of(final String name, final String namespace, final String version,
                                                   final String featureGates, final List<String> watchedNamespaces,
                                                   final boolean watchesAllNamespaces,
                                                   final List<String> supportedKafkaVersions,
                                                   final String operationTimeoutMs,
                                                   final String fullReconciliationIntervalMs,
                                                   final String leaderElectionEnabled,
                                                   final Map<String, String> config) {
        String scope = watchesAllNamespaces
            ? "all namespaces"
            : watchedNamespaces.size() + " namespace(s)";
        String msg = String.format("Operator %s watches %s, supports %d Kafka version(s)",
            name, scope, supportedKafkaVersions.size());
        return new StrimziOperatorConfigResponse(name, namespace, version, featureGates, watchedNamespaces,
            watchesAllNamespaces, supportedKafkaVersions, operationTimeoutMs, fullReconciliationIntervalMs,
            leaderElectionEnabled, config, msg);
    }
}
