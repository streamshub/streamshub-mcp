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

## get_kafka_cluster

Get detailed information about a specific Kafka cluster including status, version, and configuration.

**Parameters**:
- `clusterName` (required) -- Name of the Kafka cluster
- `namespace` (optional) -- Kubernetes namespace

**Returns**: Comprehensive cluster details including status, conditions, version, listeners, node pools, and replica counts

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
- **Cluster summary** -- name, version, readiness, replica counts
- **Operator** -- name, version, status
- **Node pools** -- name, roles, replica counts per pool
- **Topics** -- total count, ready/not-ready breakdown
- **Users** -- total count, ready/not-ready breakdown
- **Rebalances** -- total, active count, state breakdown
- **KafkaConnects** -- connected clusters with connector counts
- **KafkaMirrorMaker2** -- connected MM2 instances with mirror counts
- **KafkaBridges** -- connected bridge instances
- **Drain Cleaner** -- deployment and readiness status

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

**Returns**: Certificate details including CA certificates and listener certificates (requires sensitive RBAC permissions)

**Example**:
```
Show me the certificates for mcp-cluster
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

**Returns**: Complete configuration breakdown with all Kafka CR spec sections and resolved ConfigMap content

**Example**:
```
Show the configuration for mcp-cluster
```

## Next steps

- **[Kafka topic and node pool tools](kafka-topics.md)** -- Manage topics and node pools
- **[Tools reference](.)** -- Back to tools overview
