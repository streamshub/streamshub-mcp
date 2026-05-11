/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import io.quarkiverse.mcp.server.ToolCallException;
import io.streamshub.mcp.common.dto.ConditionInfo;
import io.streamshub.mcp.common.service.KubernetesQueryException;
import io.streamshub.mcp.common.service.KubernetesResourceService;
import io.streamshub.mcp.common.util.InputUtils;
import io.streamshub.mcp.strimzi.dto.KafkaRebalanceResponse;
import io.streamshub.mcp.strimzi.dto.KafkaRebalanceResponse.OptimizationResultInfo;
import io.streamshub.mcp.strimzi.dto.KafkaRebalanceResponse.RebalanceSpecInfo;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.common.Condition;
import io.strimzi.api.kafka.model.rebalance.KafkaRebalance;
import io.strimzi.api.kafka.model.rebalance.KafkaRebalanceSpec;
import io.strimzi.api.kafka.model.rebalance.KafkaRebalanceState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service for KafkaRebalance operations.
 */
@ApplicationScoped
public class KafkaRebalanceService {

    private static final Logger LOG = Logger.getLogger(KafkaRebalanceService.class);
    private static final String AUTO_APPROVAL_ANNOTATION = "strimzi.io/rebalance-auto-approval";
    private static final String REBALANCE_ANNOTATION = "strimzi.io/rebalance";
    private static final String STATE_UNKNOWN = "Unknown";
    private static final Set<String> ACTIVE_REBALANCE_STATES = Set.of(
        KafkaRebalanceState.New.name(),
        KafkaRebalanceState.PendingProposal.name(),
        KafkaRebalanceState.ProposalReady.name(),
        KafkaRebalanceState.Rebalancing.name(),
        KafkaRebalanceState.Stopped.name());

    @Inject
    KubernetesResourceService k8sService;

    KafkaRebalanceService() {
    }

    /**
     * List KafkaRebalances, optionally filtered by namespace and Kafka cluster.
     * Returns an empty list if the KafkaRebalance CRD is not installed.
     *
     * @param namespace   the namespace, or null for all namespaces
     * @param clusterName the Kafka cluster name filter, or null for all clusters
     * @return list of rebalance summary responses
     */
    public List<KafkaRebalanceResponse> listRebalances(final String namespace, final String clusterName) {
        String ns = InputUtils.normalizeInput(namespace);
        String cluster = InputUtils.normalizeInput(clusterName);

        LOG.infof("Listing KafkaRebalances (namespace=%s, cluster=%s)",
            ns != null ? ns : "all", cluster != null ? cluster : "all");

        List<KafkaRebalance> rebalances;
        try {
            if (cluster != null) {
                if (ns != null) {
                    rebalances = k8sService.queryResourcesByLabel(
                        KafkaRebalance.class, ns, ResourceLabels.STRIMZI_CLUSTER_LABEL, cluster);
                } else {
                    rebalances = k8sService.queryResourcesByLabelInAnyNamespace(
                        KafkaRebalance.class, ResourceLabels.STRIMZI_CLUSTER_LABEL, cluster);
                }
            } else {
                if (ns != null) {
                    rebalances = k8sService.queryResources(KafkaRebalance.class, ns);
                } else {
                    rebalances = k8sService.queryResourcesInAnyNamespace(KafkaRebalance.class);
                }
            }
        } catch (KubernetesQueryException e) {
            if (isCrdNotInstalled(e)) {
                LOG.debugf("KafkaRebalance CRD not installed, returning empty list");
                return List.of();
            }
            throw e;
        }

        return rebalances.stream()
            .map(this::createRebalanceSummary)
            .toList();
    }

    /**
     * Get a specific KafkaRebalance by name.
     *
     * @param namespace     the namespace, or null for auto-discovery
     * @param rebalanceName the rebalance name
     * @return the detailed rebalance response
     */
    public KafkaRebalanceResponse getRebalance(final String namespace, final String rebalanceName) {
        String ns = InputUtils.normalizeInput(namespace);
        String normalizedName = InputUtils.normalizeInput(rebalanceName);

        if (normalizedName == null) {
            throw new ToolCallException("Rebalance name is required");
        }
        InputUtils.validateK8sName(normalizedName, "rebalance name");
        InputUtils.validateK8sName(ns, "namespace");

        LOG.infof("Getting KafkaRebalance name=%s (namespace=%s)",
            normalizedName, ns != null ? ns : "auto");

        KafkaRebalance rebalance;
        if (ns != null) {
            rebalance = k8sService.getResource(KafkaRebalance.class, ns, normalizedName);
        } else {
            rebalance = findRebalanceInAllNamespaces(normalizedName);
        }

        if (rebalance == null) {
            if (ns != null) {
                throw new ToolCallException(
                    "KafkaRebalance '" + normalizedName + "' not found in namespace " + ns);
            } else {
                throw new ToolCallException(
                    "KafkaRebalance '" + normalizedName + "' not found in any namespace");
            }
        }

        return createRebalanceDetail(rebalance);
    }

    private boolean isCrdNotInstalled(final KubernetesQueryException e) {
        Throwable cause = e.getCause();
        if (cause == null) {
            return false;
        }
        String msg = cause.getMessage();
        return msg != null && (msg.contains("404") || msg.contains("the server doesn't have a resource type"));
    }

    private KafkaRebalance findRebalanceInAllNamespaces(final String rebalanceName) {
        List<KafkaRebalance> all;
        try {
            all = k8sService.queryResourcesInAnyNamespace(KafkaRebalance.class);
        } catch (KubernetesQueryException e) {
            if (isCrdNotInstalled(e)) {
                return null;
            }
            throw e;
        }

        List<KafkaRebalance> matching = all.stream()
            .filter(r -> rebalanceName.equals(r.getMetadata().getName()))
            .toList();

        if (matching.isEmpty()) {
            return null;
        }

        if (matching.size() > 1) {
            String namespaces = matching.stream()
                .map(r -> r.getMetadata().getNamespace())
                .distinct()
                .collect(Collectors.joining(", "));
            throw new ToolCallException("Multiple KafkaRebalances named '" + rebalanceName
                + "' found in namespaces: " + namespaces + ". Please specify namespace.");
        }

        LOG.debugf("Discovered KafkaRebalance %s in namespace %s",
            rebalanceName, matching.getFirst().getMetadata().getNamespace());
        return matching.getFirst();
    }

    private KafkaRebalanceResponse createRebalanceSummary(final KafkaRebalance rebalance) {
        return KafkaRebalanceResponse.summary(
            rebalance.getMetadata().getName(),
            rebalance.getMetadata().getNamespace(),
            extractCluster(rebalance),
            extractState(rebalance),
            extractMode(rebalance),
            extractAutoApproval(rebalance),
            extractSessionId(rebalance),
            extractOptimizationResult(rebalance),
            extractConditions(rebalance));
    }

    private KafkaRebalanceResponse createRebalanceDetail(final KafkaRebalance rebalance) {
        return KafkaRebalanceResponse.of(
            rebalance.getMetadata().getName(),
            rebalance.getMetadata().getNamespace(),
            extractCluster(rebalance),
            extractState(rebalance),
            extractMode(rebalance),
            extractAutoApproval(rebalance),
            extractSessionId(rebalance),
            extractOptimizationResult(rebalance),
            extractSpecInfo(rebalance),
            extractProgressConfigMap(rebalance),
            extractConditions(rebalance));
    }

    private String extractCluster(final KafkaRebalance rebalance) {
        Map<String, String> labels = rebalance.getMetadata().getLabels();
        return labels != null ? labels.get(ResourceLabels.STRIMZI_CLUSTER_LABEL) : null;
    }

    private String extractState(final KafkaRebalance rebalance) {
        if (rebalance.getStatus() == null || rebalance.getStatus().getConditions() == null) {
            return STATE_UNKNOWN;
        }
        return rebalance.getStatus().getConditions().stream()
            .filter(c -> "True".equals(c.getStatus()))
            .map(Condition::getType)
            .findFirst()
            .orElse(STATE_UNKNOWN);
    }

    private String extractMode(final KafkaRebalance rebalance) {
        if (rebalance.getSpec() != null && rebalance.getSpec().getMode() != null) {
            return rebalance.getSpec().getMode().toValue();
        }
        return null;
    }

    private Boolean extractAutoApproval(final KafkaRebalance rebalance) {
        Map<String, String> annotations = rebalance.getMetadata().getAnnotations();
        if (annotations == null) {
            return null;
        }
        String value = annotations.get(AUTO_APPROVAL_ANNOTATION);
        if (value == null) {
            return null;
        }
        return "true".equalsIgnoreCase(value);
    }

    private String extractSessionId(final KafkaRebalance rebalance) {
        if (rebalance.getStatus() != null) {
            return rebalance.getStatus().getSessionId();
        }
        return null;
    }

    private OptimizationResultInfo extractOptimizationResult(final KafkaRebalance rebalance) {
        if (rebalance.getStatus() == null) {
            return null;
        }
        return OptimizationResultInfo.fromMap(rebalance.getStatus().getOptimizationResult());
    }

    private RebalanceSpecInfo extractSpecInfo(final KafkaRebalance rebalance) {
        KafkaRebalanceSpec spec = rebalance.getSpec();
        if (spec == null) {
            return null;
        }
        return new RebalanceSpecInfo(
            spec.getMode() != null ? spec.getMode().toValue() : null,
            spec.getBrokers(),
            spec.getGoals(),
            spec.isSkipHardGoalCheck() ? Boolean.TRUE : null,
            spec.isRebalanceDisk() ? Boolean.TRUE : null,
            spec.getExcludedTopics(),
            spec.getConcurrentPartitionMovementsPerBroker() > 0
                ? spec.getConcurrentPartitionMovementsPerBroker() : null,
            spec.getConcurrentIntraBrokerPartitionMovements() > 0
                ? spec.getConcurrentIntraBrokerPartitionMovements() : null,
            spec.getConcurrentLeaderMovements() > 0
                ? spec.getConcurrentLeaderMovements() : null,
            spec.getReplicationThrottle() > 0
                ? spec.getReplicationThrottle() : null);
    }

    private String extractProgressConfigMap(final KafkaRebalance rebalance) {
        if (rebalance.getStatus() != null && rebalance.getStatus().getProgress() != null) {
            return rebalance.getStatus().getProgress().getRebalanceProgressConfigMap();
        }
        return null;
    }

    /**
     * Check if a rebalance state is active (not terminal).
     * Active states: New, PendingProposal, ProposalReady, Rebalancing, Stopped.
     *
     * @param state the rebalance state string
     * @return true if the state is active
     */
    public static boolean isActiveRebalanceState(final String state) {
        return state != null && ACTIVE_REBALANCE_STATES.contains(state);
    }

    private List<ConditionInfo> extractConditions(final KafkaRebalance rebalance) {
        if (rebalance.getStatus() == null || rebalance.getStatus().getConditions() == null
            || rebalance.getStatus().getConditions().isEmpty()) {
            return null;
        }
        return rebalance.getStatus().getConditions().stream()
            .map(c -> ConditionInfo.of(
                c.getType(), c.getStatus(), c.getReason(),
                c.getMessage(), c.getLastTransitionTime()))
            .toList();
    }
}
