+++
title = 'KafkaRebalance tools'
weight = 3
+++

Tools for listing and inspecting KafkaRebalance resources, which drive Cruise Control rebalancing operations.

## Rebalance management

### list_kafka_rebalances

List KafkaRebalance resources with state, mode, cluster association, and optimization summary. Optionally filter by Kafka cluster.

**Parameters**:
- `clusterName` (optional) -- Kafka cluster name to filter rebalances
- `namespace` (optional) -- Kubernetes namespace

**Returns**: List of rebalances with name, cluster, state, mode, auto-approval status, session ID, and optimization result summary (data to move, replica/leader movements, balancedness scores)

**States**: New, PendingProposal, ProposalReady, Rebalancing, Stopped, NotReady, Ready, ReconciliationPaused

**Example**:
```
List all rebalances for my-cluster
```

### get_kafka_rebalance

Get detailed KafkaRebalance information including rebalance spec, optimization metrics, auto-approval status, and Cruise Control session.

**Parameters**:
- `rebalanceName` (required) -- Name of the KafkaRebalance
- `namespace` (optional) -- Kubernetes namespace

**Returns**: Detailed rebalance information including:
- **State and mode** -- Current rebalance state and mode (full, add-brokers, remove-brokers, remove-disks)
- **Optimization result** -- Data to move (MB), replica movements, leader movements, intra-broker movements, monitored partitions percentage, balancedness scores before/after
- **Spec details** -- Goals, excluded topics, broker list, concurrency settings, replication throttle, skip hard goal check, rebalance disk
- **Annotations** -- Auto-approval status, last rebalance action (approve/stop/refresh)
- **Session** -- Cruise Control session ID and progress ConfigMap reference

**Example**:
```
Get details for rebalance my-rebalance in namespace kafka
```

## CRD availability

If the KafkaRebalance CRD is not installed on the cluster (e.g., Cruise Control is not deployed), `list_kafka_rebalances` returns an empty list instead of an error. The `get_kafka_rebalance` tool will return an error if the specific resource is not found.

## Upgrade readiness integration

Active rebalances are checked as part of the [`assess-upgrade-readiness`](prompts-and-resources.md#assess-upgrade-readiness) prompt and the [`assess_upgrade_readiness`](diagnostics.md#assess_upgrade_readiness) diagnostic tool. A rebalance in any active state (New, PendingProposal, ProposalReady, Rebalancing, Stopped) is a hard blocker for upgrades.

## Next steps

- **[Diagnostic tools](diagnostics.md)** -- Run upgrade readiness checks
- **[Prompts, resources, and subscriptions](prompts-and-resources.md)** -- Use the assess-upgrade-readiness prompt
- **[Tools reference](.)** -- Back to tools overview
