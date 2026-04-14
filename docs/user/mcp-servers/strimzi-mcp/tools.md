---
title: "Tools Reference"
weight: 3
---

Complete reference for all tools, prompts, and resources provided by the Strimzi MCP Server.

## Tool Categories

- [Cluster Management](#cluster-management)
- [Topic Management](#topic-management)
- [Node Pool Management](#node-pool-management)
- [Operator Management](#operator-management)
- [Events](#events)
- [Diagnostic Tools](#diagnostic-tools)
- [Metrics Tools](#metrics-tools)
- [Prompt Templates](#prompt-templates)
- [Resource Templates](#resource-templates)

## Smart Discovery

All tools support **smart discovery** - the namespace parameter is always optional. When omitted, tools automatically search across the entire cluster to find the requested resources.

## Cluster Management

### list_kafka_clusters

List Kafka clusters with status and configuration.

**Parameters**:
- `namespace` (optional) — Limit search to specific namespace

**Returns**: List of Kafka clusters with name, namespace, status, version, and listener information

**Example**:
```
List all Kafka clusters
```

### get_kafka_cluster

Get detailed information about a specific Kafka cluster including status, version, and configuration.

**Parameters**:
- `clusterName` (required) — Name of the Kafka cluster
- `namespace` (optional) — Kubernetes namespace

**Returns**: Comprehensive cluster details including status, conditions, version, listeners, node pools, and replica counts

**Example**:
```
Get details for mcp-cluster
```

### get_kafka_cluster_pods

Get pod information for a Kafka cluster.

**Parameters**:
- `clusterName` (required) — Name of the Kafka cluster
- `namespace` (optional) — Kubernetes namespace

**Returns**: List of pods with status, roles, readiness, and resource usage

**Example**:
```
Show me the pods for mcp-cluster
```

### get_kafka_bootstrap_servers

Get bootstrap server addresses for a Kafka cluster.

**Parameters**:
- `clusterName` (required) — Name of the Kafka cluster
- `namespace` (optional) — Kubernetes namespace

**Returns**: Bootstrap addresses grouped by listener type (internal, external, etc.)

**Example**:
```
What are the bootstrap servers for mcp-cluster?
```

### get_kafka_cluster_certificates

Get TLS certificate information for a Kafka cluster.

**Parameters**:
- `clusterName` (required) — Name of the Kafka cluster
- `namespace` (optional) — Kubernetes namespace

**Returns**: Certificate details including CA certificates and listener certificates (requires sensitive RBAC permissions)

**Example**:
```
Show me the certificates for mcp-cluster
```

### get_kafka_cluster_logs

Get logs from Kafka cluster pods with error analysis and advanced filtering.

**Parameters**:
- `clusterName` (required) — Name of the Kafka cluster
- `namespace` (optional) — Kubernetes namespace
- `filter` (optional) — Log level filter (e.g., "ERROR", "WARN", "INFO")
- `keywords` (optional) — List of keywords to search for in logs
- `sinceMinutes` (optional) — Time window in minutes
- `startTime` (optional) — Start time (ISO 8601 format or relative like "-1h")
- `endTime` (optional) — End time (ISO 8601 format or "now")
- `tailLines` (optional) — Number of lines to tail from each pod
- `previous` (optional) — Get logs from previous container instance

**Returns**: Aggregated logs from all Kafka pods with error analysis and statistics

**Example**:
```
Get ERROR logs from mcp-cluster for the last 60 minutes
```

## Topic Management

### list_kafka_topics

List Kafka topics for a cluster with partitions, replicas, and status. Returns paginated results.

**Parameters**:
- `clusterName` (required) — Name of the Kafka cluster
- `namespace` (optional) — Kubernetes namespace
- `limit` (optional) — Maximum number of topics to return per page (default: 100)
- `offset` (optional) — Zero-based offset for pagination (default: 0)

**Returns**: Paginated list of topics with name, partitions, replication factor, status, and pagination metadata

**Example**:
```
List all topics in mcp-cluster
```

### get_kafka_topic

Get detailed information for a specific Kafka topic including configuration, partitions, replicas, and status.

**Parameters**:
- `clusterName` (required) — Name of the Kafka cluster
- `topicName` (required) — Name of the topic
- `namespace` (optional) — Kubernetes namespace

**Returns**: Detailed topic information including configuration, partition count, replication factor, and status

**Example**:
```
Get details for my-topic in mcp-cluster
```

## Node Pool Management

### list_kafka_node_pools

List KafkaNodePools for a cluster showing roles, replicas, and storage configuration.

**Parameters**:
- `clusterName` (required) — Name of the Kafka cluster
- `namespace` (optional) — Kubernetes namespace

**Returns**: List of node pools with name, roles (broker/controller), replica counts, storage, and resource allocation

**Example**:
```
List node pools for mcp-cluster
```

### get_kafka_node_pool

Get detailed information about a specific KafkaNodePool including roles, replicas, and storage.

**Parameters**:
- `clusterName` (required) — Name of the Kafka cluster
- `nodePoolName` (required) — Name of the node pool
- `namespace` (optional) — Kubernetes namespace

**Returns**: Detailed node pool information including roles, ready replicas, storage configuration, and resource allocation

**Example**:
```
Get details for broker-pool in mcp-cluster
```

### get_kafka_node_pool_pods

Get pod information for a specific KafkaNodePool.

**Parameters**:
- `clusterName` (required) — Name of the Kafka cluster
- `nodePoolName` (required) — Name of the node pool
- `namespace` (optional) — Kubernetes namespace

**Returns**: List of pods in the node pool with status, readiness, and resource usage

**Example**:
```
Show me the pods for broker-pool
```

## Operator Management

### list_strimzi_operators

List Strimzi cluster operators with deployment status and health.

**Parameters**:
- `namespace` (optional) — Limit search to specific namespace

**Returns**: List of Strimzi operator deployments with name, namespace, status, version, and readiness

**Example**:
```
List all Strimzi operators
```

### get_strimzi_operator

Get detailed information about a specific Strimzi operator including status, version, image, and uptime.

**Parameters**:
- `operatorName` (required) — Name of the operator deployment
- `namespace` (optional) — Kubernetes namespace

**Returns**: Detailed operator information including deployment status, version, image, replicas, and uptime

**Example**:
```
Get details for strimzi-cluster-operator
```

### get_strimzi_operator_pods

Get pod information for a Strimzi operator.

**Parameters**:
- `operatorName` (optional) — Name of the operator deployment
- `namespace` (optional) — Kubernetes namespace

**Returns**: List of operator pods with status, readiness, and resource usage

**Example**:
```
Show me the operator pods
```

### get_strimzi_operator_logs

Get logs from Strimzi operator pods with error analysis and advanced filtering.

**Parameters**:
- `namespace` (optional) — Operator namespace
- `filter` (optional) — Log level filter (e.g., "ERROR", "WARN", "INFO")
- `keywords` (optional) — List of keywords to search for in logs
- `sinceMinutes` (optional) — Time window in minutes
- `startTime` (optional) — Start time (ISO 8601 format or relative like "-1h")
- `endTime` (optional) — End time (ISO 8601 format or "now")
- `tailLines` (optional) — Number of lines to tail from each pod
- `previous` (optional) — Get logs from previous container instance
- `operatorName` (optional) — Specific operator deployment name

**Returns**: Aggregated operator logs with error analysis and statistics

**Example**:
```
Get Strimzi operator logs with ERROR level for the last 30 minutes
```

## Events

### get_strimzi_events

Get Kubernetes events for a Kafka cluster and all related resources (pods, PVCs, node pools).

**Parameters**:
- `clusterName` (required) — Name of the Kafka cluster
- `namespace` (optional) — Kubernetes namespace
- `sinceMinutes` (optional) — Time window in minutes (default: 30)

**Returns**: Events grouped by resource type showing scheduling, restarts, volume issues, and operator reconciliation events

**Example**:
```
Get events for mcp-cluster in the last hour
```

## Diagnostic Tools

Composite diagnostic tools run multi-step workflows in a single tool call, using Sampling for LLM-guided triage and Elicitation for user input (e.g., namespace disambiguation).

### diagnose_kafka_cluster

Runs a multi-step diagnostic workflow for a Kafka cluster. Gathers cluster status, node pools, pods, operator logs, cluster logs, events, and metrics in a single call.

**3-Phase Workflow**:
1. **Phase 1 — Initial data gathering**: Always runs. Gathers cluster status, node pools, pods. If namespace is ambiguous and Elicitation is supported, asks the user to choose.
2. **Phase 2 — Deep investigation**: If Sampling is supported, sends Phase 1 results to LLM and asks which areas need deeper investigation. If not supported, investigates all areas.
3. **Phase 3 — Analysis**: If Sampling is supported, sends all gathered data to LLM for root cause analysis. If not supported, returns raw data without analysis.

**Parameters**:
- `clusterName` (required) — Name of the Kafka cluster
- `namespace` (optional) — Kubernetes namespace
- `symptom` (optional) — Observed symptom or issue description
- `sinceMinutes` (optional) — Time window for logs/events (default: 30)

**Uses Sampling**: Yes — LLM-guided triage and analysis
**Uses Elicitation**: Yes — Namespace disambiguation

**Example**:
```
Diagnose issues with mcp-cluster
```

### diagnose_kafka_connectivity

Runs a multi-step connectivity diagnostic workflow. Checks listener configuration, bootstrap addresses, TLS settings, pod health, and connection-related logs.

**Parameters**:
- `clusterName` (required) — Name of the Kafka cluster
- `namespace` (optional) — Kubernetes namespace
- `listenerName` (optional) — Specific listener to check

**Uses Sampling**: Yes
**Uses Elicitation**: Yes

**Example**:
```
Troubleshoot connectivity for mcp-cluster
```

### diagnose_kafka_metrics

Runs a multi-step metrics diagnostic workflow. Analyzes replication, throughput, performance, and resource metrics to identify anomalies and trends.

**Parameters**:
- `clusterName` (required) — Name of the Kafka cluster
- `namespace` (optional) — Kubernetes namespace
- `concern` (optional) — Specific concern (e.g., "replication", "performance", "resources")

**Uses Sampling**: Yes
**Uses Elicitation**: Yes

**Example**:
```
Analyze metrics for mcp-cluster
```

### diagnose_operator_metrics

Runs a multi-step operator metrics diagnostic workflow. Analyzes reconciliation, resource, and JVM metrics, correlating with operator logs.

**Parameters**:
- `namespace` (optional) — Operator namespace
- `operatorName` (optional) — Operator deployment name
- `clusterName` (optional) — Specific cluster to analyze
- `concern` (optional) — Specific concern (e.g., "reconciliation", "resources", "jvm")

**Uses Sampling**: Yes
**Uses Elicitation**: Yes

**Example**:
```
Analyze Strimzi operator metrics
```

## Metrics Tools

### get_kafka_metrics

Retrieves Prometheus metrics from Kafka cluster pods by category or explicit metric names. Returns samples with an interpretation guide for thresholds and diagnostics.

**Parameters**:
- `clusterName` (required) — Name of the Kafka cluster
- `namespace` (optional) — Kubernetes namespace
- `category` (optional) — Metric category: "replication", "throughput", "performance", "resources"
- `metricNames` (optional) — Comma-separated list of explicit metric names
- `rangeMinutes` (optional) — Range duration in minutes
- `startTime` (optional) — Absolute start time (ISO 8601 format)
- `endTime` (optional) — Absolute end time (ISO 8601 format)
- `stepSeconds` (optional) — Range query step in seconds

**Returns**: Metrics with samples, interpretation guide, and diagnostic thresholds

**Example**:
```
Get replication metrics for mcp-cluster
```

### get_kafka_exporter_metrics

Retrieves Prometheus metrics from Kafka Exporter pods by category or explicit metric names. Returns consumer group lag, topic partition offsets, and JVM metrics with interpretation guide.

**Parameters**:
- `clusterName` (required) — Name of the Kafka cluster
- `namespace` (optional) — Kubernetes namespace
- `category` (optional) — Metric category: "consumer_lag", "topic_partition", "jvm"
- `metricNames` (optional) — Comma-separated list of explicit metric names
- `rangeMinutes` (optional) — Range duration in minutes
- `startTime` (optional) — Absolute start time (ISO 8601 format)
- `endTime` (optional) — Absolute end time (ISO 8601 format)
- `stepSeconds` (optional) — Range query step in seconds

**Returns**: Kafka Exporter metrics with samples and interpretation guide

**Example**:
```
Get consumer lag metrics for mcp-cluster
```

### get_strimzi_operator_metrics

Retrieves Prometheus metrics from Strimzi operator pods by category or explicit metric names. When clusterName is provided, also includes entity operator (user-operator and topic-operator) metrics.

**Parameters**:
- `operatorName` (optional) — Operator deployment name
- `namespace` (optional) — Kubernetes namespace
- `clusterName` (optional) — Kafka cluster name for entity operator metrics
- `category` (optional) — Metric category: "reconciliation", "resources", "jvm"
- `metricNames` (optional) — Comma-separated list of explicit metric names
- `rangeMinutes` (optional) — Range duration in minutes
- `startTime` (optional) — Absolute start time (ISO 8601 format)
- `endTime` (optional) — Absolute end time (ISO 8601 format)
- `stepSeconds` (optional) — Range query step in seconds

**Returns**: Operator metrics with samples and interpretation guide

**Example**:
```
Get reconciliation metrics for the Strimzi operator
```

## Prompt Templates

Prompt templates encode Strimzi domain knowledge and guide LLMs through structured diagnostic workflows.

### diagnose-cluster-issue

Structured workflow for diagnosing Kafka cluster issues.

**Parameters**:
- `cluster_name` (required) — Name of the Kafka cluster
- `namespace` (optional) — Kubernetes namespace
- `symptom` (optional) — Observed symptom or issue

**Workflow**:
1. Check cluster status and conditions
2. Verify node pools are ready
3. Check pod health
4. Collect recent logs
5. Check for Kubernetes events
6. Analyze metrics
7. Correlate findings and identify root cause

**Usage**: Select this prompt in your AI assistant, provide the cluster name, and the AI will follow the structured diagnostic workflow.

### troubleshoot-connectivity

Structured workflow for troubleshooting connectivity issues.

**Parameters**:
- `cluster_name` (required) — Name of the Kafka cluster
- `namespace` (optional) — Kubernetes namespace
- `listener_name` (optional) — Specific listener to check

**Workflow**:
1. Check listener configuration
2. Verify bootstrap addresses
3. Check listener accessibility by type
4. Verify TLS configuration
5. Check authentication settings
6. Verify pod health
7. Collect connection-related logs

### analyze-kafka-metrics

Structured workflow for analyzing Kafka metrics.

**Parameters**:
- `cluster_name` (required) — Name of the Kafka cluster
- `namespace` (optional) — Kubernetes namespace
- `concern` (optional) — Specific concern (e.g., "replication", "performance")

**Workflow**:
1. Check cluster status and pod health
2. Query replication metrics
3. Query throughput metrics
4. Query performance metrics
5. Query resource metrics
6. Identify anomalies and trends

### analyze-strimzi-operator-metrics

Structured workflow for analyzing Strimzi operator metrics.

**Parameters**:
- `namespace` (optional) — Operator namespace
- `concern` (optional) — Specific concern (e.g., "reconciliation", "resources")

**Workflow**:
1. Get operator status
2. Query reconciliation metrics
3. Query resource metrics
4. Query JVM metrics
5. Correlate with operator logs

## Resource Templates

Resource templates expose Strimzi data as structured JSON that clients can attach to conversations.

### Kafka Cluster Status

**URI**: `strimzi://kafka.strimzi.io/namespaces/{namespace}/kafkas/{name}/status`

**Content**:
- Cluster readiness and conditions
- Kafka version
- Listener configuration
- Node pool status
- Replica counts

**Usage**: Attach this resource to provide cluster context to your AI assistant.

### Kafka Cluster Topology

**URI**: `strimzi://kafka.strimzi.io/namespaces/{namespace}/kafkas/{name}/topology`

**Content**:
- Node pools with roles
- Replica counts per pool
- Storage configuration
- Resource allocation

### KafkaNodePool Status

**URI**: `strimzi://kafka.strimzi.io/namespaces/{namespace}/kafkanodepools/{name}/status`

**Content**:
- Ready replicas
- Node roles (broker, controller, or both)
- Storage configuration

### KafkaTopic Status

**URI**: `strimzi://kafka.strimzi.io/namespaces/{namespace}/kafkatopics/{name}/status`

**Content**:
- Topic readiness
- Partition count
- Replication factor
- Configuration

### Strimzi Operator Status

**URI**: `strimzi://operator.strimzi.io/namespaces/{namespace}/clusteroperator/{name}/status`

**Content**:
- Deployment status
- Operator version
- Readiness
- Uptime

## Resource Subscriptions

The server watches Kubernetes resources and sends notifications when they change, enabling reactive LLM agents.

### Supported Resources

- **Kafka clusters** — Notifies when cluster status changes
- **KafkaNodePools** — Notifies when node pool status changes
- **Strimzi operator Deployments** — Notifies when operator status changes

### Use Cases

- Automatic issue detection
- Proactive troubleshooting
- Real-time monitoring
- Event-driven workflows

## Next Steps

- **[Usage Examples](usage-examples.md)** — See practical examples
- **[Configuration](configuration.md)** — Configure integrations
- **[Troubleshooting](troubleshooting.md)** — Resolve common issues

## Related Documentation

- [Installation](installation.md) — Deploy the server
- [Architecture](../../architecture.md) — System architecture