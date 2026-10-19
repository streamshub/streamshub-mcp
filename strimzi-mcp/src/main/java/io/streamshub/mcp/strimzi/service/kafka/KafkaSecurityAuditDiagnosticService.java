/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafka;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.Elicitation;
import io.quarkiverse.mcp.server.InputRequiredException;
import io.quarkiverse.mcp.server.McpException;
import io.quarkiverse.mcp.server.Progress;
import io.quarkiverse.mcp.server.Sampling;
import io.streamshub.mcp.common.service.BaseDiagnosticService;
import io.streamshub.mcp.common.service.DiagnosticHelper;
import io.streamshub.mcp.common.util.InputUtils;
import io.streamshub.mcp.common.util.McpErrors;
import io.streamshub.mcp.common.util.NamespaceElicitationHelper;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaCertificateResponse;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaClusterResponse;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaSecurityAuditReport;
import io.streamshub.mcp.strimzi.dto.kafkauser.KafkaUserAclMatrixResponse;
import io.streamshub.mcp.strimzi.dto.kafkauser.KafkaUserResponse;
import io.streamshub.mcp.strimzi.service.kafkauser.KafkaUserService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates a multistep security audit workflow for a Kafka cluster.
 *
 * <p>Server-driven companion to the {@code audit-security} prompt template. Gathers the cluster
 * authentication/authorization configuration, certificate expiry, the KafkaUser inventory, and the
 * ACL matrix (with over-broad grants pre-flagged) in a single call. When Sampling is supported it
 * uses an LLM to triage the ACL deep-dive and to produce a structured security analysis.</p>
 *
 * <p>Individual step failures do not abort the workflow. Failed steps are recorded and the report
 * includes all data that was successfully gathered.</p>
 */
@ApplicationScoped
public class KafkaSecurityAuditDiagnosticService extends BaseDiagnosticService {

    private static final Logger LOG = Logger.getLogger(KafkaSecurityAuditDiagnosticService.class);

    private static final int PHASE1_STEPS = 3;
    private static final int MAX_PHASE2_STEPS = 1;
    private static final String STEP_CLUSTER_SECURITY = "cluster_security";
    private static final String STEP_CERTIFICATES = "certificates";
    private static final String STEP_USERS = "users";
    private static final String STEP_ACLS = "acls";

    private static final List<String> ACL_RESOURCE_TYPES =
        List.of("topic", "group", "transactionalId", "cluster");

    @Inject
    KafkaService kafkaService;

    @Inject
    KafkaCertificateService certificateService;

    @Inject
    KafkaUserService userService;

    @Override
    protected Logger getLogger() {
        return LOG;
    }

    KafkaSecurityAuditDiagnosticService() {
    }

    /**
     * Run a multistep security audit workflow for a Kafka cluster.
     *
     * @param namespace    optional namespace (elicited if ambiguous)
     * @param clusterName  the Kafka cluster name
     * @param sampling     MCP Sampling for LLM analysis (may be unsupported)
     * @param elicitation  MCP Elicitation for user input (may be unsupported)
     * @param progress     MCP progress tracking
     * @param cancellation MCP cancellation checking
     * @return a consolidated security audit report
     */
    public KafkaSecurityAuditReport audit(final String namespace,
                                          final String clusterName,
                                          final Sampling sampling,
                                          final Elicitation elicitation,
                                          final Progress progress,
                                          final Cancellation cancellation) {
        String ns = InputUtils.normalizeInput(namespace);
        ns = DiagnosticHelper.effectiveNamespace(sampling, ns);
        String name = InputUtils.normalizeInput(clusterName);

        if (name == null) {
            throw McpErrors.invalidParams("Cluster name is required");
        }

        LOG.infof("Starting security audit for Kafka cluster=%s (namespace=%s)",
            name, ns != null ? ns : "auto");

        AtomicBoolean cancelled = new AtomicBoolean(false);
        DiagnosticHelper.registerCancellationCallback(cancellation, cancelled);

        List<String> completed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int stepIndex = 0;
        int maxSteps = PHASE1_STEPS + MAX_PHASE2_STEPS;

        // === Phase 1: breadth ===
        ClusterSecurity clusterSecurity = gatherClusterSecurity(ns, name, elicitation, completed);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, maxSteps,
            "Checked Kafka cluster security configuration: authorization="
                + (clusterSecurity.authorizationType() != null ? clusterSecurity.authorizationType() : "none"));
        DiagnosticHelper.checkCancellation(cancellation);

        String resolvedNs = clusterSecurity.cluster().namespace();

        KafkaCertificateResponse certificates = gatherCertificates(resolvedNs, name, completed, failed);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, maxSteps,
            certificates != null ? "Checked Kafka cluster certificates" : "Failed to check Kafka cluster certificates");
        DiagnosticHelper.checkCancellation(cancellation);

        List<KafkaUserResponse> users = gatherUsers(resolvedNs, name, completed, failed);
        DiagnosticHelper.sendProgress(progress, ++stepIndex, maxSteps,
            String.format("Found %d KafkaUsers", users.size()));
        DiagnosticHelper.checkCancellation(cancellation);

        // === Phase 2: ACL breadth deep-dive (triage-guided) ===
        InvestigationAreas areas = investigateAreas(sampling, clusterSecurity, users, cancelled);
        DiagnosticHelper.checkAsyncCancellation(cancelled);

        int totalSteps = PHASE1_STEPS + areas.enabledCount();

        List<KafkaUserAclMatrixResponse> aclMatrices = null;
        if (areas.acls && !users.isEmpty()) {
            aclMatrices = gatherAclMatrices(resolvedNs, name, completed, failed);
            DiagnosticHelper.sendProgress(progress, ++stepIndex, totalSteps,
                aclMatrices != null ? "Audited KafkaUser ACL breadth" : "Failed to audit KafkaUser ACL breadth");
            DiagnosticHelper.checkCancellation(cancellation);
        }

        // === Phase 3: analysis ===
        String analysis = produceAnalysis(sampling, clusterSecurity, certificates, users, aclMatrices, cancelled);
        DiagnosticHelper.checkAsyncCancellation(cancelled);

        return KafkaSecurityAuditReport.of(
            clusterSecurity.cluster(), clusterSecurity.authorizationType(), certificates,
            users, aclMatrices, analysis, completed, failed.isEmpty() ? null : failed);
    }

    // ---- Phase 1: breadth ----

    @WithSpan("audit.security.cluster")
    ClusterSecurity gatherClusterSecurity(final String namespace,
                                          final String clusterName,
                                          final Elicitation elicitation,
                                          final List<String> completed) {
        try {
            KafkaClusterResponse cluster = kafkaService.getCluster(namespace, clusterName);
            String authorizationType = kafkaService.getAuthorizationType(cluster.namespace(), clusterName);
            completed.add(STEP_CLUSTER_SECURITY);
            return new ClusterSecurity(cluster, authorizationType);
        } catch (McpException e) {
            if (NamespaceElicitationHelper.isMultipleNamespacesError(e)
                    && elicitation != null && elicitation.isFormModeSupported()) {
                String resolved = NamespaceElicitationHelper.elicitNamespaceMrtr(
                    e, elicitation, "audited", "namespace");
                return gatherClusterSecurity(resolved, clusterName, null, completed);
            }
            throw e;
        }
    }

    @WithSpan("audit.security.certificates")
    KafkaCertificateResponse gatherCertificates(final String namespace,
                                                final String clusterName,
                                                final List<String> completed,
                                                final List<String> failed) {
        try {
            KafkaCertificateResponse result = certificateService.getCertificates(namespace, clusterName, null);
            completed.add(STEP_CERTIFICATES);
            return result;
        } catch (InputRequiredException e) {
            // MRTR control signal, not a step failure: must propagate uncaught to the framework.
            throw e;
        } catch (Exception e) {
            LOG.warnf("Failed to check Kafka cluster certificates: %s", e.getMessage());
            failed.add(STEP_CERTIFICATES + ": " + e.getMessage());
            return null;
        }
    }

    @WithSpan("audit.security.users")
    List<KafkaUserResponse> gatherUsers(final String namespace,
                                        final String clusterName,
                                        final List<String> completed,
                                        final List<String> failed) {
        try {
            List<KafkaUserResponse> result = userService.listUsers(namespace, clusterName);
            completed.add(STEP_USERS);
            return result;
        } catch (InputRequiredException e) {
            // MRTR control signal, not a step failure: must propagate uncaught to the framework.
            throw e;
        } catch (Exception e) {
            LOG.warnf("Failed to enumerate KafkaUsers: %s", e.getMessage());
            failed.add(STEP_USERS + ": " + e.getMessage());
            return List.of();
        }
    }

    // ---- Phase 2: ACL breadth ----

    @WithSpan("audit.security.acls")
    List<KafkaUserAclMatrixResponse> gatherAclMatrices(final String namespace,
                                                       final String clusterName,
                                                       final List<String> completed,
                                                       final List<String> failed) {
        try {
            List<KafkaUserAclMatrixResponse> matrices = new ArrayList<>();
            for (String resourceType : ACL_RESOURCE_TYPES) {
                KafkaUserAclMatrixResponse matrix =
                    userService.getAclMatrix(namespace, clusterName, resourceType);
                if (matrix.principalCount() > 0) {
                    matrices.add(matrix);
                }
            }
            completed.add(STEP_ACLS);
            // Empty (not null) when the deep-dive ran but no principals have ACLs; null is
            // reserved for the skipped/failed cases so the three outcomes stay distinguishable.
            return matrices;
        } catch (InputRequiredException e) {
            // MRTR control signal, not a step failure: must propagate uncaught to the framework.
            throw e;
        } catch (Exception e) {
            LOG.warnf("Failed to audit KafkaUser ACL breadth: %s", e.getMessage());
            failed.add(STEP_ACLS + ": " + e.getMessage());
            return null;
        }
    }

    // ---- Sampling: triage and analysis ----

    @WithSpan("audit.security.triage")
    InvestigationAreas investigateAreas(final Sampling sampling,
                                        final ClusterSecurity clusterSecurity,
                                        final List<KafkaUserResponse> users,
                                        final AtomicBoolean cancelled) {
        Map<String, Object> parsed = performTriage(sampling, TRIAGE_SYSTEM_PROMPT,
            buildPhase1Summary(clusterSecurity, users), cancelled);
        return parsed != null ? parseInvestigationAreas(parsed) : InvestigationAreas.all();
    }

    @WithSpan("audit.security.analysis")
    @SuppressWarnings("checkstyle:ParameterNumber")
    String produceAnalysis(final Sampling sampling,
                           final ClusterSecurity clusterSecurity,
                           final KafkaCertificateResponse certificates,
                           final List<KafkaUserResponse> users,
                           final List<KafkaUserAclMatrixResponse> aclMatrices,
                           final AtomicBoolean cancelled) {
        return performAnalysisMrtr(sampling, ANALYSIS_SYSTEM_PROMPT,
            buildFullSummary(clusterSecurity, certificates, users, aclMatrices),
            "analysis", clusterSecurity.cluster().namespace(), cancelled);
    }

    // ---- Helpers ----

    private Map<String, Object> buildPhase1Summary(final ClusterSecurity clusterSecurity,
                                                   final List<KafkaUserResponse> users) {
        Map<String, Object> summary = new LinkedHashMap<>();
        DiagnosticHelper.putIfNotNull(summary, "authentication_enabled",
            clusterSecurity.cluster().authenticationEnabled());
        DiagnosticHelper.putIfNotNull(summary, "authorization_enabled",
            clusterSecurity.cluster().authorizationEnabled());
        DiagnosticHelper.putIfNotNull(summary, "authorization_type", clusterSecurity.authorizationType());
        summary.put("user_count", users.size());
        long usersWithAcls = users.stream()
            .filter(u -> u.aclCount() != null && u.aclCount() > 0)
            .count();
        summary.put("users_with_acls", usersWithAcls);
        return summary;
    }

    private Map<String, Object> buildFullSummary(final ClusterSecurity clusterSecurity,
                                                 final KafkaCertificateResponse certificates,
                                                 final List<KafkaUserResponse> users,
                                                 final List<KafkaUserAclMatrixResponse> aclMatrices) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(STEP_CLUSTER_SECURITY, clusterSecurity.cluster());
        DiagnosticHelper.putIfNotNull(data, "authorization_type", clusterSecurity.authorizationType());
        DiagnosticHelper.putIfNotNull(data, STEP_CERTIFICATES, certificates);
        data.put(STEP_USERS, users);
        DiagnosticHelper.putIfNotNull(data, STEP_ACLS, aclMatrices);
        return data;
    }

    private InvestigationAreas parseInvestigationAreas(final Map<String, Object> parsed) {
        return new InvestigationAreas(Boolean.TRUE.equals(parsed.get(STEP_ACLS)));
    }

    /**
     * Resolved cluster security configuration gathered in Phase 1.
     *
     * @param cluster           the cluster status and listener/authentication configuration
     * @param authorizationType the cluster authorization type (null if none configured)
     */
    record ClusterSecurity(KafkaClusterResponse cluster, String authorizationType) {
    }

    /**
     * Flags indicating which investigation areas the LLM recommended.
     *
     * @param acls whether to run the KafkaUser ACL breadth audit
     */
    record InvestigationAreas(boolean acls) {

        /**
         * Returns areas with all flags set to true (fallback when Sampling is unavailable).
         *
         * @return investigation areas with all flags enabled
         */
        static InvestigationAreas all() {
            return new InvestigationAreas(true);
        }

        /**
         * Count how many investigation areas are enabled.
         *
         * @return the number of enabled investigation areas
         */
        private int enabledCount() {
            return acls ? 1 : 0;
        }
    }

    // ---- Sampling system prompts ----

    static final String TRIAGE_SYSTEM_PROMPT = """
        You are a Kafka security auditing assistant. Based on the cluster authentication and \
        authorization configuration and the KafkaUser inventory, decide whether a deep ACL audit \
        is warranted. Return ONLY a JSON object with one boolean field: acls. \
        Set acls to true when any users have authorization/ACLs configured or when authorization \
        is enabled on the cluster, so the audit can inspect ACL breadth. \
        Set acls to false only when there are no users or no ACLs to inspect.\
        """;

    static final String ANALYSIS_SYSTEM_PROMPT = """
        You are auditing the security posture of a Kafka cluster. Analyze all the gathered data \
        and produce a structured security report.

        Flag findings in three severity tiers:

        HIGH RISK:
        - Listeners with no authentication (open access points)
        - ACL grants with 'All' operations (superuser-level access)
        - ACL grants on the 'cluster' resource (cluster admin access)
        - ACL grants with wildcard resource name '*' (matches all resources)
        - Users with authentication but no authorization/ACLs (unrestricted access when the cluster \
        uses the built-in 'simple' authorizer)
        - Certificates expired or expiring within 30 days

        MEDIUM RISK:
        - Users without quotas (resource exhaustion / DoS vector)
        - Overly broad prefix ACL patterns

        INFORMATIONAL:
        - 'deny' ACL rules (verify they are intentional hardening)
        - 'tls-external' authentication (certificates managed outside Strimzi)
        - Multiple authentication types in use (operational complexity)

        Each ACL matrix already includes a pre-computed broad_grants list flagging wildcard and \
        'All' grants with a reason -- incorporate these directly.

        Structure your response as:
        - Summary (total users, authentication type distribution, listener authentication coverage, \
        certificate health, authorization type)
        - High-risk findings (each with the affected user or listener and specific details)
        - Medium-risk findings
        - Informational findings
        - Prioritized recommendations (close open listeners, apply least privilege to broad ACLs, \
        add quotas, renew certificates)\
        """;
}
