/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.quarkiverse.mcp.server.ToolCallException;
import io.streamshub.mcp.common.service.KubernetesResourceService;
import io.streamshub.mcp.common.util.InputUtils;
import io.streamshub.mcp.strimzi.dto.KafkaEffectiveConfigResponse;
import io.streamshub.mcp.strimzi.dto.KafkaEffectiveConfigResponse.AuthorizationInfo;
import io.streamshub.mcp.strimzi.dto.KafkaEffectiveConfigResponse.AutoRebalanceInfo;
import io.streamshub.mcp.strimzi.dto.KafkaEffectiveConfigResponse.BrokerCapacityInfo;
import io.streamshub.mcp.strimzi.dto.KafkaEffectiveConfigResponse.CruiseControlInfo;
import io.streamshub.mcp.strimzi.dto.KafkaEffectiveConfigResponse.EntityOperatorInfo;
import io.streamshub.mcp.strimzi.dto.KafkaEffectiveConfigResponse.JvmOptionsInfo;
import io.streamshub.mcp.strimzi.dto.KafkaEffectiveConfigResponse.KafkaExporterInfo;
import io.streamshub.mcp.strimzi.dto.KafkaEffectiveConfigResponse.ListenerConfigInfo;
import io.streamshub.mcp.strimzi.dto.KafkaEffectiveConfigResponse.LoggingConfigInfo;
import io.streamshub.mcp.strimzi.dto.KafkaEffectiveConfigResponse.MetricsConfigInfo;
import io.streamshub.mcp.strimzi.dto.KafkaEffectiveConfigResponse.NodePoolConfigInfo;
import io.streamshub.mcp.strimzi.dto.KafkaEffectiveConfigResponse.RackInfo;
import io.streamshub.mcp.strimzi.dto.KafkaEffectiveConfigResponse.ResourcesInfo;
import io.streamshub.mcp.strimzi.dto.KafkaEffectiveConfigResponse.SubOperatorInfo;
import io.strimzi.api.kafka.model.common.ExternalLogging;
import io.strimzi.api.kafka.model.common.InlineLogging;
import io.strimzi.api.kafka.model.common.JvmOptions;
import io.strimzi.api.kafka.model.common.Logging;
import io.strimzi.api.kafka.model.common.Rack;
import io.strimzi.api.kafka.model.common.TopologyLabelRack;
import io.strimzi.api.kafka.model.common.metrics.JmxPrometheusExporterMetrics;
import io.strimzi.api.kafka.model.common.metrics.MetricsConfig;
import io.strimzi.api.kafka.model.kafka.JbodStorage;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.kafka.KafkaAuthorization;
import io.strimzi.api.kafka.model.kafka.KafkaClusterSpec;
import io.strimzi.api.kafka.model.kafka.PersistentClaimStorage;
import io.strimzi.api.kafka.model.kafka.Storage;
import io.strimzi.api.kafka.model.kafka.cruisecontrol.BrokerCapacity;
import io.strimzi.api.kafka.model.kafka.cruisecontrol.CruiseControlSpec;
import io.strimzi.api.kafka.model.kafka.entityoperator.EntityOperatorSpec;
import io.strimzi.api.kafka.model.kafka.exporter.KafkaExporterSpec;
import io.strimzi.api.kafka.model.kafka.listener.GenericKafkaListener;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Service for extracting the effective configuration of a Kafka cluster.
 *
 * <p>Assembles configuration from the Kafka CR spec, resolves referenced
 * ConfigMaps for metrics and logging, and includes per-node-pool overrides.</p>
 */
@ApplicationScoped
public class KafkaConfigService {

    private static final Logger LOG = Logger.getLogger(KafkaConfigService.class);
    private static final long MILLISECONDS_PER_SECOND = 1000;

    @Inject
    KafkaService kafkaService;

    @Inject
    KafkaNodePoolService nodePoolService;

    @Inject
    KubernetesResourceService k8sService;

    KafkaConfigService() {
    }

    /**
     * Extract the effective configuration of a Kafka cluster.
     *
     * @param namespace   the namespace, or null for auto-discovery
     * @param clusterName the Kafka cluster name
     * @return the effective configuration response
     */
    public KafkaEffectiveConfigResponse getEffectiveConfig(final String namespace, final String clusterName) {
        String ns = InputUtils.normalizeInput(namespace);
        String name = InputUtils.normalizeInput(clusterName);

        if (name == null) {
            throw new ToolCallException("Cluster name is required");
        }

        LOG.infof("Getting effective config for cluster=%s (namespace=%s)", name, ns != null ? ns : "auto");

        Kafka kafka = kafkaService.findKafkaCluster(ns, name);
        String resolvedNs = kafka.getMetadata().getNamespace();
        KafkaClusterSpec kafkaSpec = kafka.getSpec() != null ? kafka.getSpec().getKafka() : null;

        List<NodePoolConfigInfo> nodePoolConfigs = extractNodePoolConfigs(resolvedNs, name);

        return new KafkaEffectiveConfigResponse(
            kafka.getMetadata().getName(),
            resolvedNs,
            kafkaSpec != null ? kafkaSpec.getVersion() : null,
            kafkaSpec != null ? kafkaSpec.getMetadataVersion() : null,
            kafkaSpec != null ? kafkaSpec.getConfig() : null,
            kafkaSpec != null ? extractResources(kafkaSpec.getResources()) : null,
            kafkaSpec != null ? extractJvmOptions(kafkaSpec.getJvmOptions()) : null,
            kafkaSpec != null ? extractRack(kafkaSpec.getRack()) : null,
            extractListenerConfigs(kafka),
            kafkaSpec != null ? extractAuthorization(kafkaSpec.getAuthorization()) : null,
            kafkaSpec != null ? extractMetricsConfig(kafkaSpec.getMetricsConfig(), resolvedNs) : null,
            kafkaSpec != null ? extractLogging(kafkaSpec.getLogging(), resolvedNs) : null,
            kafka.getSpec() != null ? extractEntityOperator(kafka.getSpec().getEntityOperator()) : null,
            kafka.getSpec() != null ? extractCruiseControl(kafka.getSpec().getCruiseControl()) : null,
            kafka.getSpec() != null ? extractKafkaExporter(kafka.getSpec().getKafkaExporter()) : null,
            kafka.getSpec() != null ? kafka.getSpec().getMaintenanceTimeWindows() : null,
            nodePoolConfigs.isEmpty() ? null : nodePoolConfigs
        );
    }

    /**
     * Extract CPU and memory resource requests and limits.
     *
     * @param resources the Kubernetes resource requirements
     * @return resources info, or null if not set
     */
    ResourcesInfo extractResources(final ResourceRequirements resources) {
        if (resources == null) {
            return null;
        }
        String cpuReq = null;
        String memReq = null;
        String cpuLim = null;
        String memLim = null;
        if (resources.getRequests() != null) {
            cpuReq = resources.getRequests().containsKey("cpu")
                ? resources.getRequests().get("cpu").toString() : null;
            memReq = resources.getRequests().containsKey("memory")
                ? resources.getRequests().get("memory").toString() : null;
        }
        if (resources.getLimits() != null) {
            cpuLim = resources.getLimits().containsKey("cpu")
                ? resources.getLimits().get("cpu").toString() : null;
            memLim = resources.getLimits().containsKey("memory")
                ? resources.getLimits().get("memory").toString() : null;
        }
        return new ResourcesInfo(cpuReq, cpuLim, memReq, memLim);
    }

    /**
     * Extract JVM options.
     *
     * @param jvmOpts the JVM options from the spec
     * @return JVM options info, or null if not set
     */
    JvmOptionsInfo extractJvmOptions(final JvmOptions jvmOpts) {
        if (jvmOpts == null) {
            return null;
        }
        List<Map<String, String>> sysprops = null;
        if (jvmOpts.getJavaSystemProperties() != null) {
            sysprops = jvmOpts.getJavaSystemProperties().stream()
                .map(sp -> Map.of("name", sp.getName(), "value", sp.getValue()))
                .toList();
        }
        return new JvmOptionsInfo(jvmOpts.getXms(), jvmOpts.getXmx(), sysprops, jvmOpts.getXx());
    }

    private RackInfo extractRack(final Rack rack) {
        if (rack == null) {
            return null;
        }
        if (rack instanceof TopologyLabelRack topologyRack) {
            return new RackInfo(topologyRack.getTopologyKey());
        }
        return new RackInfo(rack.getType());
    }

    private List<ListenerConfigInfo> extractListenerConfigs(final Kafka kafka) {
        if (kafka.getSpec() == null || kafka.getSpec().getKafka() == null
            || kafka.getSpec().getKafka().getListeners() == null) {
            return null;
        }
        return kafka.getSpec().getKafka().getListeners().stream()
            .map(this::mapListener)
            .toList();
    }

    private ListenerConfigInfo mapListener(final GenericKafkaListener listener) {
        String authType = listener.getAuth() != null ? listener.getAuth().getType() : null;
        return new ListenerConfigInfo(
            listener.getName(),
            listener.getPort(),
            listener.getType() != null ? listener.getType().toValue() : null,
            listener.isTls(),
            authType
        );
    }

    private AuthorizationInfo extractAuthorization(final KafkaAuthorization authz) {
        if (authz == null) {
            return null;
        }
        return new AuthorizationInfo(authz.getType());
    }

    /**
     * Extract metrics configuration and resolve the referenced ConfigMap content.
     *
     * @param metricsConfig the metrics config from the spec
     * @param namespace     the namespace for ConfigMap lookup
     * @return metrics config info, or null if not set
     */
    MetricsConfigInfo extractMetricsConfig(final MetricsConfig metricsConfig, final String namespace) {
        if (metricsConfig == null) {
            return null;
        }
        if (metricsConfig instanceof JmxPrometheusExporterMetrics jmx
                && jmx.getValueFrom() != null
                && jmx.getValueFrom().getConfigMapKeyRef() != null) {
            String cmName = jmx.getValueFrom().getConfigMapKeyRef().getName();
            String cmKey = jmx.getValueFrom().getConfigMapKeyRef().getKey();
            String content = resolveConfigMapContent(namespace, cmName, cmKey);
            String note = content == null
                ? String.format("ConfigMap '%s' key '%s' not found in namespace '%s'", cmName, cmKey, namespace)
                : null;
            return new MetricsConfigInfo(metricsConfig.getType(), cmName, cmKey, content, note);
        }
        return new MetricsConfigInfo(metricsConfig.getType(), null, null, null, null);
    }

    /**
     * Extract logging configuration, resolving the ConfigMap for external logging.
     *
     * @param logging   the logging config from the spec
     * @param namespace the namespace for ConfigMap lookup
     * @return logging config info, or null if not set
     */
    LoggingConfigInfo extractLogging(final Logging logging, final String namespace) {
        if (logging == null) {
            return null;
        }
        if (logging instanceof InlineLogging inline) {
            return new LoggingConfigInfo("inline", inline.getLoggers(), null, null, null, null);
        }
        if (logging instanceof ExternalLogging external
                && external.getValueFrom() != null
                && external.getValueFrom().getConfigMapKeyRef() != null) {
            String cmName = external.getValueFrom().getConfigMapKeyRef().getName();
            String cmKey = external.getValueFrom().getConfigMapKeyRef().getKey();
            String content = resolveConfigMapContent(namespace, cmName, cmKey);
            String note = content == null
                ? String.format("ConfigMap '%s' key '%s' not found in namespace '%s'", cmName, cmKey, namespace)
                : null;
            return new LoggingConfigInfo("external", null, cmName, cmKey, content, note);
        }
        return new LoggingConfigInfo(logging.getType(), null, null, null, null, null);
    }

    private EntityOperatorInfo extractEntityOperator(final EntityOperatorSpec entityOp) {
        if (entityOp == null) {
            return null;
        }
        SubOperatorInfo topicOp = null;
        if (entityOp.getTopicOperator() != null) {
            var to = entityOp.getTopicOperator();
            topicOp = new SubOperatorInfo(
                to.getWatchedNamespace(), msToSeconds(to.getReconciliationIntervalMs()),
                extractResources(to.getResources()), extractJvmOptions(to.getJvmOptions()));
        }
        SubOperatorInfo userOp = null;
        if (entityOp.getUserOperator() != null) {
            var uo = entityOp.getUserOperator();
            userOp = new SubOperatorInfo(
                uo.getWatchedNamespace(), msToSeconds(uo.getReconciliationIntervalMs()),
                extractResources(uo.getResources()), extractJvmOptions(uo.getJvmOptions()));
        }
        return new EntityOperatorInfo(topicOp, userOp);
    }

    private CruiseControlInfo extractCruiseControl(final CruiseControlSpec ccSpec) {
        if (ccSpec == null) {
            return null;
        }
        BrokerCapacityInfo capacity = extractBrokerCapacity(ccSpec.getBrokerCapacity());
        List<AutoRebalanceInfo> autoRebalance = null;
        if (ccSpec.getAutoRebalance() != null) {
            autoRebalance = ccSpec.getAutoRebalance().stream()
                .map(ar -> new AutoRebalanceInfo(ar.getMode() != null ? ar.getMode().toValue() : null))
                .toList();
        }
        return new CruiseControlInfo(
            ccSpec.getConfig(),
            capacity,
            autoRebalance,
            extractResources(ccSpec.getResources()),
            extractJvmOptions(ccSpec.getJvmOptions())
        );
    }

    private BrokerCapacityInfo extractBrokerCapacity(final BrokerCapacity capacity) {
        if (capacity == null) {
            return null;
        }
        return new BrokerCapacityInfo(
            capacity.getCpu(),
            capacity.getInboundNetwork(),
            capacity.getOutboundNetwork()
        );
    }

    private static Integer msToSeconds(final Long ms) {
        return ms != null ? (int) (ms / MILLISECONDS_PER_SECOND) : null;
    }

    private KafkaExporterInfo extractKafkaExporter(final KafkaExporterSpec exporter) {
        if (exporter == null) {
            return null;
        }
        return new KafkaExporterInfo(
            exporter.getTopicRegex(),
            exporter.getGroupRegex(),
            exporter.getTopicExcludeRegex(),
            exporter.getGroupExcludeRegex(),
            extractResources(exporter.getResources())
        );
    }

    private List<NodePoolConfigInfo> extractNodePoolConfigs(final String namespace, final String clusterName) {
        try {
            List<KafkaNodePool> nodePools = k8sService.queryResourcesByLabel(
                KafkaNodePool.class, namespace,
                io.strimzi.api.ResourceLabels.STRIMZI_CLUSTER_LABEL, clusterName);

            return nodePools.stream()
                .map(this::mapNodePool)
                .toList();
        } catch (Exception e) {
            LOG.warnf("Failed to gather KafkaNodePool configs: %s", e.getMessage());
            return List.of();
        }
    }

    private NodePoolConfigInfo mapNodePool(final KafkaNodePool nodePool) {
        List<String> roles = nodePool.getSpec().getRoles().stream()
            .map(role -> role.toString().toLowerCase(Locale.ROOT))
            .toList();
        String storageType = nodePool.getSpec().getStorage() != null
            ? nodePool.getSpec().getStorage().getType() : null;
        String storageSize = extractStorageSize(nodePool.getSpec().getStorage());

        return new NodePoolConfigInfo(
            nodePool.getMetadata().getName(),
            roles,
            nodePool.getSpec().getReplicas(),
            storageType,
            storageSize,
            extractResources(nodePool.getSpec().getResources()),
            extractJvmOptions(nodePool.getSpec().getJvmOptions())
        );
    }

    private String extractStorageSize(final Storage storage) {
        return switch (storage) {
            case PersistentClaimStorage pcs -> pcs.getSize();
            case JbodStorage jbod when jbod.getVolumes() != null -> jbod.getVolumes().stream()
                .filter(PersistentClaimStorage.class::isInstance)
                .map(PersistentClaimStorage.class::cast)
                .map(PersistentClaimStorage::getSize)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
            case null, default -> null;
        };
    }

    private String resolveConfigMapContent(final String namespace, final String configMapName, final String key) {
        if (configMapName == null) {
            return null;
        }
        try {
            ConfigMap cm = k8sService.getResource(ConfigMap.class, namespace, configMapName);
            if (cm != null && cm.getData() != null && cm.getData().containsKey(key)) {
                return cm.getData().get(key);
            }
        } catch (Exception e) {
            LOG.debugf("Could not resolve ConfigMap '%s' in namespace '%s': %s",
                configMapName, namespace, e.getMessage());
        }
        return null;
    }
}
