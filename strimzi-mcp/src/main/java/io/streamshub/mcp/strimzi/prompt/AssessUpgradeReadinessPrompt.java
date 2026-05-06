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
 * MCP prompt template for assessing Kafka cluster upgrade readiness.
 *
 * <p>Guides the LLM through checking cluster health, operator status,
 * resource capacity, replication state, and configuration compatibility
 * before performing a Strimzi or Kafka version upgrade.</p>
 */
@Singleton
public class AssessUpgradeReadinessPrompt {

    AssessUpgradeReadinessPrompt() {
    }

    /**
     * Generate an upgrade readiness assessment prompt for a Kafka cluster.
     *
     * @param clusterName   the Kafka cluster to assess
     * @param namespace     optional Kubernetes namespace
     * @param targetVersion optional target Kafka or Strimzi version
     * @return prompt response with upgrade readiness instructions
     */
    @SuppressWarnings("checkstyle:MethodLength")
    @Prompt(
        name = "assess-upgrade-readiness",
        description = "Pre-upgrade readiness check for a Kafka cluster."
            + " Assesses cluster health, replication, operator status,"
            + " and configuration before upgrading Strimzi or Kafka."
    )
    public PromptResponse assessUpgradeReadiness(
        @PromptArg(
            name = "cluster_name",
            description = "Name of the Kafka cluster to assess."
        ) final String clusterName,
        @PromptArg(
            name = "namespace",
            description = "Kubernetes namespace where the cluster is deployed.",
            required = false
        ) final String namespace,
        @PromptArg(
            name = "target_version",
            description = "Target Kafka or Strimzi version for the upgrade,"
                + " e.g. 'Kafka 4.2.0' or 'Strimzi 0.45.0'.",
            required = false
        ) final String targetVersion
    ) {
        String nsClause = namespace != null && !namespace.isBlank()
            ? " in namespace `" + namespace + "`"
            : "";
        String nsArg = namespace != null && !namespace.isBlank()
            ? ", namespace='" + namespace + "'"
            : "";
        String versionClause = targetVersion != null && !targetVersion.isBlank()
            ? " Target version: " + targetVersion + "."
            : "";

        String instructions = """
            You are assessing upgrade readiness for Kafka cluster `%s`%s.%s

            **IMPORTANT**: An upgrade triggers a rolling restart of all broker \
            and controller pods. The cluster must be healthy and have sufficient \
            replication headroom to tolerate individual pod restarts without \
            data unavailability.

            Follow these steps in order. Each step produces a GO/NO-GO verdict. \
            If any step is NO-GO, the cluster is NOT ready for upgrade.

            %s

            ## Step 1: Cluster health baseline [GO/NO-GO]
            Use `get_kafka_cluster(cluster_name='%s'%s)` to check current state.

            **GO conditions** (ALL must be true):
            - Cluster status is Ready
            - No warning or error conditions
            - observedGeneration equals generation (no pending reconciliation)
            - Kafka version is documented (needed for upgrade path validation)

            **NO-GO conditions** (ANY blocks upgrade):
            - Cluster is NotReady
            - Active conditions with type Warning or Error
            - Reconciliation in progress (generation mismatch)

            Record the current Kafka version and Strimzi operator version.

            ## Step 2: Operator health [GO/NO-GO]
            Use `list_strimzi_operators(%s)` and `get_strimzi_operator` to check \
            operator status.

            **GO conditions**:
            - Operator deployment is available with all replicas ready
            - Operator version is known (for upgrade path validation)

            **NO-GO conditions**:
            - Operator not available or degraded
            - Operator pod restarting or in error state

            Use `get_strimzi_operator_logs(%s)` to check for recent errors.
            Any recurring reconciliation errors indicate instability.

            ## Step 3: Node pool and pod health [GO/NO-GO]
            Use `list_kafka_node_pools(cluster_name='%s'%s)` and \
            `get_kafka_node_pool` for each pool.
            Then use `get_kafka_cluster_pods(cluster_name='%s'%s)`.

            **GO conditions**:
            - All node pools Ready with expected replica count
            - All pods Running and Ready
            - No recent restarts (restart count = 0 or stable)
            - All containers healthy

            **NO-GO conditions**:
            - Any pod not in Running/Ready state
            - Any pod with recent restarts (>0 in last hour)
            - Node pool replica count mismatch (scaling in progress)

            ## Step 4: Replication health [GO/NO-GO — CRITICAL]
            Use `get_kafka_metrics(cluster_name='%s'%s, category='replication')`.

            **GO conditions** (ALL must be true):
            - offlinepartitionscount = 0
            - underreplicatedpartitions = 0
            - All partitions fully in-sync (ISR = replication factor)
            - maxlag close to 0

            **NO-GO conditions** (ANY blocks upgrade):
            - offlinepartitionscount > 0 (CRITICAL — data unavailable)
            - underreplicatedpartitions > 0 (rolling restart will lose replicas)
            - High replication lag (followers cannot keep up)

            **This is the most critical check.** During a rolling restart, one \
            broker is down at a time. If partitions are already under-replicated, \
            the restart will cause further replica loss and potential data \
            unavailability.

            ## Step 5: Resource headroom [GO/NO-GO]
            Use `get_kafka_metrics(cluster_name='%s'%s, category='resources')` \
            and `get_kafka_metrics(cluster_name='%s'%s, category='performance')`.

            **GO conditions**:
            - Broker request handler idle > 0.5 (50%% headroom)
            - Heap usage < 80%% of max
            - No GC pressure (stable GC count)

            **NO-GO conditions**:
            - Broker request handler idle < 0.3 (overloaded — restart will \
            overwhelm remaining brokers)
            - Heap usage > 90%% (OOM risk during restart)
            - Network processor idle < 0.3 (network saturated)

            During a rolling restart, the remaining brokers must absorb the \
            load of the restarting broker. If utilization is already high, \
            this can cause a cascade of failures.

            ## Step 6: Drain Cleaner readiness [GO/NO-GO]
            Use `check_drain_cleaner_readiness` to verify drain cleaner status.

            **GO conditions**:
            - Drain Cleaner deployed and ready (ensures graceful pod evictions)
            - Webhook active
            - TLS certificate valid

            **WARNING** (not a hard NO-GO but strongly recommended):
            - If Drain Cleaner is NOT deployed, pod evictions during node \
            maintenance may be abrupt and cause partition unavailability

            ## Step 7: Certificate health [ADVISORY]
            Use `get_kafka_cluster_certificates(cluster_name='%s'%s)` to check \
            certificate validity.
            - Certificates expiring within 7 days: **delay upgrade** and renew first
            - Certificates expiring within 30 days: note but not blocking
            - An upgrade may trigger certificate renewal if the operator \
            detects approaching expiry

            ## Step 8: Kubernetes events [ADVISORY]
            Use `get_strimzi_events(cluster_name='%s'%s)` to check for recent \
            warning events.
            - Recent FailedScheduling events suggest node resource pressure
            - Recent eviction events suggest node instability
            - These may impact the rolling restart process

            ## Step 9: Upgrade readiness verdict
            Produce a structured readiness report:

            **Overall verdict**: GO / NO-GO / CONDITIONAL

            **Pre-flight checklist:**
            | Check | Status | Details |
            | --- | --- | --- |
            | Cluster health | GO/NO-GO | ... |
            | Operator health | GO/NO-GO | ... |
            | Pod health | GO/NO-GO | ... |
            | Replication | GO/NO-GO | ... |
            | Resource headroom | GO/NO-GO | ... |
            | Drain Cleaner | GO/NO-GO | ... |
            | Certificates | OK/WARNING | ... |
            | Events | OK/WARNING | ... |

            **If GO:**
            - Recommended maintenance window duration estimate
            - Expected rolling restart time (approx 2-3 min per broker)
            - Suggested time of day (low-traffic period)

            **If NO-GO:**
            - List each blocking issue with remediation steps
            - Estimated time to resolve each issue
            - Re-check procedure after remediation

            **If CONDITIONAL:**
            - List conditions that should be met before proceeding
            - Risk assessment for proceeding without meeting conditions\
            """.formatted(
                clusterName, nsClause, versionClause,
                StrimziToolsPrompts.ERROR_HANDLING_INSTRUCTION,
                clusterName, nsArg,
                nsArg.isEmpty() ? "" : "namespace='" + namespace + "'",
                nsArg,
                clusterName, nsArg,
                clusterName, nsArg,
                clusterName, nsArg,
                clusterName, nsArg,
                clusterName, nsArg,
                clusterName, nsArg,
                clusterName, nsArg);

        return PromptResponse.withMessages(List.of(
            PromptMessage.withUserRole(instructions)
        ));
    }
}