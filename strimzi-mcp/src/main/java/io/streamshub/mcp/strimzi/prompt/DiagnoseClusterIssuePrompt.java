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
 * MCP prompt template for diagnosing Kafka cluster issues.
 *
 * <p>Guides the LLM through a structured diagnostic workflow:
 * check cluster status, node pools, operator logs, pod health,
 * pod logs, and cluster metrics to identify root causes.</p>
 */
@Singleton
public class DiagnoseClusterIssuePrompt {

    DiagnoseClusterIssuePrompt() {
    }

    /**
     * Generate a diagnostic prompt for a Kafka cluster issue.
     *
     * @param clusterName the name of the Kafka cluster
     * @param namespace   the Kubernetes namespace of the cluster
     * @param symptom     optional description of the observed symptom
     * @return prompt response with diagnostic instructions
     */
    @SuppressWarnings("checkstyle:MethodLength")
    @Prompt(
        name = "diagnose-cluster-issue",
        description = "Step-by-step diagnosis of a Kafka cluster issue."
            + " Guides through status checks, operator logs, pod inspection, and metrics analysis."
    )
    public PromptResponse diagnoseClusterIssue(
        @PromptArg(
            name = "cluster_name",
            description = "Name of the Kafka cluster to diagnose."
        ) final String clusterName,
        @PromptArg(
            name = "namespace",
            description = "Kubernetes namespace where the Kafka cluster is deployed.",
            required = false
        ) final String namespace,
        @PromptArg(
            name = "symptom",
            description = "Observed symptom, e.g. 'NotReady for 15 minutes' or 'pods restarting'.",
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
            You are diagnosing a Kafka cluster issue for cluster `%s`%s.%s

            **CRITICAL CONTEXT FOR INCIDENT RESPONSE:**
            - Kafka clusters can experience cascading failures
            - Data availability issues (offline partitions) are CRITICAL
            - Pod failures may be symptoms, not root causes
            - Operator reconciliation issues can prevent recovery
            
            **Diagnostic Priority:**
            1. **CRITICAL**: Data availability (offline partitions, cluster NotReady)
            2. **HIGH**: Pod health (crashes, OOM, restarts)
            3. **MEDIUM**: Operator reconciliation (stuck, errors)
            4. **LOW**: Performance degradation (if cluster is otherwise healthy)

            Follow these steps in order. After each step, analyze the results \
            before proceeding to the next. **Stop and escalate immediately if you find \
            offline partitions or cluster-wide unavailability.**

            ## Step 1: Check Kafka cluster status [CRITICAL - cluster availability]
            Use `get_kafka_cluster` to retrieve the cluster status and conditions.
            
            **STOP AND ESCALATE IF:**
            - Cluster status is NotReady for >10 minutes (not during rolling update)
            - Conditions show "KafkaNotReady" or "ZooKeeperNotReady"
            
            Look for:
            - NotReady conditions → cluster unavailable or degraded
            - Stalled reconciliation (observedGeneration != generation) → operator stuck
            - Warning conditions → potential issues developing
            - Recent status changes → correlate with symptom timeline
            
            **Expected during normal operations:**
            - Brief NotReady during rolling updates (2-3 min per broker)
            - Certificate renewal reconciliations (every 30 days)

            ## Step 2: Check KafkaNodePool statuses [HIGH - broker availability]
            Use `list_kafka_node_pools` to list all node pools for this cluster.
            For any pool that looks unhealthy, use `get_kafka_node_pool` for details.
            
            Look for:
            - Pools with fewer ready replicas than expected → brokers down
            - Pools in non-Ready state → provisioning or configuration issues
            - Role mismatches (controller vs broker) → KRaft migration issues
            - Uneven replica distribution → scaling or rebalancing in progress
            
            **If multiple pools are unhealthy, this indicates a cluster-wide issue \
            (infrastructure, operator, or configuration problem).**

            ## Step 3: Check Strimzi operator [MEDIUM - reconciliation health]
            Use `list_strimzi_operators` to find the operator managing this cluster.
            Use `get_strimzi_operator_logs` to read operator logs.
            
            Look for:
            - Reconciliation errors for `%s` → operator can't manage cluster
            - Repeated error patterns → persistent configuration or permission issues
            - Exceptions or stack traces → operator bugs or resource conflicts
            - "Reconciliation #N failed" → count failures to assess severity
            
            **Common operator issues:**
            - Missing RBAC permissions → check ClusterRole/RoleBinding
            - Invalid resource specs → check Kafka CR for validation errors
            - Resource conflicts → check for competing operators or manual changes
            - Kubernetes API throttling → check operator resource limits

            ## Step 4: Check pod health [HIGH - broker availability]
            Use `get_kafka_cluster_pods` to check all pods for the cluster.
            
            **CRITICAL POD STATES:**
            - CrashLoopBackOff → pod failing to start (check logs in Step 5)
            - Pending → resource constraints or scheduling issues
            - High restart counts (>3 in last hour) → unstable broker
            - Containers not ready → health checks failing
            
            Look for patterns:
            - All pods unhealthy → cluster-wide issue (infrastructure, config)
            - Single pod unhealthy → isolated broker issue (disk, memory, network)
            - Pods restarting sequentially → rolling update in progress (expected)
            - Pods stuck in Pending → resource exhaustion (CPU, memory, PVCs)
            
            **If pods are healthy but cluster is NotReady, issue is likely at \
            the Kafka application level (check metrics in Step 6).**

            ## Step 5: Read pod logs from unhealthy pods [HIGH - root cause identification]
            For any unhealthy pods found in Step 4, use `get_kafka_cluster_logs` \
            with filter 'errors' to get error logs from broker pods.
            
            **CRITICAL ERROR PATTERNS:**
            - "OutOfMemoryError" or "OOM killed" → insufficient heap, increase memory
            - "No space left on device" → disk full, increase storage or clean up
            - "Connection refused" or "Unable to connect" → network issues
            - "IOException" → disk I/O problems or corrupted data
            - "FATAL" or "ERROR" in startup logs → configuration or dependency issues
            
            **Common root causes:**
            - OOM: Heap too small for workload, increase Xmx or reduce load
            - Disk full: Log retention too long, increase storage or reduce retention
            - Network: Firewall rules, DNS issues, or pod network problems
            - Corruption: Disk failures, need to replace broker and rebuild replicas

            ## Step 6: Check cluster metrics [CRITICAL - data availability]
            Use `get_kafka_metrics` with category 'replication' to check replication health.

            **STOP AND ESCALATE IF:**
            - offlinepartitionscount > 0 → partitions unavailable (CRITICAL)
            - underreplicatedpartitions > 0 for >5 minutes → data loss risk

            Use the `interpretation` field for per-metric threshold guidance.

            If replication issues found, also call `get_kafka_metrics` with \
            category 'performance' and use its `interpretation` field for thresholds.

            **Correlation patterns:**
            - Replication lag + low broker idle → broker overload (scale or reduce load)
            - Replication lag + normal broker idle → disk I/O bottleneck
            - Offline partitions + pod crashes → broker failures causing partition unavailability
            - Offline partitions + healthy pods → controller issues (check operator logs)

            ## Step 7: Correlate and summarize [ROOT CAUSE ANALYSIS]
            Correlate the findings from all steps, including metrics data.
            
            **Distinguish between issue types:**
            
            1. **Operator-initiated changes (EXPECTED):**
               - Rolling updates → pods restarting sequentially, brief NotReady
               - Certificate renewal → reconciliation activity, no pod restarts
               - Configuration changes → reconciliation, possible rolling restart
               **Action**: Monitor for completion, no intervention needed
            
            2. **Infrastructure failures (CRITICAL):**
               - OOM → increase heap size or reduce load
               - Disk full → increase storage or reduce retention
               - Node failures → check Kubernetes node health
               - Network issues → check pod network, DNS, firewall rules
               **Action**: Fix infrastructure, may need broker replacement
            
            3. **Configuration errors (HIGH):**
               - Invalid resource specs → fix Kafka CR and reapply
               - Missing secrets → create required secrets
               - RBAC issues → fix operator permissions
               **Action**: Correct configuration, operator will reconcile
            
            4. **Performance degradation (MEDIUM):**
               - Overloaded brokers → scale horizontally or reduce load
               - Replication lag → check broker capacity and disk I/O
               - Network bottlenecks → increase network threads or reduce connections
               **Action**: Capacity planning, scaling, or load reduction
            
            5. **Cascading failures (CRITICAL):**
               - Broker overload → replication lag → offline partitions
               - GC thrashing → broker overload → replication lag
               - Controller failure → offline partitions → cluster unavailability
               **Action**: Address root cause first, then allow system to recover

            ## Final Summary
            Provide a clear summary with:
            1. **Root cause** (single most likely cause, not a list of symptoms)
            2. **Severity** (CRITICAL/HIGH/MEDIUM/LOW based on data availability impact)
            3. **Impact** (what is affected: data availability, performance, stability)
            4. **Evidence** (specific findings from steps 1-6 that support the diagnosis)
            5. **Actionable recommendations** (prioritized, specific steps to resolve)
            6. **Expected recovery time** (how long until cluster is healthy after fix)
            7. **Prevention** (how to avoid this issue in the future)
            
            **Remember:** Symptoms are not root causes. Pod restarts are symptoms; \
            OOM or disk full are root causes. Offline partitions are symptoms; \
            broker failures or controller issues are root causes.\
            """.formatted(clusterName, nsClause, symptomClause, clusterName);

        return PromptResponse.withMessages(List.of(
            PromptMessage.withUserRole(instructions)
        ));
    }
}