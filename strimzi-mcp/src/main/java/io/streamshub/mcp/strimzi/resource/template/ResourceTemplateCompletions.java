/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.resource.template;

import io.quarkiverse.mcp.server.CompleteContext;
import io.quarkiverse.mcp.server.CompleteResourceTemplate;
import io.streamshub.mcp.strimzi.service.CompletionService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.List;

/**
 * Auto-completions for MCP resource template URI variables.
 *
 * <p>Provides namespace and resource name completions for all five
 * resource templates by querying the Kubernetes API.</p>
 */
@Singleton
public class ResourceTemplateCompletions {

    @Inject
    CompletionService completionService;

    ResourceTemplateCompletions() {
    }

    /**
     * Complete variables for the kafka-cluster-status resource template.
     *
     * @param partial the partial input value
     * @param context the completion context with argument metadata
     * @return matching values for the requested variable
     */
    @CompleteResourceTemplate("kafka-cluster-status")
    public List<String> completeKafkaStatus(final String partial, final CompleteContext context) {
        return completionService.completeKafkaClusterArgs(partial, context);
    }

    /**
     * Complete variables for the kafka-cluster-topology resource template.
     *
     * @param partial the partial input value
     * @param context the completion context with argument metadata
     * @return matching values for the requested variable
     */
    @CompleteResourceTemplate("kafka-cluster-topology")
    public List<String> completeKafkaTopology(final String partial, final CompleteContext context) {
        return completionService.completeKafkaClusterArgs(partial, context);
    }

    /**
     * Complete variables for the kafka-nodepool-status resource template.
     *
     * @param partial the partial input value
     * @param context the completion context with argument metadata
     * @return matching values for the requested variable
     */
    @CompleteResourceTemplate("kafka-nodepool-status")
    public List<String> completeNodePoolStatus(final String partial, final CompleteContext context) {
        return completionService.completeNodePoolArgs(partial, context);
    }

    /**
     * Complete variables for the kafka-topic-status resource template.
     *
     * @param partial the partial input value
     * @param context the completion context with argument metadata
     * @return matching values for the requested variable
     */
    @CompleteResourceTemplate("kafka-topic-status")
    public List<String> completeTopicStatus(final String partial, final CompleteContext context) {
        return completionService.completeTopicArgs(partial, context);
    }

    /**
     * Complete variables for the kafka-user-status resource template.
     *
     * @param partial the partial input value
     * @param context the completion context with argument metadata
     * @return matching values for the requested variable
     */
    @CompleteResourceTemplate("kafka-user-status")
    public List<String> completeUserStatus(final String partial, final CompleteContext context) {
        return completionService.completeUserArgs(partial, context);
    }

    /**
     * Complete variables for the strimzi-operator-status resource template.
     *
     * @param partial the partial input value
     * @param context the completion context with argument metadata
     * @return matching values for the requested variable
     */
    @CompleteResourceTemplate("strimzi-operator-status")
    public List<String> completeOperatorStatus(final String partial, final CompleteContext context) {
        return completionService.completeOperatorArgs(partial, context);
    }
}
