/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.operator;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarSource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.streamshub.mcp.common.config.KubernetesConstants;
import io.streamshub.mcp.common.dto.LogCollectionParams;
import io.streamshub.mcp.common.dto.PodLogsResult;
import io.streamshub.mcp.common.service.DeploymentService;
import io.streamshub.mcp.common.service.KubernetesResourceService;
import io.streamshub.mcp.common.service.log.LogCollectionService;
import io.streamshub.mcp.common.util.InputUtils;
import io.streamshub.mcp.common.util.McpErrors;
import io.streamshub.mcp.strimzi.config.StrimziConstants;
import io.streamshub.mcp.strimzi.dto.operator.StrimziOperatorConfigResponse;
import io.streamshub.mcp.strimzi.dto.operator.StrimziOperatorLogsResponse;
import io.streamshub.mcp.strimzi.dto.operator.StrimziOperatorResponse;
import io.strimzi.api.ResourceLabels;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
/**
 * Service for Strimzi operator operations.
 */
@ApplicationScoped
public class StrimziOperatorService {

    private static final Logger LOG = Logger.getLogger(StrimziOperatorService.class);
    private static final double MINUTES_PER_HOUR = 60.0;

    private static final String ENV_NAMESPACE = "STRIMZI_NAMESPACE";
    private static final String ENV_KAFKA_IMAGES = "STRIMZI_KAFKA_IMAGES";
    private static final String ENV_FEATURE_GATES = "STRIMZI_FEATURE_GATES";
    private static final String ENV_OPERATION_TIMEOUT_MS = "STRIMZI_OPERATION_TIMEOUT_MS";
    private static final String ENV_FULL_RECONCILIATION_INTERVAL_MS = "STRIMZI_FULL_RECONCILIATION_INTERVAL_MS";
    private static final String ENV_LEADER_ELECTION_ENABLED = "STRIMZI_LEADER_ELECTION_ENABLED";

    private static final String WATCH_ALL_NAMESPACES = "*";

    @Inject
    KubernetesResourceService k8sService;

    @Inject
    LogCollectionService logCollectionService;

    @Inject
    DeploymentService deploymentService;

    /**
     * No-arg constructor for CDI.
     */
    public StrimziOperatorService() {
    }

    /**
     * List Strimzi cluster operators, optionally filtered by namespace.
     *
     * @param namespace the namespace, or null for all namespaces
     * @return list of operator responses
     */
    public List<StrimziOperatorResponse> listOperators(final String namespace) {
        String ns = InputUtils.normalizeInput(namespace);
        LOG.infof("Listing Strimzi operators (namespace=%s)", ns != null ? ns : "all");

        List<Deployment> operators;
        if (ns != null) {
            operators = k8sService.queryResourcesByLabel(
                Deployment.class, ns, KubernetesConstants.Labels.APP, StrimziConstants.Operator.APP_LABEL_VALUE);
        } else {
            operators = k8sService.queryResourcesByLabelInAnyNamespace(
                Deployment.class, KubernetesConstants.Labels.APP, StrimziConstants.Operator.APP_LABEL_VALUE);
        }

        return operators.stream()
            .map(this::createOperatorResponse)
            .toList();
    }

    /**
     * Get specific Strimzi operator details.
     *
     * @param namespace    the namespace, or null for auto-discovery
     * @param operatorName the operator deployment name
     * @return the operator response
     */
    public StrimziOperatorResponse getOperator(final String namespace, final String operatorName) {
        String ns = InputUtils.normalizeInput(namespace);

        if (operatorName == null) {
            throw McpErrors.invalidParams("Operator name is required");
        }
        InputUtils.validateK8sName(operatorName, "operator name");
        InputUtils.validateK8sName(ns, "namespace");

        LOG.infof("Getting Strimzi operator name=%s in namespace=%s", operatorName, ns != null ? ns : "auto");

        Deployment operator;

        if (ns != null) {
            operator = k8sService.getResource(Deployment.class, ns, operatorName);
        } else {
            operator = findOperatorInAllNamespaces(operatorName);
        }

        if (operator == null) {
            throw McpErrors.notFound("Strimzi operator", operatorName, ns);
        }

        return createOperatorResponse(operator);
    }

    /**
     * Get the effective configuration of a Strimzi cluster operator.
     *
     * <p>Reads all environment variables from the operator Deployment. Entries whose value
     * comes from a {@code valueFrom} reference (Secret, ConfigMap, field, resource) are
     * represented as a descriptive string (e.g. {@code secretKeyRef:my-secret/timeout})
     * so that the reference target is visible without leaking the actual value.</p>
     *
     * @param namespace    the namespace, or null for auto-discovery
     * @param operatorName the operator deployment name, or null for auto-discovery
     * @return the operator configuration response
     */
    public StrimziOperatorConfigResponse getOperatorConfig(final String namespace, final String operatorName) {
        String ns = InputUtils.normalizeInput(namespace);
        InputUtils.validateK8sName(operatorName, "operator name");
        InputUtils.validateK8sName(ns, "namespace");

        LOG.infof("Getting Strimzi operator config name=%s in namespace=%s",
            operatorName != null ? operatorName : "auto", ns != null ? ns : "auto");

        Deployment operator = findOperatorDeployment(ns, operatorName);
        if (operator == null) {
            throw McpErrors.notFound("Strimzi operator", operatorName, ns);
        }

        Map<String, String> config = allEnv(operator);

        String watchedRaw = config.get(ENV_NAMESPACE);
        boolean watchesAll = watchedRaw == null || watchedRaw.contains(WATCH_ALL_NAMESPACES);
        List<String> watched = watchesAll ? List.of() : splitCsv(watchedRaw);

        return StrimziOperatorConfigResponse.of(
            operator.getMetadata().getName(),
            operator.getMetadata().getNamespace(),
            deploymentService.extractVersion(operator),
            config.get(ENV_FEATURE_GATES),
            watched,
            watchesAll,
            parseKafkaVersions(config.get(ENV_KAFKA_IMAGES)),
            config.get(ENV_OPERATION_TIMEOUT_MS),
            config.get(ENV_FULL_RECONCILIATION_INTERVAL_MS),
            config.get(ENV_LEADER_ELECTION_ENABLED),
            config);
    }

    /**
     * Collect all environment variables from the operator Deployment containers.
     * Entries with a literal {@code value} are stored as-is. Entries whose value comes
     * from a {@code valueFrom} reference are encoded as a descriptive string so the
     * reference target is visible without exposing the resolved value.
     *
     * @param deployment the operator Deployment
     * @return ordered map of env var name to value (or reference description)
     */
    private Map<String, String> allEnv(final Deployment deployment) {
        if (deployment.getSpec() == null || deployment.getSpec().getTemplate() == null
            || deployment.getSpec().getTemplate().getSpec() == null
            || deployment.getSpec().getTemplate().getSpec().getContainers() == null) {
            return Map.of();
        }

        Map<String, String> config = new LinkedHashMap<>();
        deployment.getSpec().getTemplate().getSpec().getContainers().stream()
            .flatMap(container -> container.getEnv() != null ? container.getEnv().stream() : Stream.empty())
            .forEach(env -> config.putIfAbsent(env.getName(), envValue(env)));
        return config;
    }

    /**
     * Returns the string representation of an env var's value.
     * If the entry has a literal value, that value is returned.
     * If the value comes from a {@code valueFrom} reference, a descriptive string is returned instead.
     *
     * @param env the environment variable entry
     * @return the literal value, or a reference description such as {@code secretKeyRef:name/key}
     */
    private String envValue(final EnvVar env) {
        if (env.getValue() != null) {
            return env.getValue();
        }
        EnvVarSource source = env.getValueFrom();
        if (source == null) {
            return null;
        }
        if (source.getSecretKeyRef() != null) {
            return "secretKeyRef:" + source.getSecretKeyRef().getName() + "/" + source.getSecretKeyRef().getKey();
        }
        if (source.getConfigMapKeyRef() != null) {
            return "configMapKeyRef:" + source.getConfigMapKeyRef().getName() + "/" + source.getConfigMapKeyRef().getKey();
        }
        if (source.getFieldRef() != null) {
            return "fieldRef:" + source.getFieldRef().getFieldPath();
        }
        if (source.getResourceFieldRef() != null) {
            return "resourceFieldRef:" + source.getResourceFieldRef().getResource();
        }
        return null;
    }

    /**
     * Parse the Kafka versions from the {@code STRIMZI_KAFKA_IMAGES} value, which is a
     * newline-separated list of {@code <version>=<image>} entries.
     */
    private List<String> parseKafkaVersions(final String kafkaImages) {
        if (kafkaImages == null || kafkaImages.isBlank()) {
            return List.of();
        }
        return kafkaImages.lines()
            .map(String::trim)
            .filter(line -> line.contains("="))
            .map(line -> line.substring(0, line.indexOf('=')).trim())
            .filter(version -> !version.isEmpty())
            .distinct()
            .toList();
    }

    private List<String> splitCsv(final String value) {
        return Stream.of(value.split(","))
            .map(String::trim)
            .filter(part -> !part.isEmpty())
            .toList();
    }

    /**
     * Locate an operator deployment by optional namespace and optional name.
     *
     * @param ns           the namespace, or null to search all namespaces
     * @param operatorName the deployment name, or null to match any operator
     * @return the deployment, or null if none matched
     */
    private Deployment findOperatorDeployment(final String ns, final String operatorName) {
        if (ns == null) {
            return findOperatorInAllNamespaces(operatorName);
        }
        if (operatorName != null) {
            return k8sService.getResource(Deployment.class, ns, operatorName);
        }

        List<Deployment> operators = k8sService.queryResourcesByLabel(
            Deployment.class, ns, KubernetesConstants.Labels.APP, StrimziConstants.Operator.APP_LABEL_VALUE);
        if (operators.isEmpty()) {
            return null;
        }
        if (operators.size() > 1) {
            throw McpErrors.invalidParams("Multiple Strimzi operators found in namespace " + ns
                + "; specify operatorName");
        }
        return operators.getFirst();
    }

    /**
     * Get logs from Entity Operator pods (specifically topic-operator or user-operator) for a Kafka cluster.
     *
     * @param namespace   the namespace (null for all namespaces)
     * @param clusterName the Kafka cluster name
     * @param options     log collection options
     * @return the operator logs response
     */
    public StrimziOperatorLogsResponse getEntityOperatorLogs(final String namespace, final String clusterName,
                                                            final LogCollectionParams options) {
        String ns = InputUtils.normalizeInput(namespace);

        LOG.infof("Getting entity operator logs (namespace=%s, cluster=%s, filter=%s, tailLines=%s)",
            ns, clusterName, options.filter() != null ? options.filter() : "none", options.tailLines());

        List<Pod> pods = findEntityOperatorPods(ns, clusterName);
        if (pods.isEmpty()) {
            return StrimziOperatorLogsResponse.notFound(ns != null ? ns : KubernetesConstants.UNKNOWN);
        }

        String resolvedNs = ns != null ? ns : pods.getFirst().getMetadata().getNamespace();
        PodLogsResult result = logCollectionService.collectLogs(resolvedNs, pods, options);
        return StrimziOperatorLogsResponse.of(resolvedNs, result.logs(), result.podNames(),
            result.hasErrors(), result.errorCount(), result.failedPods(),
            result.totalLines(), result.hasMore(), result.warnings());
    }

    /**
     * Get logs from Cluster Operator pods.
     *
     * @param namespace    the namespace (null for all namespaces)
     * @param operatorName the operator deployment name prefix (null for any)
     * @param options      log collection options
     * @return the operator logs response
     */
    public StrimziOperatorLogsResponse getOperatorLogs(final String namespace, final String operatorName,
                                                        final LogCollectionParams options) {
        String ns = InputUtils.normalizeInput(namespace);

        LOG.infof("Getting operator logs (namespace=%s, name=%s, filter=%s, tailLines=%s, previous=%s)",
            ns, operatorName, options.filter() != null ? options.filter() : "none",
            options.tailLines(), options.previous());

        if (ns == null) {
            ns = discoverOperatorNamespace(operatorName);
            if (ns == null) {
                return StrimziOperatorLogsResponse.notFound(KubernetesConstants.UNKNOWN);
            }
        }

        List<Pod> pods = findClusterOperatorPods(ns, operatorName);

        if (pods.isEmpty()) {
            return StrimziOperatorLogsResponse.notFound(ns);
        }

        PodLogsResult result = logCollectionService.collectLogs(ns, pods, options);
        return StrimziOperatorLogsResponse.of(ns, result.logs(), result.podNames(),
            result.hasErrors(), result.errorCount(), result.failedPods(),
            result.totalLines(), result.hasMore(), result.warnings());
    }

    /**
     * Find cluster operator pods, optionally filtered by namespace and operator name.
     * Used by metrics services and other consumers that need CO pod references.
     *
     * @param namespace    the namespace (null for all namespaces)
     * @param operatorName the operator deployment name prefix (null for any)
     * @return list of cluster operator pods (may be empty)
     */
    public List<Pod> findClusterOperatorPods(final String namespace, final String operatorName) {
        List<Pod> pods;

        if (namespace != null) {
            pods = k8sService.queryResourcesByLabel(
                Pod.class, namespace,
                ResourceLabels.STRIMZI_KIND_LABEL, StrimziConstants.KindValues.CLUSTER_OPERATOR);
        } else {
            pods = k8sService.queryResourcesByLabelInAnyNamespace(
                Pod.class,
                ResourceLabels.STRIMZI_KIND_LABEL, StrimziConstants.KindValues.CLUSTER_OPERATOR);
        }

        if (operatorName != null) {
            pods = pods.stream()
                .filter(pod -> pod.getMetadata().getName().startsWith(operatorName))
                .toList();
        }

        return pods;
    }

    /**
     * Find entity operator pods for a specific Kafka cluster.
     * The entity operator is deployed as part of a Kafka cluster and manages
     * KafkaUser and KafkaTopic custom resources.
     *
     * @param namespace   the namespace (null for all namespaces)
     * @param clusterName the Kafka cluster name
     * @return list of entity operator pods (may be empty)
     */
    public List<Pod> findEntityOperatorPods(final String namespace, final String clusterName) {
        Map<String, String> labels = Map.of(
            KubernetesConstants.Labels.APP_NAME, StrimziConstants.EntityOperator.APP_NAME_VALUE,
            ResourceLabels.STRIMZI_CLUSTER_LABEL, clusterName
        );

        if (namespace != null) {
            return k8sService.queryResourcesByLabels(Pod.class, namespace, labels);
        } else {
            return k8sService.queryResourcesByLabelsInAnyNamespace(Pod.class, labels);
        }
    }

    /**
     * Find a Strimzi operator deployment across all namespaces.
     * When {@code operatorName} is null, matches any operator.
     * Throws if multiple matching operators exist in different namespaces.
     *
     * @param operatorName the operator name, or null for any operator
     * @return the operator deployment, or null if not found
     */
    private Deployment findOperatorInAllNamespaces(final String operatorName) {
        List<Deployment> allOperators = k8sService.queryResourcesByLabelInAnyNamespace(
            Deployment.class, KubernetesConstants.Labels.APP, StrimziConstants.Operator.APP_LABEL_VALUE);

        List<Deployment> matching = allOperators.stream()
            .filter(op -> operatorName == null || operatorName.equals(op.getMetadata().getName()))
            .toList();

        if (matching.isEmpty()) {
            return null;
        }

        List<String> namespaces = matching.stream()
            .map(op -> op.getMetadata().getNamespace())
            .distinct()
            .toList();

        if (namespaces.size() > 1) {
            throw McpErrors.ambiguous("Strimzi operator", null, namespaces);
        }

        LOG.debugf("Discovered operator %s in namespace %s",
            matching.getFirst().getMetadata().getName(), namespaces.getFirst());
        return matching.getFirst();
    }

    /**
     * Discover the namespace of a Strimzi operator by name across all namespaces.
     * When {@code operatorName} is null, matches any operator.
     *
     * @param operatorName the operator name, or null for any operator
     * @return the namespace where the operator was found, or null if not found
     */
    private String discoverOperatorNamespace(final String operatorName) {
        Deployment operator = findOperatorInAllNamespaces(operatorName);
        return operator != null ? operator.getMetadata().getNamespace() : null;
    }

    private StrimziOperatorResponse createOperatorResponse(final Deployment deployment) {
        String name = deployment.getMetadata().getName();
        String namespace = deployment.getMetadata().getNamespace();

        Integer replicas = null;
        if (deployment.getSpec() != null) {
            replicas = deployment.getSpec().getReplicas();
        }

        Integer readyReplicas = null;
        if (deployment.getStatus() != null) {
            readyReplicas = deployment.getStatus().getReadyReplicas();
        }

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

        return StrimziOperatorResponse.of(name, namespace, ready, replicas, readyReplicas,
            version, image, uptimeHours, status);
    }
}
