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
 * MCP prompt template for auditing the security posture of a Kafka cluster.
 *
 * <p>Guides the LLM through enumerating users, reviewing ACLs,
 * checking authentication configuration, and identifying security risks
 * such as overly permissive ACLs, missing quotas, or expiring certificates.</p>
 */
@Singleton
public class AuditSecurityPrompt {

    AuditSecurityPrompt() {
    }

    /**
     * Generate a security audit prompt for a Kafka cluster.
     *
     * @param clusterName the Kafka cluster to audit
     * @param namespace   optional Kubernetes namespace
     * @return prompt response with audit workflow instructions
     */
    @Prompt(
        name = "audit-security",
        description = "Security audit of Kafka cluster users, ACLs,"
            + " authentication, quotas, and certificates."
    )
    public PromptResponse auditSecurity(
        @PromptArg(
            name = "cluster_name",
            description = "Name of the Kafka cluster to audit."
        ) final String clusterName,
        @PromptArg(
            name = "namespace",
            description = "Kubernetes namespace where the cluster is deployed.",
            required = false
        ) final String namespace
    ) {
        String nsClause = namespace != null && !namespace.isBlank()
            ? " in namespace `" + namespace + "`"
            : "";

        String instructions = """
            You are performing a security audit of Kafka cluster `%s`%s.

            Follow these steps in order. After each step, analyze the results \
            before proceeding to the next.

            %s

            ## Step 1: Gather cluster authentication configuration
            Use `get_kafka_cluster` to retrieve the cluster's listener configuration.
            For each listener, note:
            - Whether authentication is configured (type: tls, scram-sha-512, oauth, custom, or none)
            - Whether TLS encryption is enabled
            - Listeners without authentication represent open access points

            Then use `get_kafka_cluster_certificates` to check certificate expiry dates.
            Flag any certificates expiring within 30 days.

            ## Step 2: Enumerate all users
            Use `list_kafka_users` with cluster name `%s`%s to get all KafkaUsers.
            Create a summary table:
            - Total user count
            - Authentication type breakdown (scram-sha-512 vs tls vs tls-external vs none)
            - Users with vs without authorization (ACLs)
            - Users with vs without quotas

            ## Step 3: Audit each user's access
            For each user from Step 2, use `get_kafka_user` to retrieve full details.
            For each user, check for these security concerns:

            **HIGH RISK:**
            - Users with NO authentication configured (access without credentials)
            - ACL rules granting `All` operations (superuser-level access)
            - ACL rules on `cluster` resource type (cluster admin access)
            - ACL rules with wildcard resource name `*` (matches all resources)
            - Users with authentication but NO authorization/ACLs (unrestricted access \
            when using Kafka's built-in authorizer)

            **MEDIUM RISK:**
            - ACL rules with `prefix` pattern on very short prefixes (1-2 chars, overly broad)
            - Users without quotas (potential resource exhaustion / DoS vector)
            - Host restriction set to `*` on high-privilege rules (no IP restriction)

            **INFORMATIONAL:**
            - `deny` type ACL rules (unusual -- verify they are intentional security hardening)
            - `tls-external` authentication (certificates managed outside Strimzi)
            - Multiple authentication types in use (adds operational complexity)

            ## Step 4: Assess overall security posture
            Produce a structured security report with:

            **Summary:**
            - Total users, authentication type distribution
            - Listener authentication coverage
            - Certificate health

            **High-risk findings** (requires immediate attention):
            - List each finding with the affected user name and specific ACL details

            **Medium-risk findings** (should be addressed):
            - List each finding with the affected user name and details

            **Informational findings** (for awareness):
            - List each finding

            ## Step 5: Recommendations
            Provide prioritized remediation steps:
            1. Address any open listeners (no authentication)
            2. Replace wildcard and `All` operation ACLs with specific permissions
            3. Add quotas to unbounded users
            4. Renew expiring certificates
            5. Apply principle of least privilege to overly broad ACLs
            6. Consider adding host restrictions to high-privilege rules\
            """.formatted(clusterName, nsClause,
                StrimziToolsPrompts.ERROR_HANDLING_INSTRUCTION,
                clusterName, nsClause.isEmpty() ? "" : " and namespace");

        return PromptResponse.withMessages(List.of(
            PromptMessage.withUserRole(instructions)
        ));
    }
}
