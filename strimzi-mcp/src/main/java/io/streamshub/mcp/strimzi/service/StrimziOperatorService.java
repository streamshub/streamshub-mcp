/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.quarkiverse.mcp.server.ToolCallException;
import io.streamshub.mcp.common.config.KubernetesConstants;
import io.streamshub.mcp.common.dto.LogCollectionOptions;
import io.streamshub.mcp.common.dto.PodLogsResult;
import io.streamshub.mcp.common.service.DeploymentService;
import io.streamshub.mcp.common.service.KubernetesResourceService;
import io.streamshub.mcp.common.service.log.LogCollectionService;
import io.streamshub.mcp.common.util.InputUtils;
import io.streamshub.mcp.strimzi.config.StrimziConstants;
import io.streamshub.mcp.strimzi.dto.StrimziOperatorLogsResponse;
import io.streamshub.mcp.strimzi.dto.StrimziOperatorResponse;
import io.strimzi.api.ResourceLabels;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Service for Strimzi operator operations.
 */
@ApplicationScoped
public class StrimziOperatorService {

    private static final Logger LOG = Logger.getLogger(StrimziOperatorService.class);
    private static final double MINUTES_PER_HOUR = 60.0;

    @Inject
    KubernetesResourceService k8sService;

    @Inject
    LogCollectionService logCollectionService;

    @Inject
    DeploymentService deploymentService;

    StrimziOperatorService() {
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
            throw new ToolCallException("Operator name is required");
        }

        LOG.infof("Getting Strimzi operator name=%s in namespace=%s", operatorName, ns != null ? ns : "auto");

        Deployment operator;

        if (ns != null) {
            operator = k8sService.getResource(Deployment.class, ns, operatorName);
        } else {
            operator = findOperatorInAllNamespaces(operatorName);
        }

        if (operator == null) {
            throw new ToolCallException("Strimzi operator '" + operatorName + "' not found");
        }

        return createOperatorResponse(operator);
    }

    /**
     * Get logs for Strimzi operator pods with optional filtering, keyword matching, and log parameters.
     * Returns a StrimziOperatorLogsResponse (including notFound) rather than throwing,
     * since missing operator pods is a valid business response.
     *
     * @param namespace    the namespace, or null for auto-discovery
     * @param operatorName the operator name, or null for any operator
     * @param options      log collection options (filter, keywords, pagination, callbacks)
     * @return the operator logs response
     */
    public StrimziOperatorLogsResponse getOperatorLogs(final String namespace, final String operatorName,
                                                        final LogCollectionOptions options) {
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

        List<Pod> pods = k8sService.queryResourcesByLabel(
            Pod.class, ns, ResourceLabels.STRIMZI_KIND_LABEL, StrimziConstants.KindValues.CLUSTER_OPERATOR);

        if (pods.isEmpty()) {
            return StrimziOperatorLogsResponse.notFound(ns);
        }

        PodLogsResult result = logCollectionService.collectLogs(ns, pods, options);
        return StrimziOperatorLogsResponse.of(ns, result.logs(), result.podNames(),
            result.hasErrors(), result.errorCount(), result.totalLines(), result.hasMore());
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
            throw new ToolCallException("Multiple Strimzi operators found in namespaces: "
                + String.join(", ", namespaces) + ". Please specify namespace.");
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
