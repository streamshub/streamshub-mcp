/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkiverse.mcp.server.CompleteContext;
import io.streamshub.mcp.common.service.CompletionCache;
import io.streamshub.mcp.common.service.CompletionHelper;
import io.streamshub.mcp.strimzi.config.StrimziConstants;
import io.strimzi.api.kafka.model.connect.KafkaConnect;
import io.strimzi.api.kafka.model.connector.KafkaConnector;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePool;
import io.strimzi.api.kafka.model.topic.KafkaTopic;
import io.strimzi.api.kafka.model.user.KafkaUser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * Strimzi-specific auto-completion service for MCP prompt and resource template parameters.
 *
 * <p>Delegates generic completions (namespace, prefix filtering) to {@link CompletionHelper}
 * and provides Strimzi-specific completions for Kafka clusters, node pools,
 * topics, and operator deployments.</p>
 */
@ApplicationScoped
public class CompletionService {

    // Prompt arguments use descriptive names (e.g., "cluster_name")
    private static final String ARG_NAMESPACE = "namespace";
    private static final String ARG_CLUSTER_NAME = "cluster_name";
    private static final String ARG_CONNECTOR_NAME = "connector_name";
    private static final String ARG_CONNECT_CLUSTER = "connect_cluster";
    private static final String ARG_USER_NAME = "user_name";

    // Resource template variables match URI template placeholders (e.g., {name})
    private static final String ARG_NAME = "name";

    @Inject
    CompletionHelper completionHelper;

    @Inject
    CompletionCache completionCache;

    @Inject
    KubernetesClient kubernetesClient;

    CompletionService() {
    }

    /**
     * Complete prompt arguments by dispatching on the argument name from context.
     * Supports {@code namespace} and {@code cluster_name} arguments, including
     * numbered variants like {@code namespace_1} and {@code cluster_name_2}.
     *
     * @param partial the partial input value
     * @param context the completion context identifying which argument is being completed
     * @return matching values for the requested argument
     */
    public List<String> completeByArgumentName(final String partial, final CompleteContext context) {
        Map<String, String> args = context.arguments();
        for (String key : args.keySet()) {
            if (key.startsWith(ARG_NAMESPACE)) {
                return completionHelper.completeNamespace(partial);
            }
            if (key.startsWith(ARG_CLUSTER_NAME)) {
                return completeClusterName(partial);
            }
        }
        if (args.containsKey(ARG_CONNECTOR_NAME)) {
            return completeConnectorName(partial);
        }
        if (args.containsKey(ARG_CONNECT_CLUSTER)) {
            return completeConnectClusterName(partial);
        }
        if (args.containsKey(ARG_USER_NAME)) {
            return completeUserName(partial);
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

    private List<String> completeClusterName(final String partial) {
        List<String> names = completionCache.getOrFetch("kafka", () ->
            kubernetesClient.resources(Kafka.class)
                .inAnyNamespace()
                .list()
                .getItems()
                .stream()
                .map(k -> k.getMetadata().getName())
                .distinct()
                .toList()
        );
        return completionHelper.filterByPrefix(names, partial);
    }

    private List<String> completeNodePoolName(final String partial) {
        List<String> names = completionCache.getOrFetch("nodepool", () ->
            kubernetesClient.resources(KafkaNodePool.class)
                .inAnyNamespace()
                .list()
                .getItems()
                .stream()
                .map(p -> p.getMetadata().getName())
                .distinct()
                .toList()
        );
        return completionHelper.filterByPrefix(names, partial);
    }

    private List<String> completeTopicName(final String partial) {
        List<String> names = completionCache.getOrFetch("topic", () ->
            kubernetesClient.resources(KafkaTopic.class)
                .inAnyNamespace()
                .list()
                .getItems()
                .stream()
                .map(t -> t.getMetadata().getName())
                .distinct()
                .toList()
        );
        return completionHelper.filterByPrefix(names, partial);
    }

    private List<String> completeConnectClusterName(final String partial) {
        List<String> names = completionCache.getOrFetch("kafkaconnect", () ->
            kubernetesClient.resources(KafkaConnect.class)
                .inAnyNamespace()
                .list()
                .getItems()
                .stream()
                .map(c -> c.getMetadata().getName())
                .distinct()
                .toList()
        );
        return completionHelper.filterByPrefix(names, partial);
    }

    private List<String> completeConnectorName(final String partial) {
        List<String> names = completionCache.getOrFetch("kafkaconnector", () ->
            kubernetesClient.resources(KafkaConnector.class)
                .inAnyNamespace()
                .list()
                .getItems()
                .stream()
                .map(c -> c.getMetadata().getName())
                .distinct()
                .toList()
        );
        return completionHelper.filterByPrefix(names, partial);
    }

    /**
     * Complete resource template arguments for KafkaUser resources.
     * Supports {@code namespace} and {@code name} (user name) variables.
     *
     * @param partial the partial input value
     * @param context the completion context identifying which variable is being completed
     * @return matching values for the requested variable
     */
    public List<String> completeUserArgs(final String partial, final CompleteContext context) {
        Map<String, String> args = context.arguments();
        if (args.containsKey(ARG_NAMESPACE)) {
            return completionHelper.completeNamespace(partial);
        }
        if (args.containsKey(ARG_NAME)) {
            return completeUserName(partial);
        }
        return List.of();
    }

    private List<String> completeUserName(final String partial) {
        List<String> names = completionCache.getOrFetch("kafkauser", () ->
            kubernetesClient.resources(KafkaUser.class)
                .inAnyNamespace()
                .list()
                .getItems()
                .stream()
                .map(u -> u.getMetadata().getName())
                .distinct()
                .toList()
        );
        return completionHelper.filterByPrefix(names, partial);
    }

    private List<String> completeOperatorName(final String partial) {
        List<String> names = completionCache.getOrFetch("operator", () ->
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
}
