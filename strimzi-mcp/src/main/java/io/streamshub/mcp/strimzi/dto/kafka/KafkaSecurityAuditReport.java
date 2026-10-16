/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafka;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.streamshub.mcp.strimzi.dto.kafkauser.KafkaUserAclMatrixResponse;
import io.streamshub.mcp.strimzi.dto.kafkauser.KafkaUserResponse;

import java.time.Instant;
import java.util.List;

/**
 * Consolidated security audit report for a Kafka cluster.
 * Composes data gathered during a single audit workflow: cluster security configuration,
 * certificate expiry, the KafkaUser inventory, and the ACL breadth matrix.
 *
 * @param cluster           the cluster status and listener/authentication configuration
 * @param authorizationType the cluster authorization type (null if none configured)
 * @param certificates      the CA and certificate expiry details (null if not gathered)
 * @param users             the KafkaUser inventory (authentication types, ACL counts, quotas)
 * @param aclMatrices       the ACL matrices per resource type with over-broad grants flagged
 *                          (empty if the deep-dive ran but no principals have ACLs; null if it
 *                          was skipped or failed)
 * @param analysis          LLM-generated security analysis (null if Sampling not supported)
 * @param stepsCompleted    the list of successfully completed audit steps
 * @param stepsFailed       the list of failed audit steps with error messages (null if none)
 * @param timestamp         the time this report was generated
 * @param message           a human-readable summary
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaSecurityAuditReport(
    @JsonProperty("cluster") KafkaClusterResponse cluster,
    @JsonProperty("authorization_type") String authorizationType,
    @JsonProperty("certificates") KafkaCertificateResponse certificates,
    @JsonProperty("users") List<KafkaUserResponse> users,
    @JsonProperty("acl_matrices") List<KafkaUserAclMatrixResponse> aclMatrices,
    @JsonProperty("analysis") String analysis,
    @JsonProperty("steps_completed") List<String> stepsCompleted,
    @JsonProperty("steps_failed") List<String> stepsFailed,
    @JsonProperty("timestamp") Instant timestamp,
    @JsonProperty("message") String message
) {

    /**
     * Creates a security audit report with all gathered data.
     *
     * @param cluster           the cluster security configuration
     * @param authorizationType the cluster authorization type (may be null)
     * @param certificates      the certificate details (may be null)
     * @param users             the KafkaUser inventory
     * @param aclMatrices       the ACL matrices (may be null)
     * @param analysis          the LLM analysis (may be null)
     * @param stepsCompleted    the completed steps
     * @param stepsFailed       the failed steps (null if none)
     * @return a new security audit report
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static KafkaSecurityAuditReport of(KafkaClusterResponse cluster,
                                              String authorizationType,
                                              KafkaCertificateResponse certificates,
                                              List<KafkaUserResponse> users,
                                              List<KafkaUserAclMatrixResponse> aclMatrices,
                                              String analysis,
                                              List<String> stepsCompleted,
                                              List<String> stepsFailed) {
        String msg = String.format("Security audit completed: %d steps succeeded, %d steps failed",
            stepsCompleted.size(), stepsFailed != null ? stepsFailed.size() : 0);
        return new KafkaSecurityAuditReport(cluster, authorizationType, certificates, users,
            aclMatrices, analysis, stepsCompleted, stepsFailed, Instant.now(), msg);
    }
}
