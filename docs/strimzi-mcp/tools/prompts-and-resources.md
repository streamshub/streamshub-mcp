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
2. Check Strimzi Drain Cleaner readiness
3. Check node pool statuses
4. Check Strimzi operator and User Operator health
5. Check pod health
6. Check Kubernetes events
7. Read pod logs from unhealthy pods
8. Check cluster metrics
9. Correlate and summarize

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

### audit-security

Security audit of Kafka cluster users, ACLs, authentication, quotas, and certificates.

**Parameters**:
- `cluster_name` (required) -- Name of the Kafka cluster to audit
- `namespace` (optional) -- Kubernetes namespace

**Workflow**:
1. Check listener authentication configuration and certificate expiry
2. Enumerate all users and summarize authentication types
3. Audit each user's ACL rules for security concerns (wildcard ACLs, cluster-level access, missing auth/authz)
4. Assess overall security posture with high/medium/informational findings
5. Provide prioritized remediation recommendations

### troubleshoot-connector

Step-by-step troubleshooting of a KafkaConnector issue.

**Parameters**:
- `connector_name` (required) -- Name of the KafkaConnector to troubleshoot
- `namespace` (optional) -- Kubernetes namespace
- `connect_cluster` (optional) -- Parent KafkaConnect cluster name (auto-discovered if omitted)
- `symptom` (optional) -- Observed symptom (e.g., "connector FAILED", "tasks restarting")

**Workflow**:
1. Check connector status and conditions
2. Check parent KafkaConnect cluster health
3. Check Connect worker pods
4. Check Connect logs for connector-related errors
5. Check Kubernetes events
6. Correlate and diagnose (connector config vs platform vs external issues)

### troubleshoot-bridge

Step-by-step troubleshooting of a KafkaBridge issue.

**Parameters**:
- `bridge_name` (required) -- Name of the KafkaBridge to troubleshoot
- `namespace` (optional) -- Kubernetes namespace
- `symptom` (optional) -- Observed symptom (e.g., "bridge not ready", "HTTP 503")

**Workflow**:
1. Check KafkaBridge status and configuration
2. Check KafkaBridge pods
3. Check KafkaBridge logs for errors
4. Check Kubernetes events
5. Correlate and diagnose (bridge config, Kafka connectivity, pod/resource, HTTP/CORS, operator issues)

### troubleshoot-topic

Step-by-step troubleshooting of a KafkaTopic issue. Focuses on topic-specific diagnosis and delegates to `diagnose-cluster-issue` or `analyze-kafka-metrics` for cluster-level problems.

**Parameters**:
- `topic_name` (required) -- Name of the KafkaTopic to troubleshoot
- `cluster_name` (optional) -- Kafka cluster name (auto-discovered from topic labels if omitted)
- `namespace` (optional) -- Kubernetes namespace
- `symptom` (optional) -- Observed symptom (e.g., "NotReady", "config mismatch", "reconciliation stalled")

**Workflow**:
1. Check KafkaTopic status, conditions, and configuration
2. Determine scope -- topic-wide or cluster-wide problem
3. Quick cluster health gate (redirects to `diagnose-cluster-issue` if cluster is NotReady)
4. Check Topic Operator reconciliation logs for this specific topic
5. Check Kubernetes events
6. Diagnose and recommend (topic config, operator issues, or redirect to cluster prompts)

### analyze-capacity

Capacity analysis of a Kafka cluster.

**Parameters**:
- `cluster_name` (required) -- Name of the Kafka cluster
- `namespace` (optional) -- Kubernetes namespace
- `concern` (optional) -- Specific concern (e.g., "storage running low", "planning for traffic increase")

**Workflow**:
1. Inventory cluster topology and resource allocation per node pool
2. Check pod resource utilization and restart counts
3. Assess broker performance metrics (request handler idle, network processor idle)
4. Check JVM and resource metrics (heap usage, GC pressure)
5. Assess throughput volume and broker balance
6. Check replication health and partition distribution
7. Assess topic and partition scale
8. Check Kafka Exporter consumer lag and partition metrics
9. Produce capacity report with utilization, bottlenecks, and scaling recommendations

### assess-upgrade-readiness

Pre-upgrade readiness check for a Kafka cluster.

**Parameters**:
- `cluster_name` (required) -- Name of the Kafka cluster
- `namespace` (optional) -- Kubernetes namespace
- `target_version` (optional) -- Target Kafka or Strimzi version (e.g., "Kafka 4.2.0")

**Workflow**:
1. Check cluster health baseline (GO/NO-GO)
2. Check operator health (GO/NO-GO)
3. Check node pool and pod health (GO/NO-GO)
4. Check replication health -- critical for rolling restart safety (GO/NO-GO)
5. Check resource headroom for absorbing rolling restart load (GO/NO-GO)
6. Check Drain Cleaner readiness (GO/NO-GO)
7. Check certificate health (advisory)
8. Check Kubernetes events (advisory)
9. Produce upgrade readiness verdict (GO/NO-GO/CONDITIONAL) with pre-flight checklist

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

### KafkaUser status

**URI**: `strimzi://kafka.strimzi.io/namespaces/{namespace}/kafkausers/{name}/status`

**Content**:
- Authentication type
- ACL rules and quotas
- Kafka principal name
- Readiness and conditions
- Credential secret name (never the secret data)

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
- **KafkaUsers** -- Notifies when user ACLs, quotas, or status changes
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
