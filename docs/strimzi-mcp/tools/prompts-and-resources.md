+++
title = 'Prompts, resources, and subscriptions'
weight = 6
+++

Prompt templates, resource templates, and resource subscriptions provided by the Strimzi MCP Server.

## Prompt templates

Prompt templates encode Strimzi domain knowledge and guide LLMs through structured diagnostic workflows.

### diagnose-cluster-issue

Structured workflow for diagnosing Kafka cluster issues.

**Parameters**:
- `cluster_name` (required) -- Name of the Kafka cluster
- `namespace` (optional) -- Kubernetes namespace
- `symptom` (optional) -- Observed symptom or issue

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
- `cluster_name` (required) -- Name of the Kafka cluster
- `namespace` (optional) -- Kubernetes namespace
- `listener_name` (optional) -- Specific listener to check

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
- `cluster_name` (required) -- Name of the Kafka cluster
- `namespace` (optional) -- Kubernetes namespace
- `concern` (optional) -- Specific concern (e.g., "replication", "performance")

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
- `namespace` (optional) -- Operator namespace
- `concern` (optional) -- Specific concern (e.g., "reconciliation", "resources")

**Workflow**:
1. Get operator status
2. Query reconciliation metrics
3. Query resource metrics
4. Query JVM metrics
5. Correlate with operator logs

### compare-cluster-configs

Structured workflow for comparing two Kafka cluster configurations.

**Parameters**:
- `cluster_name_1` (required) -- Name of the first cluster
- `cluster_name_2` (required) -- Name of the second cluster
- `namespace_1` (optional) -- Namespace for the first cluster
- `namespace_2` (optional) -- Namespace for the second cluster

**Workflow**:
1. Get configuration for cluster 1
2. Get configuration for cluster 2
3. Compare broker configuration
4. Compare resources and JVM options
5. Compare listeners and authorization
6. Compare component settings (Entity Operator, Cruise Control, Kafka Exporter)
7. Compare metrics and logging
8. Summarize differences by impact (CRITICAL/HIGH/MEDIUM/LOW)

## Resource templates

Resource templates expose Strimzi data as structured JSON that clients can attach to conversations.

### Kafka cluster status

**URI**: `strimzi://kafka.strimzi.io/namespaces/{namespace}/kafkas/{name}/status`

**Content**:
- Cluster readiness and conditions
- Kafka version
- Listener configuration
- Node pool status
- Replica counts

**Usage**: Attach this resource to provide cluster context to your AI assistant.

### Kafka cluster topology

**URI**: `strimzi://kafka.strimzi.io/namespaces/{namespace}/kafkas/{name}/topology`

**Content**:
- Node pools with roles
- Replica counts per pool
- Storage configuration
- Resource allocation

### KafkaNodePool status

**URI**: `strimzi://kafka.strimzi.io/namespaces/{namespace}/kafkanodepools/{name}/status`

**Content**:
- Ready replicas
- Node roles (broker, controller, or both)
- Storage configuration

### KafkaTopic status

**URI**: `strimzi://kafka.strimzi.io/namespaces/{namespace}/kafkatopics/{name}/status`

**Content**:
- Topic readiness
- Partition count
- Replication factor
- Configuration

### Strimzi operator status

**URI**: `strimzi://operator.strimzi.io/namespaces/{namespace}/clusteroperator/{name}/status`

**Content**:
- Deployment status
- Operator version
- Readiness
- Uptime

## Resource subscriptions

The server watches Kubernetes resources and sends notifications when they change, enabling reactive LLM agents.

### Supported resources

- **Kafka clusters** -- Notifies when cluster status changes
- **KafkaNodePools** -- Notifies when node pool status changes
- **Strimzi operator Deployments** -- Notifies when operator status changes

### Use cases

- Automatic issue detection
- Proactive troubleshooting
- Real-time monitoring
- Event-driven workflows

## Next steps

- **[Usage Examples](../usage-examples.md)** -- See practical examples
- **[Configuration](../configuration.md)** -- Configure integrations
- **[Troubleshooting](../troubleshooting.md)** -- Resolve common issues
