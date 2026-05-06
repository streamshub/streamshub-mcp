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
     * Complete arguments for the troubleshoot-connector prompt.
     *
     * <p>Supports completions for {@code namespace}, {@code connector_name},
     * and {@code connect_cluster} arguments.</p>
     *
     * @param partial the partial input value
     * @param context the completion context with argument metadata
     * @return matching values for the requested argument
     */
    @CompletePrompt("troubleshoot-connector")
    public List<String> completeTroubleshootConnectorArgs(final String partial, final CompleteContext context) {
        return completionService.completeByArgumentName(partial, context);
    }

    /**
     * Complete arguments for the troubleshoot-bridge prompt.
     *
     * <p>Supports completions for {@code namespace} and {@code bridge_name}
     * arguments.</p>
     *
     * @param partial the partial input value
     * @param context the completion context with argument metadata
     * @return matching values for the requested argument
     */
    @CompletePrompt("troubleshoot-bridge")
    public List<String> completeTroubleshootBridgeArgs(final String partial, final CompleteContext context) {
        return completionService.completeByArgumentName(partial, context);
    }

    /**
     * Complete arguments for the audit-security prompt.
     *
     * <p>Supports completions for {@code namespace} and {@code cluster_name} arguments.
     * The argument being completed is identified via the MCP completion request context.</p>
     *
     * @param partial the partial input value
     * @param context the completion context with argument metadata
     * @return matching values for the requested argument
     */
    @CompletePrompt("audit-security")
    public List<String> completeAuditSecurityArgs(final String partial, final CompleteContext context) {
        return completionService.completeByArgumentName(partial, context);
    }

    /**
     * Complete arguments for the compare-cluster-configs prompt.
     *
     * <p>Supports completions for {@code namespace_1}, {@code namespace_2},
     * {@code cluster_name_1}, and {@code cluster_name_2} arguments.
     * The argument being completed is identified via the MCP completion request context.</p>
     *
     * @param partial the partial input value
     * @param context the completion context with argument metadata
     * @return matching values for the requested argument
     */
    @CompletePrompt("compare-cluster-configs")
    public List<String> completeCompareConfigsArgs(final String partial, final CompleteContext context) {
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

    /**
     * Complete arguments for the troubleshoot-topic prompt.
     *
     * <p>Supports completions for {@code namespace}, {@code cluster_name},
     * and {@code topic_name} arguments.</p>
     *
     * @param partial the partial input value
     * @param context the completion context with argument metadata
     * @return matching values for the requested argument
     */
    @CompletePrompt("troubleshoot-topic")
    public List<String> completeTroubleshootTopicArgs(final String partial, final CompleteContext context) {
        return completionService.completeByArgumentName(partial, context);
    }

    /**
     * Complete arguments for the analyze-capacity prompt.
     *
     * <p>Supports completions for {@code namespace} and {@code cluster_name} arguments.
     * The argument being completed is identified via the MCP completion request context.</p>
     *
     * @param partial the partial input value
     * @param context the completion context with argument metadata
     * @return matching values for the requested argument
     */
    @CompletePrompt("analyze-capacity")
    public List<String> completeAnalyzeCapacityArgs(final String partial, final CompleteContext context) {
        return completionService.completeByArgumentName(partial, context);
    }

    /**
     * Complete arguments for the assess-upgrade-readiness prompt.
     *
     * <p>Supports completions for {@code namespace} and {@code cluster_name} arguments.
     * The argument being completed is identified via the MCP completion request context.</p>
     *
     * @param partial the partial input value
     * @param context the completion context with argument metadata
     * @return matching values for the requested argument
     */
    @CompletePrompt("assess-upgrade-readiness")
    public List<String> completeAssessUpgradeArgs(final String partial, final CompleteContext context) {
        return completionService.completeByArgumentName(partial, context);
    }
}
