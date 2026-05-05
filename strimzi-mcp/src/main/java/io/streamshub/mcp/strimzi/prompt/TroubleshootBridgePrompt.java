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
 * MCP prompt template for troubleshooting KafkaBridge issues.
 *
 * <p>Guides the LLM through checking bridge status, HTTP configuration,
 * pod health, log analysis, and Kubernetes events to identify root causes
 * of bridge connectivity and configuration issues.</p>
 */
@Singleton
public class TroubleshootBridgePrompt {

    TroubleshootBridgePrompt() {
    }

    /**
     * Generate a bridge troubleshooting prompt.
     *
     * @param bridgeName the KafkaBridge name
     * @param namespace  optional Kubernetes namespace
     * @param symptom    optional observed symptom
     * @return prompt response with troubleshooting instructions
     */
    @Prompt(
        name = "troubleshoot-bridge",
        description = "Step-by-step troubleshooting of a KafkaBridge issue."
            + " Guides through bridge status, HTTP configuration,"
            + " pod inspection, and log analysis."
    )
    public PromptResponse troubleshootBridge(
        @PromptArg(
            name = "bridge_name",
            description = "Name of the KafkaBridge to troubleshoot."
        ) final String bridgeName,
        @PromptArg(
            name = "namespace",
            description = "Kubernetes namespace where the bridge is deployed.",
            required = false
        ) final String namespace,
        @PromptArg(
            name = "symptom",
            description = "Observed symptom, e.g. 'bridge not ready' or 'HTTP 503'.",
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
            You are troubleshooting KafkaBridge `%s`%s.%s

            Follow these steps in order. After each step, analyze the results \
            before proceeding to the next.

            %s

            ## Step 1: Check bridge status and configuration
            Use `get_kafka_bridge` to retrieve the bridge's current state, \
            readiness conditions, and full configuration.
            Check for:
            - Ready condition status (True/False) and any error messages
            - HTTP URL and port configuration
            - CORS allowed origins and methods
            - Authentication type (TLS, SCRAM-SHA, OAuth)
            - TLS configuration for Kafka connectivity
            - Bootstrap servers configuration
            - Producer and consumer configuration settings
            - Expected vs actual replica counts

            ## Step 2: Check bridge pods
            Use `get_kafka_bridge_pods` to inspect the bridge pods.
            Look for:
            - Pods not in Running phase
            - High restart counts (may indicate OOM or crashes)
            - Pods that are not ready
            - Expected vs actual replica counts

            ## Step 3: Check bridge logs
            Use `get_kafka_bridge_logs` with filter 'errors' to find errors \
            in bridge pod logs.
            Look for:
            - Connection refused or timeout errors (Kafka connectivity)
            - Authentication or authorization failures
            - TLS handshake errors or certificate issues
            - HTTP binding or port conflict errors
            - `OutOfMemoryError` or resource exhaustion
            - CORS-related rejections in HTTP requests
            - Producer or consumer configuration errors
            - Serialization or deserialization failures

            ## Step 4: Check Kubernetes events
            Use `get_strimzi_events` to look for Kubernetes events related to \
            the bridge resources.
            Look for: Warning events, reconciliation failures, resource quota \
            issues, pod scheduling failures.

            ## Step 5: Correlate and diagnose
            Distinguish between:
            - **Bridge configuration errors**: wrong bootstrap servers, invalid \
            HTTP config, CORS misconfiguration, invalid producer/consumer \
            settings — fix the KafkaBridge CR spec
            - **Kafka connectivity issues**: authentication failures, TLS \
            certificate problems, bootstrap server unreachable — check auth \
            secrets, TLS certs, and network connectivity
            - **Pod/resource issues**: OOM kills, insufficient resources, pod \
            crashes — fix resource limits (memory, CPU) in KafkaBridge CR
            - **HTTP/CORS issues**: HTTP endpoint unreachable, CORS rejections \
            — check HTTP configuration, CORS origins and methods in the \
            KafkaBridge CR
            - **Strimzi operator issues**: reconciliation failures, resource \
            not being managed — check operator logs and RBAC permissions

            Provide:
            - Root cause diagnosis
            - Severity assessment
            - Specific remediation steps\
            """.formatted(bridgeName, nsClause, symptomClause,
                StrimziToolsPrompts.ERROR_HANDLING_INSTRUCTION);

        return PromptResponse.withMessages(List.of(
            PromptMessage.withUserRole(instructions)
        ));
    }
}
