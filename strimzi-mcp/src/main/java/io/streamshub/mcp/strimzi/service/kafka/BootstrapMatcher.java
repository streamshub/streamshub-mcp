/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafka;

import io.streamshub.mcp.strimzi.dto.kafka.KafkaClusterResponse;
import io.streamshub.mcp.strimzi.dto.kafka.ListenerInfo;
import io.streamshub.mcp.strimzi.dto.kafkamirrormaker2.KafkaMirrorMaker2Response;

import java.util.HashSet;
import java.util.Set;
/**
 * Shared logic for matching Strimzi resources to a Kafka cluster
 * by comparing bootstrap server addresses.
 */
public final class BootstrapMatcher {

    private BootstrapMatcher() {
    }

    /**
     * Extract all possible bootstrap addresses for a cluster:
     * the conventional service DNS name plus every listener's bootstrap address and host.
     *
     * @param cluster     the cluster response
     * @param clusterName the cluster name (used to build the DNS name)
     * @return the set of bootstrap addresses
     */
    public static Set<String> extractBootstrapAddresses(final KafkaClusterResponse cluster,
                                                         final String clusterName) {
        Set<String> addresses = new HashSet<>();
        addresses.add(clusterName + "-kafka-bootstrap");

        if (cluster.listeners() != null) {
            for (ListenerInfo listener : cluster.listeners()) {
                if (listener.bootstrapAddress() != null) {
                    addresses.add(listener.bootstrapAddress());
                    String host = listener.bootstrapAddress().split(":")[0];
                    addresses.add(host);
                }
            }
        }
        return addresses;
    }

    /**
     * Check whether a resource's {@code spec.bootstrapServers} string
     * contains any of the cluster's bootstrap addresses.
     *
     * @param specBootstrapServers the bootstrap servers from the resource spec
     * @param clusterAddresses     the cluster's known bootstrap addresses
     * @return true if there is a match
     */
    public static boolean matchesBootstrap(final String specBootstrapServers,
                                            final Set<String> clusterAddresses) {
        if (specBootstrapServers == null || specBootstrapServers.isBlank()) {
            return false;
        }
        for (String address : clusterAddresses) {
            if (specBootstrapServers.contains(address)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check whether a KafkaMirrorMaker2 connects to a cluster,
     * matching by bootstrap servers, target cluster name, or source cluster aliases.
     *
     * @param mm2              the MM2 response
     * @param clusterAddresses the cluster's known bootstrap addresses
     * @return true if the MM2 connects to the cluster
     */
    public static boolean mm2ConnectsToCluster(final KafkaMirrorMaker2Response mm2,
                                                final Set<String> clusterAddresses) {
        if (matchesBootstrap(mm2.bootstrapServers(), clusterAddresses)) {
            return true;
        }
        if (mm2.targetCluster() != null
                && anyAddressContains(clusterAddresses, mm2.targetCluster())) {
            return true;
        }
        if (mm2.sourceClusterAliases() != null) {
            return mm2.sourceClusterAliases().stream()
                .anyMatch(alias -> anyAddressContains(clusterAddresses, alias));
        }
        return false;
    }

    private static boolean anyAddressContains(final Set<String> addresses, final String value) {
        return addresses.stream().anyMatch(addr -> addr.contains(value));
    }
}
