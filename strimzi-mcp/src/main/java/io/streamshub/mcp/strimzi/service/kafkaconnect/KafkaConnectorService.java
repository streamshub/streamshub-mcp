/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafkaconnect;

import io.quarkiverse.mcp.server.ToolCallException;
import io.streamshub.mcp.common.config.KubernetesConstants;
import io.streamshub.mcp.common.dto.ConditionInfo;
import io.streamshub.mcp.common.service.KubernetesResourceService;
import io.streamshub.mcp.common.util.InputUtils;
import io.streamshub.mcp.strimzi.dto.kafkaconnect.KafkaConnectorResponse;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.common.Condition;
import io.strimzi.api.kafka.model.connector.KafkaConnector;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for KafkaConnector operations.
 */
@ApplicationScoped
public class KafkaConnectorService {

    private static final Logger LOG = Logger.getLogger(KafkaConnectorService.class);
    private static final String DEFAULT_STATE = "running";

    @Inject
    KubernetesResourceService k8sService;

    KafkaConnectorService() {
    }

    /**
     * List KafkaConnectors, optionally filtered by namespace and parent KafkaConnect cluster.
     *
     * @param namespace      the namespace, or null for all namespaces
     * @param connectCluster the parent KafkaConnect cluster name, or null for all
     * @return list of connector responses
     */
    public List<KafkaConnectorResponse> listConnectors(final String namespace, final String connectCluster) {
        String ns = InputUtils.normalizeInput(namespace);
        String cluster = InputUtils.normalizeInput(connectCluster);

        LOG.infof("Listing KafkaConnectors (namespace=%s, connectCluster=%s)",
            ns != null ? ns : "all", cluster != null ? cluster : "all");

        List<KafkaConnector> connectors;
        if (cluster != null) {
            if (ns != null) {
                connectors = k8sService.queryResourcesByLabel(
                    KafkaConnector.class, ns, ResourceLabels.STRIMZI_CLUSTER_LABEL, cluster);
            } else {
                connectors = k8sService.queryResourcesByLabelInAnyNamespace(
                    KafkaConnector.class, ResourceLabels.STRIMZI_CLUSTER_LABEL, cluster);
            }
        } else {
            if (ns != null) {
                connectors = k8sService.queryResources(KafkaConnector.class, ns);
            } else {
                connectors = k8sService.queryResourcesInAnyNamespace(KafkaConnector.class);
            }
        }

        return connectors.stream()
            .map(this::createConnectorSummary)
            .toList();
    }

    /**
     * Get a specific KafkaConnector by name.
     *
     * @param namespace     the namespace, or null for auto-discovery
     * @param connectorName the connector name
     * @return the connector response
     */
    public KafkaConnectorResponse getConnector(final String namespace, final String connectorName) {
        String ns = InputUtils.normalizeInput(namespace);
        String normalizedName = InputUtils.normalizeInput(connectorName);

        if (normalizedName == null) {
            throw new ToolCallException("Connector name is required");
        }

        LOG.infof("Getting KafkaConnector name=%s (namespace=%s)", normalizedName, ns != null ? ns : "auto");

        KafkaConnector connector;
        if (ns != null) {
            connector = k8sService.getResource(KafkaConnector.class, ns, normalizedName);
        } else {
            connector = findConnectorInAllNamespaces(normalizedName);
        }

        if (connector == null) {
            if (ns != null) {
                throw new ToolCallException(
                    "KafkaConnector '" + normalizedName + "' not found in namespace " + ns);
            } else {
                throw new ToolCallException(
                    "KafkaConnector '" + normalizedName + "' not found in any namespace");
            }
        }

        return createConnectorDetail(connector);
    }

    private KafkaConnector findConnectorInAllNamespaces(final String connectorName) {
        List<KafkaConnector> all = k8sService.queryResourcesInAnyNamespace(KafkaConnector.class);
        List<KafkaConnector> matching = all.stream()
            .filter(c -> connectorName.equals(c.getMetadata().getName()))
            .toList();

        if (matching.isEmpty()) {
            return null;
        }

        if (matching.size() > 1) {
            String namespaces = matching.stream()
                .map(c -> c.getMetadata().getNamespace())
                .distinct()
                .collect(Collectors.joining(", "));
            throw new ToolCallException("Multiple KafkaConnectors named '" + connectorName
                + "' found in namespaces: " + namespaces + ". Please specify namespace.");
        }

        LOG.debugf("Discovered KafkaConnector %s in namespace %s",
            connectorName, matching.getFirst().getMetadata().getNamespace());
        return matching.getFirst();
    }

    private KafkaConnectorResponse createConnectorSummary(final KafkaConnector connector) {
        return KafkaConnectorResponse.summary(
            connector.getMetadata().getName(),
            connector.getMetadata().getNamespace(),
            extractConnectCluster(connector),
            extractClassName(connector),
            extractTasksMax(connector),
            extractState(connector),
            determineResourceStatus(connector),
            extractAutoRestart(connector),
            extractTopics(connector),
            extractConditions(connector));
    }

    private KafkaConnectorResponse createConnectorDetail(final KafkaConnector connector) {
        return KafkaConnectorResponse.of(
            connector.getMetadata().getName(),
            connector.getMetadata().getNamespace(),
            extractConnectCluster(connector),
            extractClassName(connector),
            extractTasksMax(connector),
            extractState(connector),
            determineResourceStatus(connector),
            extractAutoRestart(connector),
            extractTopics(connector),
            extractConnectorStatus(connector),
            extractConditions(connector),
            extractConfig(connector));
    }

    private String extractConnectCluster(final KafkaConnector connector) {
        Map<String, String> labels = connector.getMetadata().getLabels();
        return labels != null ? labels.get(ResourceLabels.STRIMZI_CLUSTER_LABEL) : null;
    }

    private String extractClassName(final KafkaConnector connector) {
        return connector.getSpec() != null ? connector.getSpec().getClassName() : null;
    }

    private Integer extractTasksMax(final KafkaConnector connector) {
        return connector.getSpec() != null ? connector.getSpec().getTasksMax() : null;
    }

    private String extractState(final KafkaConnector connector) {
        if (connector.getSpec() != null && connector.getSpec().getState() != null) {
            return connector.getSpec().getState().toValue().toLowerCase(Locale.ROOT);
        }
        return DEFAULT_STATE;
    }

    private String determineResourceStatus(final KafkaConnector connector) {
        if (connector.getStatus() == null || connector.getStatus().getConditions() == null
            || connector.getStatus().getConditions().isEmpty()) {
            return KubernetesConstants.ResourceStatus.UNKNOWN;
        }

        List<Condition> conditions = connector.getStatus().getConditions();
        boolean ready = conditions.stream().anyMatch(c ->
            KubernetesConstants.Conditions.TYPE_READY.equals(c.getType())
                && KubernetesConstants.Conditions.STATUS_TRUE.equals(c.getStatus()));
        if (ready) {
            return KubernetesConstants.ResourceStatus.READY;
        }

        boolean hasError = conditions.stream().anyMatch(c ->
            KubernetesConstants.Conditions.TYPE_READY.equals(c.getType())
                && KubernetesConstants.Conditions.STATUS_FALSE.equals(c.getStatus()));
        return hasError ? KubernetesConstants.ResourceStatus.ERROR : KubernetesConstants.ResourceStatus.NOT_READY;
    }

    private KafkaConnectorResponse.AutoRestartInfo extractAutoRestart(final KafkaConnector connector) {
        Boolean enabled = null;
        Integer maxRestarts = null;
        Integer restartCount = null;
        String lastRestartTime = null;

        if (connector.getSpec() != null && connector.getSpec().getAutoRestart() != null) {
            enabled = connector.getSpec().getAutoRestart().isEnabled();
            maxRestarts = connector.getSpec().getAutoRestart().getMaxRestarts();
        }

        if (connector.getStatus() != null && connector.getStatus().getAutoRestart() != null) {
            restartCount = connector.getStatus().getAutoRestart().getCount();
            lastRestartTime = connector.getStatus().getAutoRestart().getLastRestartTimestamp();
        }

        if (enabled == null && restartCount == null) {
            return null;
        }

        return KafkaConnectorResponse.AutoRestartInfo.of(enabled, maxRestarts, restartCount, lastRestartTime);
    }

    private List<String> extractTopics(final KafkaConnector connector) {
        if (connector.getStatus() != null && connector.getStatus().getTopics() != null) {
            return connector.getStatus().getTopics();
        }
        return null;
    }

    private Map<String, Object> extractConnectorStatus(final KafkaConnector connector) {
        if (connector.getStatus() != null) {
            return connector.getStatus().getConnectorStatus();
        }
        return null;
    }

    private Map<String, Object> extractConfig(final KafkaConnector connector) {
        return connector.getSpec() != null ? connector.getSpec().getConfig() : null;
    }

    private List<ConditionInfo> extractConditions(final KafkaConnector connector) {
        if (connector.getStatus() == null || connector.getStatus().getConditions() == null
            || connector.getStatus().getConditions().isEmpty()) {
            return null;
        }
        return connector.getStatus().getConditions().stream()
            .map(c -> ConditionInfo.of(
                c.getType(), c.getStatus(), c.getReason(),
                c.getMessage(), c.getLastTransitionTime()))
            .toList();
    }
}
