/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.draincleaner;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.ValidatingWebhookConfiguration;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.quarkiverse.mcp.server.ToolCallException;
import io.streamshub.mcp.common.config.KubernetesConstants;
import io.streamshub.mcp.common.dto.LogCollectionParams;
import io.streamshub.mcp.common.dto.PodLogsResult;
import io.streamshub.mcp.common.service.DeploymentService;
import io.streamshub.mcp.common.service.KubernetesQueryException;
import io.streamshub.mcp.common.service.KubernetesResourceService;
import io.streamshub.mcp.common.service.log.LogCollectionService;
import io.streamshub.mcp.common.util.CertificateUtils;
import io.streamshub.mcp.common.util.EnvVarUtils;
import io.streamshub.mcp.common.util.InputUtils;
import io.streamshub.mcp.common.util.McpErrors;
import io.streamshub.mcp.strimzi.config.StrimziConstants;
import io.streamshub.mcp.strimzi.dto.draincleaner.DrainCleanerLogsResponse;
import io.streamshub.mcp.strimzi.dto.draincleaner.DrainCleanerReadinessResponse;
import io.streamshub.mcp.strimzi.dto.draincleaner.DrainCleanerReadinessResponse.ReadinessCheck;
import io.streamshub.mcp.strimzi.dto.draincleaner.DrainCleanerResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
/**
 * Service for Strimzi Drain Cleaner operations.
 */
@ApplicationScoped
public class DrainCleanerService {

    private static final Logger LOG = Logger.getLogger(DrainCleanerService.class);
    private static final double MINUTES_PER_HOUR = 60.0;
    private static final long CERT_EXPIRY_WARNING_DAYS = 30;
    private static final String TLS_CRT_KEY = "tls.crt";

    @Inject
    KubernetesResourceService k8sService;

    @Inject
    DeploymentService deploymentService;

    @Inject
    LogCollectionService logCollectionService;

    DrainCleanerService() {
    }

    /**
     * List Strimzi Drain Cleaner deployments, optionally filtered by namespace.
     *
     * @param namespace the namespace, or null for all namespaces
     * @return list of drain cleaner responses
     */
    public List<DrainCleanerResponse> listDrainCleaners(final String namespace) {
        String ns = InputUtils.normalizeInput(namespace);
        LOG.infof("Listing Strimzi Drain Cleaners (namespace=%s)", ns != null ? ns : "all");

        List<Deployment> deployments;
        if (ns != null) {
            deployments = k8sService.queryResourcesByLabel(
                Deployment.class, ns,
                KubernetesConstants.Labels.APP, StrimziConstants.DrainCleaner.APP_LABEL_VALUE);
        } else {
            deployments = k8sService.queryResourcesByLabelInAnyNamespace(
                Deployment.class,
                KubernetesConstants.Labels.APP, StrimziConstants.DrainCleaner.APP_LABEL_VALUE);
        }

        return deployments.stream()
            .map(d -> createDrainCleanerResponse(d, null, null))
            .toList();
    }

    /**
     * Get specific Strimzi Drain Cleaner details.
     *
     * @param namespace the namespace, or null for auto-discovery
     * @param name      the drain cleaner deployment name
     * @return the drain cleaner response
     */
    public DrainCleanerResponse getDrainCleaner(final String namespace, final String name) {
        String ns = InputUtils.normalizeInput(namespace);

        if (name == null) {
            throw McpErrors.invalidParams("Drain cleaner name is required");
        }
        InputUtils.validateK8sName(name, "drain cleaner name");
        InputUtils.validateK8sName(ns, "namespace");

        LOG.infof("Getting Strimzi Drain Cleaner name=%s in namespace=%s", name, ns != null ? ns : "auto");

        Deployment deployment;
        if (ns != null) {
            deployment = k8sService.getResource(Deployment.class, ns, name);
        } else {
            deployment = findDrainCleanerInAllNamespaces(name);
        }

        if (deployment == null) {
            throw McpErrors.notFound("Strimzi Drain Cleaner", name, ns);
        }

        ValidatingWebhookConfiguration webhook = k8sService.getClusterScopedResource(
            ValidatingWebhookConfiguration.class, StrimziConstants.DrainCleaner.WEBHOOK_CONFIG_NAME);

        String failurePolicy = extractFailurePolicy(webhook);

        return createDrainCleanerResponse(deployment, webhook != null, failurePolicy);
    }

    /**
     * Get logs from Strimzi Drain Cleaner pods.
     *
     * @param namespace the namespace, or null for auto-discovery
     * @param name      the drain cleaner name, or null for any
     * @param options   log collection options
     * @return the drain cleaner logs response
     */
    public DrainCleanerLogsResponse getDrainCleanerLogs(final String namespace, final String name,
                                                        final LogCollectionParams options) {
        String ns = InputUtils.normalizeInput(namespace);

        LOG.infof("Getting drain cleaner logs (namespace=%s, name=%s, filter=%s, tailLines=%s, previous=%s)",
            ns, name, options.filter() != null ? options.filter() : "none",
            options.tailLines(), options.previous());

        if (ns == null) {
            ns = discoverDrainCleanerNamespace(name);
            if (ns == null) {
                return DrainCleanerLogsResponse.notFound(KubernetesConstants.UNKNOWN);
            }
        }

        List<Pod> pods = findDrainCleanerPods(ns, name);

        if (pods.isEmpty()) {
            return DrainCleanerLogsResponse.notFound(ns);
        }

        PodLogsResult result = logCollectionService.collectLogs(ns, pods, options);
        return DrainCleanerLogsResponse.of(ns, result.logs(), result.podNames(),
            result.hasErrors(), result.errorCount(), result.failedPods(),
            result.totalLines(), result.hasMore(), result.warnings());
    }

    /**
     * Check production readiness of the Strimzi Drain Cleaner.
     *
     * @param namespace the namespace, or null for all namespaces
     * @return the readiness response
     */
    public DrainCleanerReadinessResponse checkReadiness(final String namespace) {
        String ns = InputUtils.normalizeInput(namespace);
        LOG.infof("Checking Drain Cleaner readiness (namespace=%s)", ns != null ? ns : "all");

        List<Deployment> deployments;
        try {
            if (ns != null) {
                deployments = k8sService.queryResourcesByLabel(
                    Deployment.class, ns,
                    KubernetesConstants.Labels.APP, StrimziConstants.DrainCleaner.APP_LABEL_VALUE);
            } else {
                deployments = k8sService.queryResourcesByLabelInAnyNamespace(
                    Deployment.class,
                    KubernetesConstants.Labels.APP, StrimziConstants.DrainCleaner.APP_LABEL_VALUE);
            }
        } catch (KubernetesQueryException e) {
            throw new ToolCallException(
                "Unable to check Drain Cleaner readiness: " + e.getMessage());
        }

        if (deployments.isEmpty()) {
            return DrainCleanerReadinessResponse.notDeployed();
        }

        Deployment deployment = deployments.getFirst();
        List<ReadinessCheck> checks = new ArrayList<>();

        checks.add(ReadinessCheck.of("Drain Cleaner deployed", true,
            String.format("Found %d drain cleaner deployment(s)", deployments.size())));

        boolean allReplicasReady = checkReplicas(deployment, checks);

        ValidatingWebhookConfiguration webhook = k8sService.getClusterScopedResource(
            ValidatingWebhookConfiguration.class, StrimziConstants.DrainCleaner.WEBHOOK_CONFIG_NAME);
        boolean webhookConfigured = webhook != null;
        checks.add(ReadinessCheck.of("Webhook configured", webhookConfigured,
            webhookConfigured
                ? "ValidatingWebhookConfiguration found"
                : "ValidatingWebhookConfiguration not found. Eviction interception is not active."));

        String failurePolicy = extractFailurePolicy(webhook);
        String failurePolicyRecommendation = null;
        if (failurePolicy != null) {
            checks.add(ReadinessCheck.of("Failure policy", true,
                "Failure policy is '" + failurePolicy + "'"));
        }

        OperatingMode operatingMode = checkOperatingMode(deployment, checks);

        String resolvedNs = deployment.getMetadata().getNamespace();
        CertificateInfo certInfo = parseTlsCertificate(resolvedNs);
        checks.add(ReadinessCheck.of("TLS certificate", certInfo.valid,
            certInfo.detail));

        EnvSetting watched = envSetting(deployment, StrimziConstants.DrainCleaner.ENV_DRAIN_NAMESPACES);
        boolean coverageKnown = !watched.unknown();
        String coveredNamespaces;
        if (!coverageKnown) {
            coveredNamespaces = watched.display();
        } else if (watched.value() == null) {
            coveredNamespaces = "all (no restriction)";
        } else {
            coveredNamespaces = watched.value();
        }
        checks.add(ReadinessCheck.of("Namespace coverage", coverageKnown,
            "Watching: " + coveredNamespaces));

        boolean overallReady = allReplicasReady && webhookConfigured && certInfo.valid
            && operatingMode.ready && coverageKnown;

        return DrainCleanerReadinessResponse.of(true, allReplicasReady,
            webhookConfigured, failurePolicy, failurePolicyRecommendation,
            operatingMode.mode, operatingMode.recommendation,
            certInfo.valid, certInfo.daysUntilExpiry,
            coveredNamespaces, checks, overallReady);
    }

    /**
     * Evaluate the operating mode from {@code STRIMZI_DENY_EVICTION}. An unset variable means the
     * default (standard mode). A variable set via {@code valueFrom}, or possibly via {@code envFrom},
     * cannot be resolved from the Deployment, so the mode is reported as unknown and the check
     * does not pass.
     */
    private OperatingMode checkOperatingMode(final Deployment deployment, final List<ReadinessCheck> checks) {
        EnvSetting var = envSetting(deployment, StrimziConstants.DrainCleaner.ENV_DENY_EVICTION);
        if (var.unknown()) {
            String confirm;
            if (var.fromEnvFrom()) {
                confirm = "Confirm the source does not define "
                    + StrimziConstants.DrainCleaner.ENV_DENY_EVICTION
                    + " (an unset variable means the default, standard mode),"
                    + " or that it resolves to 'true' (standard mode).";
            } else {
                confirm = "Confirm that " + StrimziConstants.DrainCleaner.ENV_DENY_EVICTION
                    + " resolves to 'true' (standard mode).";
            }
            String recommendation = "The operating mode cannot be verified ("
                + StrimziConstants.DrainCleaner.ENV_DENY_EVICTION + " " + var.unknownReason() + "). " + confirm;
            checks.add(ReadinessCheck.of("Operating mode", false, recommendation));
            return new OperatingMode(KubernetesConstants.UNKNOWN, recommendation, false);
        }

        boolean denyEviction = var.value() == null || Boolean.parseBoolean(var.value());
        String mode = denyEviction
            ? StrimziConstants.DrainCleaner.MODE_STANDARD
            : StrimziConstants.DrainCleaner.MODE_LEGACY;
        String recommendation = null;
        if (!denyEviction) {
            recommendation = "Legacy mode is active. Standard mode (deny eviction) is recommended "
                + "for better control over pod restarts during node drains.";
        }
        checks.add(ReadinessCheck.of("Operating mode", denyEviction,
            denyEviction
                ? "Standard mode (eviction denial enabled)"
                : "Legacy mode (evictions allowed, relying on PDB). Standard mode recommended."));
        return new OperatingMode(mode, recommendation, denyEviction);
    }

    private boolean checkReplicas(final Deployment deployment, final List<ReadinessCheck> checks) {
        Integer replicas = deployment.getSpec() != null ? deployment.getSpec().getReplicas() : null;
        Integer readyReplicas = deployment.getStatus() != null
            ? deployment.getStatus().getReadyReplicas() : null;
        boolean ready = replicas != null && replicas.equals(readyReplicas) && readyReplicas > 0;

        checks.add(ReadinessCheck.of("Replicas ready", ready,
            String.format("%d/%d replicas ready",
                readyReplicas != null ? readyReplicas : 0,
                replicas != null ? replicas : 0)));

        return ready;
    }

    private Deployment findDrainCleanerInAllNamespaces(final String name) {
        List<Deployment> allDrainCleaners = k8sService.queryResourcesByLabelInAnyNamespace(
            Deployment.class,
            KubernetesConstants.Labels.APP, StrimziConstants.DrainCleaner.APP_LABEL_VALUE);

        List<Deployment> matching = allDrainCleaners.stream()
            .filter(d -> name == null || name.equals(d.getMetadata().getName()))
            .toList();

        if (matching.isEmpty()) {
            return null;
        }

        List<String> namespaces = matching.stream()
            .map(d -> d.getMetadata().getNamespace())
            .distinct()
            .toList();

        if (namespaces.size() > 1) {
            throw McpErrors.ambiguous("Strimzi Drain Cleaner", null, namespaces);
        }

        return matching.getFirst();
    }

    private String discoverDrainCleanerNamespace(final String name) {
        Deployment deployment = findDrainCleanerInAllNamespaces(name);
        return deployment != null ? deployment.getMetadata().getNamespace() : null;
    }

    private List<Pod> findDrainCleanerPods(final String namespace, final String name) {
        List<Pod> pods = k8sService.queryResourcesByLabel(
            Pod.class, namespace,
            KubernetesConstants.Labels.APP, StrimziConstants.DrainCleaner.APP_LABEL_VALUE);

        if (name != null) {
            pods = pods.stream()
                .filter(pod -> pod.getMetadata().getName().startsWith(name))
                .toList();
        }

        return pods;
    }

    private DrainCleanerResponse createDrainCleanerResponse(final Deployment deployment,
                                                            final Boolean webhookConfigured,
                                                            final String failurePolicy) {
        String name = deployment.getMetadata().getName();
        String depNamespace = deployment.getMetadata().getNamespace();

        Integer replicas = deployment.getSpec() != null ? deployment.getSpec().getReplicas() : null;
        Integer readyReplicas = deployment.getStatus() != null
            ? deployment.getStatus().getReadyReplicas() : null;

        boolean ready = replicas != null && replicas.equals(readyReplicas) && readyReplicas > 0;
        String status = ready
            ? KubernetesConstants.HealthStatus.HEALTHY
            : KubernetesConstants.HealthStatus.DEGRADED;
        String version = deploymentService.extractVersion(deployment);
        String image = deploymentService.extractImage(deployment);
        Long uptimeMinutes = deploymentService.calculateUptimeMinutes(deployment);

        String uptimeHours = null;
        if (uptimeMinutes != null) {
            uptimeHours = String.format("%.1f", uptimeMinutes / MINUTES_PER_HOUR);
        }

        EnvSetting denyEvictionVar = envSetting(deployment, StrimziConstants.DrainCleaner.ENV_DENY_EVICTION);
        Boolean denyEviction = booleanValue(denyEvictionVar.value());

        String mode;
        if (denyEvictionVar.unknown()) {
            mode = KubernetesConstants.UNKNOWN;
        } else if (denyEviction == null || denyEviction) {
            mode = StrimziConstants.DrainCleaner.MODE_STANDARD;
        } else {
            mode = StrimziConstants.DrainCleaner.MODE_LEGACY;
        }

        Boolean drainKafka = booleanValue(
            envSetting(deployment, StrimziConstants.DrainCleaner.ENV_DRAIN_KAFKA).value());

        EnvSetting watched = envSetting(deployment, StrimziConstants.DrainCleaner.ENV_DRAIN_NAMESPACES);
        String watchedNamespaces = watched.unknown() ? watched.display() : watched.value();

        return DrainCleanerResponse.of(name, depNamespace, ready, replicas, readyReplicas,
            version, image, uptimeHours, status, mode,
            webhookConfigured, failurePolicy, denyEviction, drainKafka, watchedNamespaces);
    }

    /**
     * Resolve an environment variable of the first container as far as the Deployment allows: a
     * literal {@code env} value, or the reason the value cannot be read ({@code valueFrom}, or an
     * {@code envFrom} source that could supply it). {@code env} takes precedence over {@code envFrom}.
     */
    private EnvSetting envSetting(final Deployment deployment, final String varName) {
        if (deployment.getSpec() == null
            || deployment.getSpec().getTemplate() == null
            || deployment.getSpec().getTemplate().getSpec() == null
            || deployment.getSpec().getTemplate().getSpec().getContainers() == null
            || deployment.getSpec().getTemplate().getSpec().getContainers().isEmpty()) {
            return new EnvSetting(null, null, false);
        }

        Container container = deployment.getSpec().getTemplate().getSpec().getContainers().getFirst();
        EnvVar var = container.getEnv() == null ? null : container.getEnv().stream()
            .filter(env -> varName.equals(env.getName()))
            .findFirst()
            .orElse(null);

        if (var != null && var.getValue() == null && var.getValueFrom() != null) {
            return new EnvSetting(null, "set via valueFrom: " + EnvVarUtils.describeSource(var.getValueFrom()), false);
        }
        if (var != null) {
            return new EnvSetting(var.getValue(), null, false);
        }
        String envFrom = EnvVarUtils.describeEnvFrom(container.getEnvFrom(), varName);
        return new EnvSetting(null, envFrom != null ? "may be set via envFrom: " + envFrom : null, envFrom != null);
    }

    /** Literal boolean value of an env var; null when unset or unresolvable. */
    private static Boolean booleanValue(final String value) {
        return value == null ? null : Boolean.valueOf(value);
    }

    private String extractFailurePolicy(final ValidatingWebhookConfiguration webhook) {
        if (webhook == null || webhook.getWebhooks() == null || webhook.getWebhooks().isEmpty()) {
            return null;
        }
        return webhook.getWebhooks().getFirst().getFailurePolicy();
    }

    private CertificateInfo parseTlsCertificate(final String namespace) {
        String secretName = StrimziConstants.DrainCleaner.WEBHOOK_CONFIG_NAME;
        Secret secret;
        try {
            secret = k8sService.getResource(Secret.class, namespace, secretName);
        } catch (KubernetesQueryException e) {
            LOG.warnf("Unable to check TLS certificate for Drain Cleaner in namespace %s: %s",
                namespace, e.getMessage());
            return new CertificateInfo(false, null,
                "Unable to read TLS secret '" + secretName + "' in namespace '" + namespace
                    + "': " + e.getMessage()
                    + ". Ensure the MCP server has RBAC permissions to read Secrets in this namespace.");
        }

        if (secret == null) {
            return new CertificateInfo(false, null,
                "TLS secret '" + secretName + "' not found in namespace '" + namespace
                    + "'. Ensure the sensitive RBAC Role is configured or the secret exists.");
        }

        Map<String, String> data = secret.getData();
        if (data == null || !data.containsKey(TLS_CRT_KEY)) {
            return new CertificateInfo(false, null,
                "TLS secret exists but does not contain '" + TLS_CRT_KEY + "' key.");
        }

        List<X509Certificate> certs = CertificateUtils.parseCertificates(data.get(TLS_CRT_KEY));

        if (certs.isEmpty()) {
            return new CertificateInfo(false, null, "No certificates found in TLS secret.");
        }

        X509Certificate cert = certs.getFirst();
        long daysUntilExpiry = CertificateUtils.calculateDaysUntilExpiry(cert);

        if (CertificateUtils.isExpired(cert)) {
            return new CertificateInfo(false, daysUntilExpiry,
                "Certificate expired " + Math.abs(daysUntilExpiry) + " days ago.");
        }

        return new CertificateInfo(true, daysUntilExpiry,
            "Certificate valid, expires in " + daysUntilExpiry + " days.");
    }

    /**
     * An environment variable as read from the Deployment.
     *
     * @param value         the literal value, or null when unset or unresolvable
     * @param unknownReason why the value cannot be read, or null when it is literal or unset
     * @param fromEnvFrom   true when the unknown comes from an {@code envFrom} source that may
     *                      or may not actually define the variable (as opposed to a definite
     *                      {@code valueFrom} reference)
     */
    private record EnvSetting(String value, String unknownReason, boolean fromEnvFrom) {

        boolean unknown() {
            return unknownReason != null;
        }

        String display() {
            return KubernetesConstants.UNKNOWN + " (" + unknownReason + ")";
        }
    }

    private record OperatingMode(String mode, String recommendation, boolean ready) {
    }

    private record CertificateInfo(boolean valid, Long daysUntilExpiry, String detail) {
    }
}
