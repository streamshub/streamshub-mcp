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
 * MCP prompt template for troubleshooting KafkaConnect cluster issues.
 *
 * <p>Guides the LLM through checking cluster status, connector inventory,
 * pod health, logs, and events to distinguish platform issues from
 * individual connector configuration problems.</p>
 */
@Singleton
public class TroubleshootConnectPrompt {

    TroubleshootConnectPrompt() {
    }

    /**
     * Generate a KafkaConnect cluster troubleshooting prompt.
     *
     * @param connectCluster the KafkaConnect cluster name
     * @param namespace      optional Kubernetes namespace
     * @param symptom        optional observed symptom
     * @return prompt response with troubleshooting instructions
     */
    @Prompt(
        name = "troubleshoot-connect",
        description = "Step-by-step troubleshooting of a KafkaConnect cluster issue."
            + " Guides through cluster status, connector inventory, pod inspection,"
            + " and log analysis for platform-level problems."
    )
    public PromptResponse troubleshootConnect(
        @PromptArg(
            name = "connect_cluster",
            description = "Name of the KafkaConnect cluster to troubleshoot."
        ) final String connectCluster,
        @PromptArg(
            name = "namespace",
            description = "Kubernetes namespace where the Connect cluster is deployed.",
            required = false
        ) final String namespace,
        @PromptArg(
            name = "symptom",
            description = "Observed symptom, e.g. 'pods crashing', 'workers rebalancing',"
                + " or 'all connectors failing'.",
            required = false
        ) final String symptom
    ) {
        String nsClause = namespace != null && !namespace.isBlank()
            ? " in namespace `" + namespace + "`"
            : "";
        String nsArg = namespace != null && !namespace.isBlank()
            ? ", namespace='" + namespace + "'"
            : "";
        String symptomClause = symptom != null && !symptom.isBlank()
            ? " The reported symptom is: " + symptom + "."
            : "";

        String instructions = """
            You are troubleshooting KafkaConnect cluster `%s`%s.%s

            This is a **cluster-level** investigation focusing on the Connect platform, \
            not individual connector configuration. For individual connector issues, \
            use the `troubleshoot-connector` prompt instead.

            Follow these steps in order. After each step, analyze the results \
            before proceeding to the next.

            %s

            ## Step 1: Check Connect cluster status
            Use `get_kafka_connect(connect_cluster='%s'%s)` to retrieve the cluster status.
            Check for:
            - Overall readiness (Ready/NotReady/Error)
            - Replicas: are all expected replicas ready?
            - REST API URL: is the Connect REST API accessible?
            - Available connector plugins: is the expected connector class listed?
            - Conditions: any warning or error conditions?

            ## Step 2: Check connector inventory
            Use `list_kafka_connectors(%s)` to get all connectors on this cluster.
            Check for:
            - How many connectors total?
            - How many are NotReady or in error state?
            - **Scope detection**: if ALL connectors are failing, this is a cluster \
            platform issue. If only some are failing, it may be connector-specific.

            ## Step 3: Check Connect pods
            Use `get_kafka_connect_pods(connect_cluster='%s'%s)`.
            Look for:
            - Pods not in Running phase
            - High restart counts (may indicate OOM kills or crashes)
            - Pods that are not ready
            - Resource pressure (if pod descriptions show resource limits being hit)

            ## Step 4: Check Connect logs
            Use `get_kafka_connect_logs(connect_cluster='%s'%s, filter='errors')`.
            Look for: `WorkerRebalance`, `ConnectRestException`, \
            `OutOfMemoryError`, `ClassNotFoundException`, \
            `NoClassDefFoundError`, `ConnectException`, \
            `GroupCoordinatorNotAvailableException`, `DisconnectException`, \
            `SaslAuthenticationException`.

            ## Step 5: Check Kubernetes events
            Use `get_strimzi_events(%s)`.
            Look for: reconciliation failures, scheduling failures, \
            resource quota issues, eviction events, image pull errors.

            ## Step 6: Correlate and diagnose
            Distinguish between:
            - **Deployment/operator issues**: reconciliation failures, image pull errors, \
            invalid CR configuration — check operator logs and events
            - **Resource issues**: OOM kills, CPU throttling, insufficient replicas — \
            check pod restart counts and resource limits
            - **Plugin issues**: missing connector classes, version incompatibility, \
            classpath conflicts — check available plugins list and ClassNotFoundException
            - **Worker rebalancing storms**: frequent task reassignment, group coordinator \
            issues — check logs for WorkerRebalance and GroupCoordinator errors
            - **Kafka connectivity**: bootstrap server unreachable, authentication \
            failures, TLS misconfiguration — check logs for connection errors

            Provide:
            - Root cause diagnosis
            - Severity assessment (CRITICAL if all connectors affected)
            - Specific remediation steps\
            """.formatted(connectCluster, nsClause, symptomClause,
                StrimziToolsPrompts.ERROR_HANDLING_INSTRUCTION,
                connectCluster, nsArg,
                nsArg.isEmpty() ? "" : "namespace='" + namespace + "'",
                connectCluster, nsArg,
                connectCluster, nsArg,
                nsArg.isEmpty() ? "" : "namespace='" + namespace + "'");

        return PromptResponse.withMessages(List.of(
            PromptMessage.withUserRole(instructions)
        ));
    }
}
