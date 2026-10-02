/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafkauser;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;
/**
 * Aggregated view of KafkaUser ACL rules across a Kafka cluster: resource name to
 * principal to allowed (or denied) operations, plus flagged over-broad grants.
 *
 * @param cluster       the Kafka cluster name
 * @param namespace     the Kubernetes namespace, or {@code null} if searched across all namespaces
 * @param resourceType  the ACL resource type the matrix was built for (topic, group, transactionalId, cluster)
 * @param matrix        allow rules: resource name to principal to sorted, de-duplicated operations
 * @param denied        deny rules with the same shape as {@code matrix}, or {@code null} when there are none
 * @param principals    every principal that appears in {@code matrix}, sorted
 * @param resourceCount the number of distinct resource keys in {@code matrix}
 * @param principalCount the number of distinct principals in {@code matrix}
 * @param broadGrants   allow grants flagged as over-broad (wildcard resource or the {@code All} operation)
 * @param message       a human-readable summary of the matrix contents
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaUserAclMatrixResponse(
    @JsonProperty("cluster") String cluster,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("resource_type") String resourceType,
    @JsonProperty("matrix") Map<String, Map<String, List<String>>> matrix,
    @JsonProperty("denied") Map<String, Map<String, List<String>>> denied,
    @JsonProperty("principals") List<String> principals,
    @JsonProperty("resource_count") int resourceCount,
    @JsonProperty("principal_count") int principalCount,
    @JsonProperty("broad_grants") List<BroadGrant> broadGrants,
    @JsonProperty("message") String message
) {

    /**
     * A single over-broad ACL grant: an allow rule whose resource name is a wildcard
     * or whose operations include {@code All}.
     *
     * @param principal  the Kafka principal the grant applies to
     * @param resource   the matrix resource key the grant applies to
     * @param operations the granted operations
     * @param reason     a short human-readable reason the grant was flagged, e.g. {@code "wildcard resource"}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BroadGrant(
        @JsonProperty("principal") String principal,
        @JsonProperty("resource") String resource,
        @JsonProperty("operations") List<String> operations,
        @JsonProperty("reason") String reason
    ) {

        /**
         * Creates a broad grant entry.
         *
         * @param principal  the principal
         * @param resource   the resource key
         * @param operations the operations
         * @param reason     the reason it was flagged
         * @return a new broad grant entry
         */
        public static BroadGrant of(String principal, String resource, List<String> operations, String reason) {
            return new BroadGrant(principal, resource, operations, reason);
        }
    }

    /**
     * Creates an ACL matrix response, computing the principal list, resource and
     * principal counts, and summary message from the given matrix.
     *
     * @param cluster      the Kafka cluster name
     * @param namespace    the namespace, or {@code null} for all namespaces
     * @param resourceType the ACL resource type the matrix was built for
     * @param matrix       the allow-rule matrix
     * @param denied       the deny-rule matrix, or {@code null} when there are no deny rules
     * @param broadGrants  the flagged over-broad grants
     * @return a new ACL matrix response
     */
    public static KafkaUserAclMatrixResponse of(String cluster, String namespace, String resourceType,
                                                 Map<String, Map<String, List<String>>> matrix,
                                                 Map<String, Map<String, List<String>>> denied,
                                                 List<BroadGrant> broadGrants) {
        TreeSet<String> principalSet = new TreeSet<>();
        for (Map<String, List<String>> byPrincipal : matrix.values()) {
            principalSet.addAll(byPrincipal.keySet());
        }
        List<String> principals = List.copyOf(principalSet);

        int resourceCount = matrix.size();
        int principalCount = principals.size();
        String message = principalCount + " principal(s) across " + resourceCount + " " + resourceType
            + " resource(s); " + broadGrants.size() + " over-broad grant(s)";

        return new KafkaUserAclMatrixResponse(cluster, namespace, resourceType, matrix, denied,
            principals, resourceCount, principalCount, broadGrants, message);
    }
}
