+++
title = 'Usage Examples'
weight = 4
+++

Practical examples of using the Strimzi MCP Server with AI assistants to manage and troubleshoot Kafka infrastructure.

## Basic cluster operations

### Listing clusters

**Question**: "What Kafka clusters are running?"

**What happens**:
- AI calls `list_kafka_clusters` tool
- Returns all Kafka clusters across namespaces
- Shows cluster names, namespaces, and ready status

**Example response**:
```
I found 2 Kafka clusters:

1. mcp-cluster (namespace: strimzi-kafka)
   - Status: Ready
   - Version: 3.9.0
   - Nodes: 3 brokers

2. dev-cluster (namespace: kafka-dev)
   - Status: Ready
   - Version: 3.8.1
   - Nodes: 1 broker
```

### Checking cluster status

**Question**: "What's the status of mcp-cluster?"

**What happens**:
- AI calls `get_kafka_cluster_status` with cluster name
- Returns detailed status including conditions, version, listeners

**Example response**:
```
The mcp-cluster is Ready and healthy:

Status: Ready
Version: 3.9.0
Kafka Version: 3.9.0

Conditions:
✓ Ready: True - Kafka cluster is ready
✓ NotReady: False

Listeners:
- plain (port 9092): Internal plaintext
- tls (port 9093): Internal TLS
- external (port 9094): LoadBalancer

Node Pools:
- broker: 3/3 replicas ready (broker role)
```

### Viewing cluster topology

**Question**: "Show me the topology of mcp-cluster"

**What happens**:
- AI calls `get_kafka_cluster_topology`
- Returns node pool configuration and roles

**Example response**:
```
Cluster Topology for mcp-cluster:

Node Pool: broker
- Role: broker
- Replicas: 3 (all ready)
- Storage: 100Gi persistent volume per replica
- Resources: 2 CPU, 4Gi memory per pod

Node Pool: controller
- Role: controller
- Replicas: 3 (all ready)
- Storage: 10Gi persistent volume per replica
- Resources: 1 CPU, 2Gi memory per pod
```

## Diagnostic workflows

### Comprehensive cluster diagnosis

**Question**: "Diagnose issues with mcp-cluster"

**What happens**:
- AI calls `diagnose_kafka_cluster` diagnostic tool
- Runs multi-step workflow checking status, pods, logs, events, metrics
- Uses Sampling to focus on problem areas

**Example interaction**:
```
I'll run a comprehensive diagnosis of mcp-cluster...

Step 1: Checking cluster status...
✓ Cluster is Ready

Step 2: Checking node pools...
✓ All node pools are ready (3/3 brokers, 3/3 controllers)

Step 3: Checking pod health...
⚠ Found 1 pod with issues:
  - mcp-cluster-kafka-2: CrashLoopBackOff

Step 4: Collecting logs from problematic pods...
Found errors in mcp-cluster-kafka-2:
  - "java.io.IOException: No space left on device"
  - Repeated 15 times in the last 10 minutes

Step 5: Checking Kubernetes events...
Warning events:
  - Pod mcp-cluster-kafka-2: Back-off restarting failed container

Step 6: Analyzing metrics...
Disk usage: 98% on mcp-cluster-kafka-2

Root Cause: Pod mcp-cluster-kafka-2 has run out of disk space, causing 
the Kafka broker to crash. The pod is in CrashLoopBackOff.

Recommendation: Increase the persistent volume size or clean up old log 
segments. Check retention policies.
```

### Connectivity troubleshooting

**Question**: "Why can I not connect to mcp-cluster from outside the cluster?"

**What happens**:
- AI calls `diagnose_kafka_connectivity`
- Checks listeners, bootstrap addresses, TLS configuration
- Verifies external access configuration

**Example interaction**:
```
Let me check the connectivity configuration for mcp-cluster...

Listeners configured:
1. plain (9092) - Internal only
2. tls (9093) - Internal only
3. external (9094) - LoadBalancer

External listener status:
✓ LoadBalancer provisioned
✓ External IP assigned: 203.0.113.45
✓ Bootstrap address: 203.0.113.45:9094

TLS Configuration:
✓ Certificate valid
✓ Certificate issuer: cluster-ca

Testing connectivity:
✓ Internal connectivity: OK
✓ External LoadBalancer: OK
✓ DNS resolution: OK

The cluster is properly configured for external access. You can connect using:
- Bootstrap server: 203.0.113.45:9094
- Security protocol: SSL
- Required: Client certificate for authentication

If you still cannot connect, check:
1. Firewall rules allowing port 9094
2. Client has the correct CA certificate
3. Client certificate is properly configured
```

### Metrics analysis

**Question**: "Are there any performance issues with mcp-cluster?"

**What happens**:
- AI calls `diagnose_kafka_metrics`
- Queries key performance metrics
- Identifies anomalies and trends

**Example interaction**:
```
Analyzing performance metrics for mcp-cluster...

Replication Health:
✓ Under-replicated partitions: 0
✓ Offline partitions: 0
✓ Leader election rate: Normal (0.1/sec)

Throughput:
✓ Messages in: 1,250/sec (normal)
✓ Bytes in: 2.5 MB/sec (normal)
✓ Messages out: 1,250/sec (normal)
✓ Bytes out: 2.5 MB/sec (normal)

Performance:
⚠ Request queue time: 85ms (elevated)
✓ Response queue time: 12ms (normal)
✓ Network processor idle: 75% (healthy)

Resource Usage:
✓ CPU: 45% average
⚠ Memory: 85% average (approaching limit)
✓ Disk I/O: Normal

Findings:
The cluster is healthy overall, but showing signs of memory pressure:
- Request queue time is elevated (85ms vs typical 20-30ms)
- Memory usage is at 85% across brokers

Recommendations:
1. Consider increasing broker memory allocation
2. Review producer batch sizes and compression
3. Monitor for memory-related GC pauses
```

## Log analysis

### Finding errors

**Question**: "Show me errors from mcp-cluster in the last hour"

**What happens**:
- AI calls `collect_kafka_logs` with time filter
- Filters for ERROR level messages
- Summarizes findings

**Example response**:
```
Found 23 ERROR messages in the last hour from mcp-cluster:

Most common errors:

1. "Connection to node -1 could not be established" (15 occurrences)
   - Pods: mcp-cluster-kafka-0, mcp-cluster-kafka-1
   - Likely cause: Network connectivity issue or broker restart

2. "Not enough replicas available" (5 occurrences)
   - Pod: mcp-cluster-kafka-2
   - Topic: user-events
   - Likely cause: Replication lag or broker unavailability

3. "Request timed out" (3 occurrences)
   - Pod: mcp-cluster-kafka-1
   - Likely cause: High load or slow disk I/O

Recent error from mcp-cluster-kafka-2:
[2024-01-15 14:23:45] ERROR Not enough replicas available for topic 
user-events partition 5. Current ISR: [0,1], required: 3
```

### Operator logs

**Question**: "What's the Strimzi operator doing?"

**What happens**:
- AI calls `collect_operator_logs`
- Shows recent operator activity

**Example response**:
```
Recent Strimzi operator activity:

[2024-01-15 14:30:12] INFO Reconciling Kafka cluster mcp-cluster
[2024-01-15 14:30:15] INFO Kafka cluster mcp-cluster is Ready
[2024-01-15 14:30:20] INFO Reconciling KafkaTopic user-events
[2024-01-15 14:30:22] INFO KafkaTopic user-events is Ready
[2024-01-15 14:30:25] INFO Reconciling KafkaNodePool broker
[2024-01-15 14:30:27] INFO KafkaNodePool broker is Ready

The operator is functioning normally, completing reconciliation cycles 
every 2-3 seconds. No errors or warnings in the last hour.
```

## Topic management

### Listing topics

**Question**: "What topics exist in mcp-cluster?"

**What happens**:
- AI calls `list_kafka_topics`
- Returns topic names and basic info

**Example response**:
```
Found 5 topics in mcp-cluster:

1. user-events
   - Partitions: 12
   - Replication factor: 3
   - Status: Ready

2. order-processing
   - Partitions: 6
   - Replication factor: 3
   - Status: Ready

3. analytics-stream
   - Partitions: 24
   - Replication factor: 2
   - Status: Ready

4. __consumer_offsets (internal)
   - Partitions: 50
   - Replication factor: 3

5. __transaction_state (internal)
   - Partitions: 50
   - Replication factor: 3
```

### Topic status

**Question**: "What's the status of the user-events topic?"

**What happens**:
- AI calls `get_kafka_topic_status`
- Returns detailed topic configuration and status

**Example response**:
```
Topic: user-events

Status: Ready
Partitions: 12
Replication Factor: 3
Min In-Sync Replicas: 2

Configuration:
- Retention: 7 days
- Compression: snappy
- Cleanup policy: delete
- Segment size: 1GB

All partitions are healthy with sufficient replicas.
```

## Metrics queries

### Specific metrics

**Question**: "What's the current message rate for mcp-cluster?"

**What happens**:
- AI calls `query_kafka_metrics` with specific metric
- Returns current values

**Example response**:
```
Current message rates for mcp-cluster:

Messages In:
- mcp-cluster-kafka-0: 425 msg/sec
- mcp-cluster-kafka-1: 398 msg/sec
- mcp-cluster-kafka-2: 427 msg/sec
- Total: 1,250 msg/sec

Messages Out:
- mcp-cluster-kafka-0: 420 msg/sec
- mcp-cluster-kafka-1: 405 msg/sec
- mcp-cluster-kafka-2: 425 msg/sec
- Total: 1,250 msg/sec

The cluster is processing approximately 1,250 messages per second 
with balanced load across all brokers.
```

### Replication metrics

**Question**: "Are there any under-replicated partitions?"

**What happens**:
- AI queries replication metrics
- Checks for issues

**Example response**:
```
Checking replication status for mcp-cluster...

Under-replicated Partitions: 0
Offline Partitions: 0
Preferred Replica Imbalance: 2%

All partitions are fully replicated and healthy. The cluster has good 
replication balance with only 2% imbalance in preferred replica 
distribution.
```

## Operator monitoring

### Operator status

**Question**: "Is the Strimzi operator healthy?"

**What happens**:
- AI calls `get_strimzi_operator_status`
- Checks operator deployment and metrics

**Example response**:
```
Strimzi Operator Status:

Deployment: strimzi-cluster-operator
Namespace: strimzi
Status: Ready
Replicas: 1/1 ready
Version: 0.51.0
Uptime: 5 days, 3 hours

Recent Activity:
- Reconciliations: 1,247 in the last hour
- Average reconciliation time: 2.3 seconds
- Failed reconciliations: 0

Resource Usage:
- CPU: 0.15 cores (15% of limit)
- Memory: 512 MB (50% of limit)

The operator is healthy and functioning normally.
```

## Best practices

### Effective questions

**Good**: "What's the status of mcp-cluster and are there any issues?"
- Specific cluster name
- Clear intent
- Allows AI to choose appropriate tools

**Better**: "Diagnose issues with mcp-cluster, focusing on recent errors"
- Specific cluster and scope
- Guides the diagnostic approach
- More efficient tool usage

### Iterative investigation

Start broad, then narrow down:
1. "What clusters are running?" → Get overview
2. "What's the status of mcp-cluster?" → Check specific cluster
3. "Show me errors from mcp-cluster" → Investigate issues
4. "What's causing the connection errors?" → Root cause analysis

### Using context

Attach resources for faster responses:
1. Attach cluster status resource
2. Ask: "Based on this status, what should I check?"
3. AI can analyze without additional tool calls

## Using with different AI assistants

### Claude Desktop

Natural conversation style:
- "What's wrong with my Kafka cluster?"
- "Show me what's happening in the last hour"
- "Help me troubleshoot connectivity issues"

### Claude Code (VS Code)

Code-focused queries:
- "Generate a KafkaTopic manifest for a high-throughput topic"
- "What configuration should I use for this use case?"
- "Review this Kafka configuration"

### Programmatic access

Direct tool calls via MCP protocol:
```json
{
  "method": "tools/call",
  "params": {
    "name": "get_kafka_cluster_status",
    "arguments": {
      "clusterName": "mcp-cluster",
      "namespace": "strimzi-kafka"
    }
  }
}
```

## Next steps

- **[Tools reference](tools/)** — Complete tool catalog
- **[Troubleshooting](troubleshooting.md)** — Resolve common issues
- **[Configuration](configuration.md)** — Configure integrations

