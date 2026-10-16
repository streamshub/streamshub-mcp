/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.prompt;

import io.quarkiverse.mcp.server.Prompt;
import io.quarkiverse.mcp.server.PromptArg;
import io.quarkiverse.mcp.server.PromptMessage;
import io.quarkiverse.mcp.server.PromptResponse;
import io.streamshub.mcp.common.util.InputUtils;
import io.streamshub.mcp.strimzi.config.StrimziToolsPrompts;
import jakarta.inject.Singleton;

import java.util.List;
/**
 * MCP prompt template for troubleshooting Kafka storage issues.
 *
 * <p>Guides the LLM through checking PVC status, storage capacity metrics,
 * StorageClass expansion capability, node storage pressure, and JBOD layout
 * from node pool specifications. Also available as the server-driven
 * {@code diagnose_kafka_storage} composite tool.</p>
 */
@Singleton
public class TroubleshootKafkaStoragePrompt {

    TroubleshootKafkaStoragePrompt() {
    }

    /**
     * Generate a storage troubleshooting prompt for a Kafka cluster.
     *
     * @param clusterName the name of the Kafka cluster
     * @param namespace   optional Kubernetes namespace
     * @param symptom     optional observed symptom
     * @return prompt response with troubleshooting instructions
     */
    @Prompt(
        name = "troubleshoot-kafka-storage",
        description = "Step-by-step troubleshooting of Kafka storage issues."
            + " Checks PVC status, capacity metrics, volume expansion, node storage pressure,"
            + " and JBOD layout."
    )
    public PromptResponse troubleshootKafkaStorage(
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
            name = "symptom",
            description = "Observed symptom, e.g. 'disk full', 'PVC Pending',"
                + " 'broker pod CrashLoopBackOff'.",
            required = false
        ) final String symptom
    ) {
        String instructions = buildInstructions(clusterName,
            InputUtils.normalizeInput(namespace),
            InputUtils.normalizeInput(symptom));

        return PromptResponse.withMessages(List.of(
            PromptMessage.withAssistantRole(StrimziToolsPrompts.systemMessage("Kafka storage troubleshooting")),
            PromptMessage.withUserRole(instructions)
        ));
    }

    private static String buildInstructions(final String clusterName,
                                            final String ns,
                                            final String symptom) {
        String nsClause = ns != null ? " in namespace `" + ns + "`" : "";
        String symptomClause = symptom != null ? " The reported symptom is: " + symptom + "." : "";
        String nsArg = ns != null ? ", namespace='" + ns + "'" : "";

        return """
            Troubleshoot Kafka storage for cluster `%s`%s.%s

            This workflow covers full disks, PVC binding failures, volume expansion,
            and JBOD layout problems. Also available as the server-driven \
            `diagnose_kafka_storage` composite tool for a single-call investigation.

            ## Step 1: Check cluster status
            Use `get_kafka_cluster(cluster_name='%s'%s)` to verify the cluster exists.
            If the cluster is NotReady, storage issues may be a cause or a symptom.
            Continue with storage steps regardless -- both can be true at the same time.

            ## Step 2: Inspect PersistentVolumeClaims
            Use `get_kafka_pvcs(cluster_name='%s'%s)` to retrieve PVC status, \
            capacity, and StorageClass expansion capability.
            Check for:
            - **PVCs in Pending state** -- broker cannot start until its volume is bound
            - **Capacity and available space** -- compare requested vs actual capacity
            - **`allowVolumeExpansion`** -- if false on the StorageClass, \
            the only option to grow storage is re-provisioning
            - **Volume names** -- each broker should have exactly one volume per JBOD disk

            **Common PVC binding failures:**
            - StorageClass does not exist or its provisioner is not running
            - No PersistentVolumes are available that match the requested size and access mode
            - Node selector or zone constraints conflict with provisioner placement
            - `WaitForFirstConsumer` binding mode and scheduler cannot satisfy pod constraints

            ## Step 3: Check storage capacity and disk metrics
            Use `get_kafka_metrics(cluster_name='%s'%s, category='storage')` \
            to retrieve disk utilization metrics.
            Key indicators:
            - **`kafka_log_log_size`** -- total bytes used by Kafka logs per broker; \
            compare against PVC capacity to estimate free space
            - **`kafka_server_kafkaserver_linux_disk_read_bytes` / \
            `kafka_server_kafkaserver_linux_disk_write_bytes`** -- I/O throughput; \
            unusually high write rates may indicate runaway producers or missing \
            retention settings
            - **Trends** -- a steadily growing `kafka_log_log_size` with no \
            corresponding increase in PVC capacity signals impending disk exhaustion

            If metrics are unavailable (metrics backend not configured), note the gap \
            and rely on PVC capacity from Step 2.

            ## Step 4: Assess volume expansion capability
            Based on `get_kafka_pvcs` results, determine whether online expansion is possible:
            - If `allowVolumeExpansion` is **true**, the StorageClass supports online resize: \
            increase `storage.size` in each KafkaNodePool spec and let the operator patch the PVCs
            - If `allowVolumeExpansion` is **false**, options are:
              1. Add a new JBOD disk to the node pool and migrate data offline
              2. Re-provision with a new StorageClass that supports expansion (requires migration)
              3. Add brokers in a new node pool with larger storage and rebalance partitions

            ## Step 5: Check pod health for storage-related failures
            Use `get_kafka_cluster_pods(cluster_name='%s'%s)` to identify pods with:
            - **OOMKilled** or **CrashLoopBackOff** -- broker may be failing due to \
            `java.io.IOException: No space left on device`
            - **Restart count** -- repeated restarts on a single broker indicate \
            a persistent storage failure rather than a transient issue
            - **Not-ready pods** -- a broker that cannot start often cannot open \
            its log directories because the underlying volume is full or unbound

            ## Step 6: Inspect JBOD layout from node pool specs
            Use `list_kafka_node_pools(cluster_name='%s'%s)` and then \
            `get_kafka_node_pool` for each pool to examine the declared storage layout.
            Check for each pool:
            - **Storage type** -- `jbod` (multiple disks) or `persistent-claim` (single disk)
            - **JBOD volumes** -- each volume has an `id` and a `size`; mismatches \
            between the number of volumes in the spec and the number of PVCs are a sign \
            of a partially failed expansion or migration
            - **Size per volume** -- if a volume's declared size is less than the \
            actual PVC capacity (after a manual resize), the spec and PVC are out of sync
            - **StorageClass name** -- confirm the same StorageClass is used consistently \
            across pools, or document intentional differences

            ## Step 7: Check Kubernetes events for storage errors
            Use `get_strimzi_events(resourceName='%s'%s, resource_kind='Kafka')` \
            to look for warning events related to PVC binding, provisioning, or capacity.
            Look for: `FailedMount`, `ProvisioningFailed`, `VolumeResizeFailed`, \
            `FailedBinding`, `Evicted`, or `EvictionThresholdMet`.

            ## Step 8: Check broker logs for I/O errors
            If the cluster shows unhealthy pods or metrics indicate disk pressure, \
            use `get_kafka_cluster_logs(cluster_name='%s'%s)` to check for storage-related errors.
            Look for: `IOException`, `No space left on device`, \
            `Failed to acquire lock on file`, `KafkaStorageException`, \
            `DiskFailureException`, `LogDirFailedException`.

            ## Step 9: Summarize and recommend
            Produce a structured storage diagnosis:

            **PVC status:**
            | Broker | PVC | Status | Capacity | StorageClass | Expansion |
            (one row per PVC)

            **Issues found** (severity: CRITICAL / HIGH / MEDIUM):
            - List each issue with the affected broker, root cause, and impact

            **Remediation plan** (prioritized):
            1. Immediate -- actions to prevent data loss or restore availability
            2. Short-term -- expansions or layout corrections that can be done \
            with a rolling restart
            3. Long-term -- architectural changes (StorageClass migration, JBOD redesign)\
            """.formatted(
                clusterName, nsClause, symptomClause,
                clusterName, nsArg,
                clusterName, nsArg,
                clusterName, nsArg,
                clusterName, nsArg,
                clusterName, nsArg,
                clusterName, nsArg,
                clusterName, nsArg);
    }
}
