/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.prompt;

import io.quarkiverse.mcp.server.Prompt;
import io.quarkiverse.mcp.server.PromptArg;
import io.quarkiverse.mcp.server.PromptMessage;
import io.quarkiverse.mcp.server.PromptResponse;
import io.streamshub.mcp.strimzi.config.StrimziToolsPrompts;
import jakarta.inject.Singleton;

import java.util.List;

/**
 * MCP prompt template for troubleshooting KafkaConnector issues.
 *
 * <p>Guides the LLM through checking connector status, parent Connect
 * cluster health, pod inspection, and log analysis to distinguish
 * connector configuration errors from Connect platform issues.</p>
 */
@Singleton
public class TroubleshootConnectorPrompt {

    TroubleshootConnectorPrompt() {
    }

    /**
     * Generate a connector troubleshooting prompt.
     *
     * @param connectorName the KafkaConnector name
     * @param namespace     optional Kubernetes namespace
     * @param connectCluster optional parent KafkaConnect cluster name
     * @param symptom       optional observed symptom
     * @return prompt response with troubleshooting instructions
     */
    @Prompt(
        name = "troubleshoot-connector",
        description = "Step-by-step troubleshooting of a KafkaConnector issue."
            + " Guides through connector status, Connect cluster health,"
            + " pod inspection, and log analysis."
    )
    public PromptResponse troubleshootConnector(
        @PromptArg(
            name = "connector_name",
            description = "Name of the KafkaConnector to troubleshoot."
        ) final String connectorName,
        @PromptArg(
            name = "namespace",
            description = "Kubernetes namespace where the connector is deployed.",
            required = false
        ) final String namespace,
        @PromptArg(
            name = "connect_cluster",
            description = "Parent KafkaConnect cluster name. Omit to auto-discover from connector labels.",
            required = false
        ) final String connectCluster,
        @PromptArg(
            name = "symptom",
            description = "Observed symptom, e.g. 'connector FAILED' or 'tasks restarting'.",
            required = false
        ) final String symptom
    ) {
        String nsClause = namespace != null && !namespace.isBlank()
            ? " in namespace `" + namespace + "`"
            : "";
        String symptomClause = symptom != null && !symptom.isBlank()
            ? " The reported symptom is: " + symptom + "."
            : "";

        String instructions = """
            You are troubleshooting KafkaConnector `%s`%s.%s

            Follow these steps in order. After each step, analyze the results \
            before proceeding to the next.

            %s

            ## Step 1: Check connector status
            Use `get_kafka_connector` to retrieve the connector's current state, \
            conditions, task configuration, and connector REST API status.
            Check for:
            - Connector state (running, paused, stopped)
            - Ready condition status (True/False) and any error messages
            - Task failures in the `connector_status` field
            - Auto-restart count (frequent restarts indicate recurring failures)
            Note the `connect_cluster` field — this is the parent KafkaConnect cluster.

            ## Step 2: Check parent KafkaConnect cluster
            Use `get_kafka_connect` with the connect cluster name from Step 1 \
            (or `%s` if provided) to verify the Connect cluster is healthy.
            Check for:
            - Cluster readiness (Ready/NotReady/Error)
            - Replicas: are all expected replicas ready?
            - REST API URL availability
            - Available connector plugins: does the deployed connector class exist?

            ## Step 3: Check Connect pods
            Use `get_kafka_connect_pods` to inspect the Connect worker pods.
            Look for:
            - Pods not in Running phase
            - High restart counts (may indicate OOM or crashes)
            - Pods that are not ready

            ## Step 4: Check Connect logs
            Use `get_kafka_connect_logs` with the connector class name as a keyword \
            to find errors related to this connector.
            Look for: `ConnectException`, `RetriableException`, serialization errors, \
            schema registry failures, authentication errors, target system timeouts, \
            `TaskAssignmentException`, `ConfigException`.

            ## Step 5: Check events
            Use `get_strimzi_events` to look for Kubernetes events related to the \
            Connect cluster and connector resources.
            Look for: Warning events, reconciliation failures, resource quota issues.

            ## Step 6: Correlate and diagnose
            Distinguish between:
            - **Connector configuration errors**: wrong class, missing required config, \
            invalid topic names, schema issues — fix the KafkaConnector CR spec
            - **Connect platform issues**: OOM kills, insufficient resources, pod crashes — \
            fix the KafkaConnect CR (resources, replicas, JVM options)
            - **External system issues**: target database down, network unreachable — \
            check connectivity to external systems
            - **Rebalancing**: connector tasks being reassigned frequently — \
            check Connect worker stability and group coordination

            Provide:
            - Root cause diagnosis
            - Severity assessment
            - Specific remediation steps\
            """.formatted(connectorName, nsClause, symptomClause,
                StrimziToolsPrompts.ERROR_HANDLING_INSTRUCTION,
                connectCluster != null ? connectCluster : "the value from the connector's connect_cluster field");

        return PromptResponse.withMessages(List.of(
            PromptMessage.withUserRole(instructions)
        ));
    }
}
