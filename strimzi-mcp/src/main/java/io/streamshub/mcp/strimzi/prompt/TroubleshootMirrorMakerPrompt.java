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
 * MCP prompt template for troubleshooting KafkaMirrorMaker2 issues.
 *
 * <p>Guides the LLM through checking MM2 status, connector statuses,
 * pod health, logs, and events to distinguish replication issues
 * from platform and connectivity problems.</p>
 */
@Singleton
public class TroubleshootMirrorMakerPrompt {

    TroubleshootMirrorMakerPrompt() {
    }

    /**
     * Generate a MirrorMaker2 troubleshooting prompt.
     *
     * @param mirrorMakerName the KafkaMirrorMaker2 name
     * @param namespace       optional Kubernetes namespace
     * @param symptom         optional observed symptom
     * @return prompt response with troubleshooting instructions
     */
    @Prompt(
        name = "troubleshoot-mirror-maker",
        description = "Step-by-step troubleshooting of a KafkaMirrorMaker2 issue."
            + " Guides through MM2 status, connector health, pod inspection,"
            + " and log analysis for cross-cluster replication."
    )
    public PromptResponse troubleshootMirrorMaker(
        @PromptArg(
            name = "mirror_maker_name",
            description = "Name of the KafkaMirrorMaker2 to troubleshoot."
        ) final String mirrorMakerName,
        @PromptArg(
            name = "namespace",
            description = "Kubernetes namespace where the MirrorMaker2 is deployed.",
            required = false
        ) final String namespace,
        @PromptArg(
            name = "symptom",
            description = "Observed symptom, e.g. 'replication lag', 'connectors failing',"
                + " or 'pods restarting'.",
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
            You are troubleshooting KafkaMirrorMaker2 `%s`%s.%s

            KafkaMirrorMaker2 manages cross-cluster replication using MirrorSourceConnector \
            (data replication), MirrorCheckpointConnector (consumer group offset sync), \
            and MirrorHeartbeatConnector (connectivity monitoring).

            Follow these steps in order. After each step, analyze the results \
            before proceeding to the next.

            %s

            ## Step 1: Check MM2 status and configuration
            Use `get_kafka_mirror_maker(mirror_maker_name='%s'%s)` to retrieve the MM2 \
            status, mirror configurations, and connector statuses.
            Check for:
            - Overall readiness (Ready/NotReady/Error)
            - Replicas: are all expected replicas ready?
            - Mirror configurations: are source/target clusters correct?
            - Topic patterns: are the right topics being mirrored?
            - Connector statuses in the status field: check each \
            MirrorSourceConnector, MirrorCheckpointConnector state

            ## Step 2: Check connector statuses
            From the `connector_statuses` field in Step 1, examine each connector:
            - **MirrorSourceConnector**: handles data replication. If FAILED, \
            check source cluster connectivity and topic access.
            - **MirrorCheckpointConnector**: syncs consumer group offsets. If FAILED, \
            check target cluster write access.
            - Note the connector name pattern: `{source}->{target}.MirrorSourceConnector`

            ## Step 3: Check MM2 pods
            Use `get_kafka_mirror_maker_pods(mirror_maker_name='%s'%s)`.
            Look for:
            - Pods not in Running phase
            - High restart counts (may indicate OOM or crashes)
            - Pods that are not ready

            ## Step 4: Check MM2 logs
            Use `get_kafka_mirror_maker_logs(mirror_maker_name='%s'%s, filter='errors')`.
            Look for: `MirrorSourceConnector`, `MirrorCheckpointConnector`, \
            `ReplicationException`, `RetriableException`, `ConnectException`, \
            `TimeoutException`, `AuthenticationException`, `SaslAuthenticationException`, \
            `TopicAuthorizationException`, `GroupAuthorizationException`.

            ## Step 5: Check Kubernetes events
            Use `get_strimzi_events(%s%s)` to look for warning events \
            related to the MM2 resource.
            Look for: reconciliation failures, resource quota issues, scheduling failures.

            ## Step 6: Correlate and diagnose
            Distinguish between:
            - **Replication issues**: source cluster unreachable, topic authorization \
            failures, TLS/SASL misconfiguration between clusters
            - **Topic pattern issues**: topics not matching `topicsPattern`, \
            excluded by `topicsExcludePattern`, or internal topics being mirrored
            - **Consumer group issues**: offset sync failures, group authorization \
            errors on target cluster, checkpoint connector failures
            - **Resource issues**: OOM kills, CPU throttling, insufficient replicas \
            for the replication load
            - **Operator issues**: reconciliation failures, image pull errors, \
            invalid CR configuration

            Provide:
            - Root cause diagnosis
            - Severity assessment
            - Specific remediation steps (which field in the MM2 CR to change, \
            which cluster configuration to check)\
            """.formatted(mirrorMakerName, nsClause, symptomClause,
                StrimziToolsPrompts.ERROR_HANDLING_INSTRUCTION,
                mirrorMakerName, nsArg,
                mirrorMakerName, nsArg,
                mirrorMakerName, nsArg,
                nsArg.isEmpty() ? "" : "namespace='" + namespace + "'",
                nsArg.isEmpty() ? "" : ", cluster_name='" + mirrorMakerName + "'");

        return PromptResponse.withMessages(List.of(
            PromptMessage.withUserRole(instructions)
        ));
    }
}
