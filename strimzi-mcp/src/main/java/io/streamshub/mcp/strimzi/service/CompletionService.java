/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkiverse.mcp.server.CompleteContext;
import io.streamshub.mcp.common.service.CompletionHelper;
import io.streamshub.mcp.strimzi.config.StrimziConstants;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePool;
import io.strimzi.api.kafka.model.topic.KafkaTopic;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Strimzi-specific auto-completion service for MCP prompt and resource template parameters.
 *
 * <p>Delegates generic completions (namespace, prefix filtering) to {@link CompletionHelper}
 * and provides Strimzi-specific completions for Kafka clusters, node pools,
 * topics, and operator deployments.</p>
 */
@ApplicationScoped
public class CompletionService {

    private static final Logger LOG = Logger.getLogger(CompletionService.class);
    // Prompt arguments use descriptive names (e.g., "cluster_name")
    private static final String ARG_NAMESPACE = "namespace";
    private static final String ARG_CLUSTER_NAME = "cluster_name";

    // Resource template variables match URI template placeholders (e.g., {name})
    private static final String ARG_NAME = "name";

    @Inject
    CompletionHelper completionHelper;

    @Inject
    KubernetesClient kubernetesClient;

    @ConfigProperty(name = "mcp.completion.cache-ttl-seconds", defaultValue = "5")
    long cacheTtlSeconds;

    private final Map<String, CachedNames> nameCache = new ConcurrentHashMap<>();

    CompletionService() {
    }

    private record CachedNames(List<String> names, Instant expiry) {
        boolean isExpired() {
            return Instant.now().isAfter(expiry);
        }
    }

    /**
     * Complete prompt arguments by dispatching on the argument name from context.
     * Supports {@code namespace} and {@code cluster_name} arguments.
     *
     * @param partial the partial input value
     * @param context the completion context identifying which argument is being completed
     * @return matching values for the requested argument
     */
    public List<String> completeByArgumentName(final String partial, final CompleteContext context) {
        Map<String, String> args = context.arguments();
        if (args.containsKey(ARG_NAMESPACE)) {
            return completionHelper.completeNamespace(partial);
        }
        if (args.containsKey(ARG_CLUSTER_NAME)) {
            return completeClusterName(partial);
        }
        return List.of();
    }

    /**
     * Complete resource template arguments for Kafka cluster resources.
     * Supports {@code namespace} and {@code name} (cluster name) variables.
     *
     * @param partial the partial input value
     * @param context the completion context identifying which variable is being completed
     * @return matching values for the requested variable
     */
    public List<String> completeKafkaClusterArgs(final String partial, final CompleteContext context) {
        Map<String, String> args = context.arguments();
        if (args.containsKey(ARG_NAMESPACE)) {
            return completionHelper.completeNamespace(partial);
        }
        if (args.containsKey(ARG_NAME)) {
            return completeClusterName(partial);
        }
        return List.of();
    }

    /**
     * Complete resource template arguments for KafkaNodePool resources.
     * Supports {@code namespace} and {@code name} (node pool name) variables.
     *
     * @param partial the partial input value
     * @param context the completion context identifying which variable is being completed
     * @return matching values for the requested variable
     */
    public List<String> completeNodePoolArgs(final String partial, final CompleteContext context) {
        Map<String, String> args = context.arguments();
        if (args.containsKey(ARG_NAMESPACE)) {
            return completionHelper.completeNamespace(partial);
        }
        if (args.containsKey(ARG_NAME)) {
            return completeNodePoolName(partial);
        }
        return List.of();
    }

    /**
     * Complete resource template arguments for KafkaTopic resources.
     * Supports {@code namespace} and {@code name} (topic name) variables.
     *
     * @param partial the partial input value
     * @param context the completion context identifying which variable is being completed
     * @return matching values for the requested variable
     */
    public List<String> completeTopicArgs(final String partial, final CompleteContext context) {
        Map<String, String> args = context.arguments();
        if (args.containsKey(ARG_NAMESPACE)) {
            return completionHelper.completeNamespace(partial);
        }
        if (args.containsKey(ARG_NAME)) {
            return completeTopicName(partial);
        }
        return List.of();
    }

    /**
     * Complete resource template arguments for Strimzi operator resources.
     * Supports {@code namespace} and {@code name} (operator name) variables.
     *
     * @param partial the partial input value
     * @param context the completion context identifying which variable is being completed
     * @return matching values for the requested variable
     */
    public List<String> completeOperatorArgs(final String partial, final CompleteContext context) {
        Map<String, String> args = context.arguments();
        if (args.containsKey(ARG_NAMESPACE)) {
            return completionHelper.completeNamespace(partial);
        }
        if (args.containsKey(ARG_NAME)) {
            return completeOperatorName(partial);
        }
        return List.of();
    }

    /**
     * Complete Kafka cluster names matching a partial input.
     *
     * @param partial the partial cluster name input
     * @return list of matching cluster names
     */
    private List<String> completeClusterName(final String partial) {
        List<String> names = getCachedOrFetch("kafka", () -> {
            List<Kafka> kafkas = kubernetesClient.resources(Kafka.class)
                .inAnyNamespace()
                .list()
                .getItems();
            return kafkas.stream()
                .map(k -> k.getMetadata().getName())
                .distinct()
                .toList();
        });
        return completionHelper.filterByPrefix(names, partial);
    }

    /**
     * Complete KafkaNodePool names matching a partial input.
     *
     * @param partial the partial node pool name input
     * @return list of matching node pool names
     */
    private List<String> completeNodePoolName(final String partial) {
        List<String> names = getCachedOrFetch("nodepool", () -> {
            List<KafkaNodePool> pools = kubernetesClient.resources(KafkaNodePool.class)
                .inAnyNamespace()
                .list()
                .getItems();
            return pools.stream()
                .map(p -> p.getMetadata().getName())
                .distinct()
                .toList();
        });
        return completionHelper.filterByPrefix(names, partial);
    }

    /**
     * Complete KafkaTopic names matching a partial input.
     *
     * @param partial the partial topic name input
     * @return list of matching topic names
     */
    private List<String> completeTopicName(final String partial) {
        List<String> names = getCachedOrFetch("topic", () -> {
            List<KafkaTopic> topics = kubernetesClient.resources(KafkaTopic.class)
                .inAnyNamespace()
                .list()
                .getItems();
            return topics.stream()
                .map(t -> t.getMetadata().getName())
                .distinct()
                .toList();
        });
        return completionHelper.filterByPrefix(names, partial);
    }

    /**
     * Complete Strimzi operator deployment names matching a partial input.
     *
     * @param partial the partial operator name input
     * @return list of matching operator deployment names
     */
    private List<String> completeOperatorName(final String partial) {
        List<String> names = getCachedOrFetch("operator", () ->
            kubernetesClient.apps().deployments()
                .inAnyNamespace()
                .withLabel("app", StrimziConstants.Operator.APP_LABEL_VALUE)
                .list()
                .getItems()
                .stream()
                .map(d -> d.getMetadata().getName())
                .distinct()
                .toList()
        );
        return completionHelper.filterByPrefix(names, partial);
    }

    private List<String> getCachedOrFetch(final String cacheKey,
                                           final java.util.function.Supplier<List<String>> fetcher) {
        CachedNames cached = nameCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.names();
        }
        try {
            List<String> names = fetcher.get();
            nameCache.put(cacheKey, new CachedNames(names,
                Instant.now().plusSeconds(cacheTtlSeconds)));
            return names;
        } catch (Exception e) {
            LOG.debugf("Error fetching %s names for completion: %s", cacheKey, e.getMessage());
            return List.of();
        }
    }
}
