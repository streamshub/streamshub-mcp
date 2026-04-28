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
 * MCP prompt template for troubleshooting Kafka cluster connectivity.
 *
 * <p>Guides the LLM through checking listener configuration,
 * bootstrap addresses, TLS certificates, and authentication settings.</p>
 */
@Singleton
public class TroubleshootConnectivityPrompt {

    TroubleshootConnectivityPrompt() {
    }

    /**
     * Generate a connectivity troubleshooting prompt for a Kafka cluster.
     *
     * @param clusterName  the name of the Kafka cluster
     * @param namespace    the Kubernetes namespace of the cluster
     * @param listenerName optional specific listener to investigate
     * @return prompt response with troubleshooting instructions
     */
    @Prompt(
        name = "troubleshoot-connectivity",
        description = "Step-by-step troubleshooting of Kafka cluster connectivity."
            + " Checks listeners, bootstrap addresses, and TLS configuration."
    )
    public PromptResponse troubleshootConnectivity(
        @PromptArg(
            name = "cluster_name",
            description = "Name of the Kafka cluster to troubleshoot."
        ) final String clusterName,
        @PromptArg(
            name = "namespace",
            description = "Kubernetes namespace where the Kafka cluster is deployed.",
            required = false
        ) final String namespace,
        @PromptArg(
            name = "listener_name",
            description = "Specific listener name to investigate, e.g. 'plain', 'tls', 'external'.",
            required = false
        ) final String listenerName
    ) {
        String nsClause = namespace != null && !namespace.isBlank()
            ? " in namespace `" + namespace + "`"
            : "";
        String listenerClause = listenerName != null && !listenerName.isBlank()
            ? " Focus on the `" + listenerName + "` listener."
            : "";

        String instructions = """
            You are troubleshooting connectivity to Kafka cluster `%s`%s.%s

            Follow these steps in order. After each step, analyze the results \
            before proceeding to the next.

            %s

            ## Step 1: Check cluster status
            Use `get_kafka_cluster` to verify the cluster exists and is Ready.
            If the cluster is not Ready, connectivity issues may be a symptom \
            of a deeper problem. Use the `diagnose-cluster-issue` prompt instead.

            ## Step 2: Get bootstrap server addresses
            Use `get_kafka_bootstrap_servers` to retrieve all listener configurations.
            For each listener, note:
            - Listener name and type (internal, route, loadbalancer, nodeport, ingress)
            - Bootstrap address and port
            - Whether TLS is enabled

            ## Step 3: Verify listener accessibility
            Based on the listener type, explain how the client should connect:
            - **internal**: accessible only within the Kubernetes cluster \
            via `<cluster>-kafka-bootstrap.<namespace>.svc:<port>`
            - **route** (OpenShift): accessible externally via the route hostname on port 443
            - **loadbalancer**: accessible externally via the load balancer IP/hostname
            - **nodeport**: accessible via any cluster node IP on the assigned node port
            - **ingress**: accessible via the ingress hostname

            ## Step 4: Check TLS certificates and authentication
            Use `get_kafka_cluster_certificates` to retrieve certificate metadata \
            and listener authentication configuration.
            Check for:
            - Expired or soon-to-expire certificates (< 30 days)
            - Mismatched certificate types for the listener being investigated
            - Authentication type configured on each listener \
            (scram-sha-512, tls, oauth, or none)
            - Whether TLS is enabled on the listener the client is trying to use

            ## Step 5: Check KafkaUser authentication and ACLs
            Use `list_kafka_users` with the cluster name to enumerate all configured users.
            For each user, check:
            - Authentication type matches the listener being investigated \
            (SCRAM-SHA-512 user needs a SCRAM listener, TLS user needs a TLS listener)
            - User is in Ready state (not-ready means User Operator has not provisioned credentials)
            - User has ACLs covering the topics and groups the client needs

            Use `get_kafka_user` for any suspicious user to inspect full ACL rules and quotas.

            **Common authentication mismatches:**
            - Client uses SCRAM credentials but connects to a TLS-only listener
            - Client uses a TLS certificate but connects to a SCRAM listener
            - User exists but is NotReady (credentials not provisioned yet)
            - User has no ACLs but cluster uses simple authorization (all access denied)
            - User has ACLs but they do not cover the required topics or consumer groups

            ## Step 6: Check pod health
            Use `get_kafka_cluster_pods` to verify broker pods are running and ready.
            If broker pods are not ready, clients cannot connect even if the \
            listener is configured correctly.

            ## Step 7: Check broker logs for connection errors
            If pods are running but connectivity issues persist, use \
            `get_kafka_cluster_logs` to check for errors related to listeners \
            or networking.
            Look for: TLS handshake failures, authentication errors, \
            listener binding failures, port conflicts, certificate issues, \
            `SocketException`, `SSLHandshakeException`, or `SaslAuthenticationException`.

            ## Step 8: Summarize connectivity information
            Provide a summary with:
            - Available listeners and their bootstrap addresses
            - Connection protocol for each listener (PLAINTEXT, SSL, SASL_PLAINTEXT, SASL_SSL)
            - KafkaUser authentication and ACL status for the relevant listener
            - Any issues found that could prevent connectivity
            - Example client configuration properties for the relevant listener(s)\
            """.formatted(clusterName, nsClause, listenerClause,
                StrimziToolsPrompts.ERROR_HANDLING_INSTRUCTION);

        return PromptResponse.withMessages(List.of(
            PromptMessage.withUserRole(instructions)
        ));
    }
}
