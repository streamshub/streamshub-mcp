/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.util;

import io.streamshub.mcp.common.service.KubernetesQueryException;
import io.streamshub.mcp.common.service.KubernetesResourceService;
import io.streamshub.mcp.common.util.McpErrors;
import io.strimzi.api.kafka.model.kafka.Kafka;
import org.jboss.logging.Logger;

/**
 * Checks on the Kafka cluster that a request names, for services that list a cluster's child resources.
 */
public final class KafkaClusterChecks {

    private static final Logger LOG = Logger.getLogger(KafkaClusterChecks.class);

    private KafkaClusterChecks() {
    }

    /**
     * Throw RESOURCE_NOT_FOUND when no Kafka cluster has the given name, so that a list filtered by an
     * unknown cluster name is not mistaken for a cluster without children. Call it only when that list
     * is empty, so the normal path costs no extra API call.
     * <p>
     * The check is best-effort: when the Kafka resources cannot be read (for example, RBAC forbids it),
     * it returns and the caller keeps its empty result. Without a namespace, a name found in several
     * namespaces counts as existing (no ambiguity error).
     *
     * @param k8sService  the Kubernetes resource service
     * @param namespace   the namespace searched, or null for all namespaces
     * @param clusterName the Kafka cluster name
     * @throws io.quarkiverse.mcp.server.McpException RESOURCE_NOT_FOUND when no Kafka cluster has that name
     */
    public static void requireClusterExists(final KubernetesResourceService k8sService, final String namespace,
                                            final String clusterName) {
        requireClusterExists(k8sService, namespace, clusterName, null);
    }

    /**
     * Same as {@link #requireClusterExists(KubernetesResourceService, String, String)}, with a hint
     * appended to the not-found message.
     *
     * @param k8sService  the Kubernetes resource service
     * @param namespace   the namespace searched, or null for all namespaces
     * @param clusterName the Kafka cluster name
     * @param hint        a sentence appended to the not-found message, or null for none
     * @throws io.quarkiverse.mcp.server.McpException RESOURCE_NOT_FOUND when no Kafka cluster has that name
     */
    public static void requireClusterExists(final KubernetesResourceService k8sService, final String namespace,
                                            final String clusterName, final String hint) {
        boolean exists;
        try {
            exists = k8sService.resourceExists(Kafka.class, namespace, clusterName);
        } catch (KubernetesQueryException e) {
            LOG.debugf("Could not check whether Kafka cluster %s exists (namespace=%s): %s",
                clusterName, namespace != null ? namespace : "all", e.getMessage());
            return;
        }
        if (!exists) {
            throw McpErrors.notFound("Kafka cluster", clusterName, namespace, hint);
        }
    }
}
