/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.prompt;

import io.quarkiverse.mcp.server.Prompt;
import io.quarkiverse.mcp.server.PromptArg;
import io.quarkiverse.mcp.server.PromptMessage;
import io.quarkiverse.mcp.server.PromptResponse;
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

        String instructions = "You are troubleshooting connectivity to Kafka cluster `"
            + clusterName + "`" + nsClause + "." + listenerClause
            + "\n\nFollow these steps in order. After each step, analyze the results"
            + " before proceeding to the next."
            + "\n\n## Step 1: Check cluster status"
            + "\nUse `get_kafka_cluster` to verify the cluster exists and is Ready."
            + "\nIf the cluster is not Ready, connectivity issues may be a symptom"
            + " of a deeper problem. Consider using the `diagnose-cluster-issue` prompt instead."
            + "\n\n## Step 2: Get bootstrap server addresses"
            + "\nUse `get_kafka_bootstrap_servers` to retrieve all listener configurations."
            + "\nFor each listener, note:"
            + "\n- Listener name and type (internal, route, loadbalancer, nodeport, ingress)"
            + "\n- Bootstrap address and port"
            + "\n- Whether TLS is enabled"
            + "\n\n## Step 3: Verify listener accessibility"
            + "\nBased on the listener type, explain how the client should connect:"
            + "\n- **internal**: accessible only within the Kubernetes cluster"
            + " via `<cluster>-kafka-bootstrap.<namespace>.svc:<port>`"
            + "\n- **route** (OpenShift): accessible externally via the route hostname on port 443"
            + "\n- **loadbalancer**: accessible externally via the load balancer IP/hostname"
            + "\n- **nodeport**: accessible via any cluster node IP on the assigned node port"
            + "\n- **ingress**: accessible via the ingress hostname"
            + "\n\n## Step 4: Check pod health"
            + "\nUse `get_kafka_cluster_pods` to verify broker pods are running and ready."
            + "\nIf broker pods are not ready, clients cannot connect even if the"
            + " listener is configured correctly."
            + "\n\n## Step 5: Summarize connectivity information"
            + "\nProvide a summary with:"
            + "\n- Available listeners and their bootstrap addresses"
            + "\n- Connection protocol for each listener (PLAINTEXT, SSL, SASL_PLAINTEXT, SASL_SSL)"
            + "\n- Any issues found that could prevent connectivity"
            + "\n- Example client configuration properties for the relevant listener(s)";

        return PromptResponse.withMessages(List.of(
            PromptMessage.withUserRole(instructions)
        ));
    }
}