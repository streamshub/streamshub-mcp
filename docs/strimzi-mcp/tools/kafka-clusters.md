+++
title = 'Kafka cluster tools'
weight = 1
+++

Tools for managing and inspecting Kafka clusters, including status, pods, bootstrap servers, certificates, and logs.

## list_kafka_clusters

List Kafka clusters with status and configuration.

**Parameters**:
- `namespace` (optional) -- Limit search to specific namespace

**Returns**: List of Kafka clusters with name, namespace, status, version, and listener information

**Example**:
```
List all Kafka clusters
```

## get_kafka_fleet_overview

Get aggregated health overview across all Kafka clusters in a single call. Shows status distribution, total broker count, per-cluster summaries, and warnings for clusters that need attention. Designed for fleet-level triage without inspecting each cluster individually.

**Parameters**:
- `namespace` (optional) -- Limit to a specific namespace

**Returns**: Aggregated fleet summary including:
- **Total clusters and brokers** -- aggregate counts
- **Status distribution** -- count of clusters by readiness (ready, not_ready, error, unknown)
- **Clusters** -- per-cluster summary with name, namespace, readiness, Kafka version, broker counts, age, and relationship counts (topics, users, active rebalances, connected KafkaConnect/Bridge/MirrorMaker2 instances)
- **Warnings** -- clusters with health issues: NotReady, Error, or broker replica mismatch (capped at 20)
- **Resource errors** -- resource types that failed to load (fleet-level and per-cluster), distinguishing "0 found" from "failed to read"

**Example**:
```
Give me a fleet overview of all Kafka clusters
```

## get_kafka_cluster

Get detailed information about a specific Kafka cluster including status, version, and configuration.

**Parameters**:
- `clusterName` (required) -- Name of the Kafka cluster
- `namespace` (optional) -- Kubernetes namespace

**Returns**: Comprehensive cluster details including:
- **Core identity** -- name, namespace, kind, `kafka_version` (spec), `running_kafka_version` (status), `kafka_metadata_version` (KRaft), `operator_last_successful_version`, `cluster_id`
- **Readiness and conditions** -- readiness string and full conditions list
- **Listeners** -- configured listeners with type and bootstrap address
- **Replica info** -- separate `broker_replicas` and `controller_replicas` objects each with `expected`, `ready`, `storage_type`, and `storage_size`
- **Security flags** -- `external_access`, `authentication_enabled`, `authorization_enabled`
- **Age** -- `creation_time` and `age_minutes`
- **Auto-rebalance status** -- `auto_rebalance` with `state`, `modes`, and `last_transition_time` (when Cruise Control auto-rebalance is configured)
- **Cluster security status** -- `cluster_security` with `encryption` and `authentication` values from status
- **Reconciliation** -- `reconciliation` object with `generation`, `observed_generation`, and `up_to_date` flag
- **Warnings** -- data gathering issues, omitted when empty

**Example**:
```
Get details for mcp-cluster
```

## get_strimzi_kafka_cluster_overview

Get a full overview of a Kafka cluster and all related Strimzi resources in a single call. Shows the dependency graph: which operator manages the cluster, its node pools, topic/user counts with readiness breakdown, active rebalances, connected KafkaConnect clusters and KafkaBridge instances, and Drain Cleaner status.

KafkaConnect and KafkaBridge resources are matched by comparing their `spec.bootstrapServers` against the cluster's listener addresses.

**Parameters**:
- `clusterName` (required) -- Name of the Kafka cluster
- `namespace` (optional) -- Kubernetes namespace

**Returns**: Structured overview including:
- **Cluster summary** -- name, version, readiness, separate broker and controller replica counts
- **Operator** -- name, version, status
- **Node pools** -- name, roles, replica counts per pool
- **Topics** -- total count, ready/not-ready breakdown
- **Users** -- total count, ready/not-ready breakdown
- **Rebalances** -- total, active count, state breakdown
- **KafkaConnects** -- connected clusters with connector counts
- **KafkaMirrorMaker2** -- connected MM2 instances with mirror counts
- **KafkaBridges** -- connected bridge instances
- **Drain Cleaner** -- name, namespace, readiness, and replica count

**Example**:
```
Give me an overview of my-cluster and all its related resources
```

## get_kafka_cluster_pods

Get pod information for a Kafka cluster.

**Parameters**:
- `clusterName` (required) -- Name of the Kafka cluster
- `namespace` (optional) -- Kubernetes namespace

**Returns**: List of pods with status, roles, readiness, and resource usage

**Example**:
```
Show me the pods for mcp-cluster
```

## get_kafka_bootstrap_servers

Get bootstrap server addresses for a Kafka cluster.

**Parameters**:
- `clusterName` (required) -- Name of the Kafka cluster
- `namespace` (optional) -- Kubernetes namespace

**Returns**: Bootstrap addresses grouped by listener type (internal, external, etc.)

**Example**:
```
What are the bootstrap servers for mcp-cluster?
```

## get_kafka_cluster_certificates

Get TLS certificate information for a Kafka cluster.

**Parameters**:
- `clusterName` (required) -- Name of the Kafka cluster
- `namespace` (optional) -- Kubernetes namespace
- `listenerName` (optional) -- Filter certificates by listener name

**Returns**: Certificate details including:
- **CA policies** -- `cluster_ca_policy` and `clients_ca_policy` objects, each with `renewal_days`, `validity_days`, `generate_certificate_authority`, `certificate_expiration_policy`, and a pre-calculated `calculated_renewal_date`
- **Certificates** -- metadata per Strimzi-managed secret (`secret_name`, `type`, `subject`, `issuer`, `not_before`, `not_after`, `days_until_expiry`, `expired`, and `san` Subject Alternative Names)
- **Listener authentication** -- per-listener auth config (`listener_name`, `listener_type`, `tls_enabled`, `authentication_type`)
- **Errors** -- list of any secrets that could not be read (requires sensitive RBAC permissions)

**Example**:
```
Show me the certificates for mcp-cluster
```

## get_kafka_cluster_policies

Get PodDisruptionBudgets and NetworkPolicies associated with a Kafka cluster.

**Parameters**:
- `clusterName` (required) -- Name of the Kafka cluster
- `namespace` (optional) -- Kubernetes namespace

**Returns**: Policies details including:
- `cluster_name` -- Name of the Kafka cluster
- `namespace` -- Kubernetes namespace
- `pod_disruption_budgets` -- List of PDB objects with `name`, `namespace`, `component`, `min_available`, `max_unavailable`, `current_healthy`, `desired_healthy`, `disruptions_allowed`, `expected_pods`
- `network_policies` -- List of NetworkPolicy objects with `name`, `namespace`, `component`, `policy_types`, `ingress_rules_count`, `egress_rules_count`

**Example**:
```
Show me the PDBs and network policies for mcp-cluster
```

## get_kafka_pvcs

Get PersistentVolumeClaims backing a Kafka cluster including capacity and storage class volume expansion capability.

**Parameters**:
- `clusterName` (required) -- Name of the Kafka cluster
- `namespace` (optional) -- Kubernetes namespace

**Returns**: PVC details including:
- `cluster_name` -- Name of the Kafka cluster
- `namespace` -- Kubernetes namespace
- `pvcs` -- List of PVC objects with `name`, `namespace`, `phase`, `storage_class`, `requested_capacity`, `actual_capacity`, `volume_name`, `allow_volume_expansion`

**Example**:
```
List the PVCs and storage capacity for mcp-cluster
```

## get_strimzi_pod_sets

Lists the StrimziPodSets owned by a Kafka cluster to inspect rolling-update state -- which pods are on the current revision and which are still on a previous one. StrimziPodSet is a Strimzi-internal resource managed by the operator (it replaces StatefulSets).

**Parameters**:
- `clusterName` (required) -- Name of the Kafka cluster
- `namespace` (optional) -- Kubernetes namespace

**Returns**: A list of StrimziPodSets (`items`, `count`), where each item includes:
- `name` -- Name of the StrimziPodSet
- `namespace` -- Kubernetes namespace
- `cluster` -- Owning Kafka cluster name (from the `strimzi.io/cluster` label)
- `pods_count` -- Number of pods managed by this StrimziPodSet
- `ready_pods` -- Number of managed pods that are ready
- `current_pods` -- Number of managed pods on the current (desired) revision
- `pod_revisions` -- Map of **stale** pod name to its current (outdated) `strimzi.io/revision` -- the pods not yet on the desired revision. Present **only during a rolling update**; omitted once every pod is on the desired revision. (The `strimzi.io/revision` annotation hashes the whole pod definition, so every pod has a distinct value even in steady state; a pod is stale only when its live revision differs from the desired revision the operator records in `spec.pods`.)

**Example**:
```
Is a rolling update in progress for mcp-cluster, and which pods are still on the old config?
```

## get_kafka_cluster_logs

Get logs from Kafka cluster pods with error analysis and advanced filtering.

**Parameters**:
- `clusterName` (required) -- Name of the Kafka cluster
- `namespace` (optional) -- Kubernetes namespace
- `filter` (optional) -- Log level filter (e.g., "ERROR", "WARN", "INFO")
- `keywords` (optional) -- List of keywords to search for in logs
- `sinceMinutes` (optional) -- Time window in minutes
- `startTime` (optional) -- Start time (ISO 8601 format)
- `endTime` (optional) -- End time (ISO 8601 format)
- `tailLines` (optional) -- Number of lines to tail from each pod
- `previous` (optional) -- Get logs from previous container instance
- `podNames` (optional) -- List of specific pod names to collect logs from. Omit to collect from all pods in the cluster.

**Returns**: Aggregated logs from the specified (or all) Kafka pods with error analysis and statistics

**Examples**:
```
Get ERROR logs from mcp-cluster for the last 60 minutes
```

```
Get logs from pods my-cluster-kafka-0 and my-cluster-kafka-2 in the last 30 minutes
```

## get_kafka_cluster_config

Returns the effective configuration of a Kafka cluster including broker config, resources, JVM options, listeners, authorization, metrics, logging, Entity Operator, Cruise Control, Kafka Exporter, and per-node-pool overrides. Resolves referenced ConfigMap content for metrics and logging.

**Parameters**:
- `clusterName` (required) -- Name of the Kafka cluster
- `namespace` (optional) -- Kubernetes namespace

**Returns**: Complete configuration breakdown with all Kafka CR spec sections and resolved ConfigMap content, including:
- **Broker config** -- `broker_config` map, `jvm_options`, `rack_awareness`, `listeners`, `authorization`
- **Observability** -- `metrics_config` and `logging` with resolved ConfigMap content
- **Components** -- `entity_operator`, `cruise_control`, `kafka_exporter`
- **Tiered storage** -- `tiered_storage` with type and `remote_storage_manager` class details and safe config (sensitive credentials redacted)
- **Quotas** -- `quotas` with plugin type and safe config properties (sensitive credentials redacted)
- **Maintenance** -- `maintenance_windows` list
- **Node pools** -- per-pool overrides (`node_pools`) with roles, replicas, storage, resources, and JVM options

**Example**:
```
Show the configuration for mcp-cluster
```

## Next steps

- **[Kafka topic and node pool tools](kafka-topics.md)** -- Manage topics and node pools
- **[Tools reference](.)** -- Back to tools overview
