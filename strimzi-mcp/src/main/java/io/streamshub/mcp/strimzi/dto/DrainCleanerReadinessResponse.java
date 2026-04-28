/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Readiness assessment for Strimzi Drain Cleaner.
 *
 * @param deployed                   whether a drain cleaner deployment was found
 * @param allReplicasReady           whether all replicas are ready
 * @param webhookConfigured          whether the ValidatingWebhookConfiguration exists
 * @param failurePolicy              the webhook failure policy
 * @param failurePolicyRecommendation guidance if failure policy is not optimal
 * @param mode                       the operating mode ('standard' or 'legacy')
 * @param modeRecommendation         guidance if operating in legacy mode
 * @param certificateValid           whether the TLS certificate is valid
 * @param certificateDaysUntilExpiry days until the TLS certificate expires
 * @param coveredNamespaces          namespaces covered by the webhook
 * @param checks                     individual readiness check results
 * @param overallReady               whether all checks pass
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DrainCleanerReadinessResponse(
    @JsonProperty("deployed") boolean deployed,
    @JsonProperty("all_replicas_ready") boolean allReplicasReady,
    @JsonProperty("webhook_configured") boolean webhookConfigured,
    @JsonProperty("failure_policy") String failurePolicy,
    @JsonProperty("failure_policy_recommendation") String failurePolicyRecommendation,
    @JsonProperty("mode") String mode,
    @JsonProperty("mode_recommendation") String modeRecommendation,
    @JsonProperty("certificate_valid") boolean certificateValid,
    @JsonProperty("certificate_days_until_expiry") Long certificateDaysUntilExpiry,
    @JsonProperty("covered_namespaces") String coveredNamespaces,
    @JsonProperty("checks") List<ReadinessCheck> checks,
    @JsonProperty("overall_ready") boolean overallReady
) {

    /**
     * Creates a readiness response with all assessment details.
     *
     * @param deployed                   whether deployed
     * @param allReplicasReady           whether all replicas are ready
     * @param webhookConfigured          whether webhook exists
     * @param failurePolicy              the failure policy
     * @param failurePolicyRecommendation failure policy guidance
     * @param mode                       the operating mode
     * @param modeRecommendation         mode guidance
     * @param certificateValid           whether cert is valid
     * @param certificateDaysUntilExpiry days until cert expiry
     * @param coveredNamespaces          covered namespaces
     * @param checks                     individual checks
     * @param overallReady               overall readiness
     * @return a new DrainCleanerReadinessResponse
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static DrainCleanerReadinessResponse of(boolean deployed, boolean allReplicasReady,
                                                   boolean webhookConfigured, String failurePolicy,
                                                   String failurePolicyRecommendation,
                                                   String mode, String modeRecommendation,
                                                   boolean certificateValid,
                                                   Long certificateDaysUntilExpiry,
                                                   String coveredNamespaces,
                                                   List<ReadinessCheck> checks,
                                                   boolean overallReady) {
        return new DrainCleanerReadinessResponse(deployed, allReplicasReady,
            webhookConfigured, failurePolicy, failurePolicyRecommendation,
            mode, modeRecommendation, certificateValid,
            certificateDaysUntilExpiry,
            coveredNamespaces, checks, overallReady);
    }

    /**
     * Creates a not-deployed readiness response.
     *
     * @return a readiness response indicating drain cleaner is not deployed
     */
    public static DrainCleanerReadinessResponse notDeployed() {
        List<ReadinessCheck> checks = List.of(
            ReadinessCheck.of("Drain Cleaner deployed", false,
                "No Strimzi Drain Cleaner deployment found. "
                    + "Deploy it to handle graceful pod evictions during node drains.")
        );
        return new DrainCleanerReadinessResponse(
            false, false, false, null, null, null, null,
            false, null, null, checks, false);
    }

    /**
     * An individual readiness check result.
     *
     * @param name   the check name
     * @param passed whether the check passed
     * @param detail human-readable detail about the check result
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReadinessCheck(
        @JsonProperty("name") String name,
        @JsonProperty("passed") boolean passed,
        @JsonProperty("detail") String detail
    ) {

        /**
         * Creates a readiness check result.
         *
         * @param name   the check name
         * @param passed whether it passed
         * @param detail the detail
         * @return a new ReadinessCheck
         */
        public static ReadinessCheck of(String name, boolean passed, String detail) {
            return new ReadinessCheck(name, passed, detail);
        }
    }
}
