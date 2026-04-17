/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.prompt;

import io.quarkiverse.mcp.server.CompleteContext;
import io.quarkiverse.mcp.server.CompletePrompt;
import io.streamshub.mcp.strimzi.service.CompletionService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.List;

/**
 * Auto-completions for MCP prompt template parameters.
 *
 * <p>Provides namespace and cluster name completions for all
 * prompt templates by querying the Kubernetes API.</p>
 */
@Singleton
public class PromptCompletions {

    @Inject
    CompletionService completionService;

    PromptCompletions() {
    }

    /**
     * Complete arguments for the diagnose-cluster-issue prompt.
     *
     * <p>Supports completions for {@code namespace} and {@code cluster_name} arguments.
     * The argument being completed is identified via the MCP completion request context.</p>
     *
     * @param partial the partial input value
     * @param context the completion context with argument metadata
     * @return matching values for the requested argument
     */
    @CompletePrompt("diagnose-cluster-issue")
    public List<String> completeDiagnoseArgs(final String partial, final CompleteContext context) {
        return completionService.completeByArgumentName(partial, context);
    }

    /**
     * Complete arguments for the troubleshoot-connectivity prompt.
     *
     * <p>Supports completions for {@code namespace} and {@code cluster_name} arguments.
     * The argument being completed is identified via the MCP completion request context.</p>
     *
     * @param partial the partial input value
     * @param context the completion context with argument metadata
     * @return matching values for the requested argument
     */
    @CompletePrompt("troubleshoot-connectivity")
    public List<String> completeTroubleshootArgs(final String partial, final CompleteContext context) {
        return completionService.completeByArgumentName(partial, context);
    }

    /**
     * Complete arguments for the analyze-kafka-metrics prompt.
     *
     * <p>Supports completions for {@code namespace} and {@code cluster_name} arguments.
     * The argument being completed is identified via the MCP completion request context.</p>
     *
     * @param partial the partial input value
     * @param context the completion context with argument metadata
     * @return matching values for the requested argument
     */
    @CompletePrompt("analyze-kafka-metrics")
    public List<String> completeAnalyzeKafkaMetricsArgs(final String partial, final CompleteContext context) {
        return completionService.completeByArgumentName(partial, context);
    }

    /**
     * Complete arguments for the analyze-strimzi-operator-metrics prompt.
     *
     * <p>Supports completions for the {@code namespace} argument.
     * The argument being completed is identified via the MCP completion request context.</p>
     *
     * @param partial the partial input value
     * @param context the completion context with argument metadata
     * @return matching values for the requested argument
     */
    @CompletePrompt("analyze-strimzi-operator-metrics")
    public List<String> completeAnalyzeOperatorMetricsArgs(final String partial, final CompleteContext context) {
        return completionService.completeByArgumentName(partial, context);
    }
}
