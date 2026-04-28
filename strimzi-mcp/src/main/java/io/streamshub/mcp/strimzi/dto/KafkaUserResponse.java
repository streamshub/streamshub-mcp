/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.streamshub.mcp.common.dto.ConditionInfo;

import java.util.List;

/**
 * Response containing KafkaUser information.
 * Used for both list (summary) and get (detail) operations.
 * Never exposes credential secret data -- only the secret name from status.
 *
 * @param name           the KafkaUser resource name
 * @param namespace      the Kubernetes namespace
 * @param cluster        the Kafka cluster name from the strimzi.io/cluster label
 * @param authentication the authentication type (tls, tls-external, scram-sha-512, or null)
 * @param authorization  the authorization type (simple, or null)
 * @param aclCount       the number of ACL rules (null if no authorization)
 * @param quotas         the user quotas (null for list operations or if no quotas configured)
 * @param aclRules       the ACL rules (null for list operations or if no authorization)
 * @param username       the Kafka principal name from status (may differ from resource name for TLS users)
 * @param secretName     the name of the K8s Secret holding credentials (never the secret data itself)
 * @param readiness      the readiness status (Ready, NotReady, Error, Unknown)
 * @param conditions     the list of status conditions
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaUserResponse(
    @JsonProperty("name") String name,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("cluster") String cluster,
    @JsonProperty("authentication") String authentication,
    @JsonProperty("authorization") String authorization,
    @JsonProperty("acl_count") Integer aclCount,
    @JsonProperty("quotas") QuotaInfo quotas,
    @JsonProperty("acl_rules") List<AclRuleInfo> aclRules,
    @JsonProperty("username") String username,
    @JsonProperty("secret_name") String secretName,
    @JsonProperty("readiness") String readiness,
    @JsonProperty("conditions") List<ConditionInfo> conditions
) {

    /**
     * Quota configuration for a KafkaUser.
     *
     * @param producerByteRate       maximum bytes per second a producer can publish per broker
     * @param consumerByteRate       maximum bytes per second a consumer can fetch per broker
     * @param requestPercentage      maximum CPU utilization percentage for network and I/O threads
     * @param controllerMutationRate maximum rate of partition create/delete mutations per second
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QuotaInfo(
        @JsonProperty("producer_byte_rate") Integer producerByteRate,
        @JsonProperty("consumer_byte_rate") Integer consumerByteRate,
        @JsonProperty("request_percentage") Integer requestPercentage,
        @JsonProperty("controller_mutation_rate") Double controllerMutationRate
    ) {

        /**
         * Creates a quota info.
         *
         * @param producerByteRate       producer byte rate
         * @param consumerByteRate       consumer byte rate
         * @param requestPercentage      request percentage
         * @param controllerMutationRate controller mutation rate
         * @return a new quota info
         */
        public static QuotaInfo of(Integer producerByteRate, Integer consumerByteRate,
                                    Integer requestPercentage, Double controllerMutationRate) {
            return new QuotaInfo(producerByteRate, consumerByteRate,
                requestPercentage, controllerMutationRate);
        }
    }

    /**
     * A single ACL rule describing an access control entry.
     *
     * @param type         the rule type (allow or deny)
     * @param resourceType the Kafka resource type (topic, group, cluster, transactionalId)
     * @param resourceName the resource name or pattern (null for cluster resource type)
     * @param patternType  the pattern type (literal or prefix, null for cluster resource type)
     * @param host         the host restriction (asterisk means any host)
     * @param operations   the list of allowed or denied operations
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AclRuleInfo(
        @JsonProperty("type") String type,
        @JsonProperty("resource_type") String resourceType,
        @JsonProperty("resource_name") String resourceName,
        @JsonProperty("pattern_type") String patternType,
        @JsonProperty("host") String host,
        @JsonProperty("operations") List<String> operations
    ) {

        /**
         * Creates an ACL rule info.
         *
         * @param type         the rule type
         * @param resourceType the resource type
         * @param resourceName the resource name
         * @param patternType  the pattern type
         * @param host         the host restriction
         * @param operations   the operations
         * @return a new ACL rule info
         */
        public static AclRuleInfo of(String type, String resourceType, String resourceName,
                                      String patternType, String host, List<String> operations) {
            return new AclRuleInfo(type, resourceType, resourceName, patternType,
                host, operations);
        }
    }

    /**
     * Creates a summary response for list operations (no ACL rules or quotas).
     *
     * @param name           the user name
     * @param namespace      the namespace
     * @param cluster        the cluster name
     * @param authentication the authentication type
     * @param authorization  the authorization type
     * @param aclCount       the ACL rule count
     * @param username       the Kafka principal name
     * @param secretName     the credential secret name
     * @param readiness      the readiness status
     * @param conditions     the status conditions
     * @return a summary response
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static KafkaUserResponse summary(String name, String namespace, String cluster,
                                             String authentication, String authorization,
                                             Integer aclCount, String username,
                                             String secretName, String readiness,
                                             List<ConditionInfo> conditions) {
        return new KafkaUserResponse(name, namespace, cluster, authentication, authorization,
            aclCount, null, null, username, secretName, readiness, conditions);
    }

    /**
     * Creates a detailed response for get operations with all fields.
     *
     * @param name           the user name
     * @param namespace      the namespace
     * @param cluster        the cluster name
     * @param authentication the authentication type
     * @param authorization  the authorization type
     * @param aclCount       the ACL rule count
     * @param quotas         the user quotas
     * @param aclRules       the ACL rules
     * @param username       the Kafka principal name
     * @param secretName     the credential secret name
     * @param readiness      the readiness status
     * @param conditions     the status conditions
     * @return a detailed response
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static KafkaUserResponse of(String name, String namespace, String cluster,
                                        String authentication, String authorization,
                                        Integer aclCount, QuotaInfo quotas,
                                        List<AclRuleInfo> aclRules, String username,
                                        String secretName, String readiness,
                                        List<ConditionInfo> conditions) {
        return new KafkaUserResponse(name, namespace, cluster, authentication, authorization,
            aclCount, quotas, aclRules, username, secretName, readiness, conditions);
    }
}
