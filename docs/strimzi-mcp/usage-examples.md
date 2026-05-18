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

### KafkaConnect cluster diagnosis

**Question**: "Diagnose issues with my-connect cluster"

**What happens**:
- AI calls `diagnose_kafka_connect` diagnostic tool
- Gathers cluster status, connector inventory, pod health, logs, and events
- Uses Sampling to focus on problem areas

**Example interaction**:
```
I'll run a diagnostic workflow for the KafkaConnect cluster my-connect...

Step 1: Checking KafkaConnect status...
⚠ Cluster is NotReady
  - Conditions: NotReady — Connect worker group rebalancing

Step 2: Checking connector inventory...
Found 4 connectors:
  - debezium-source: Running (2/2 tasks)
  - jdbc-sink: Running (1/1 tasks)
  - s3-sink: Failed (0/3 tasks)
  - file-source: Paused (1/1 tasks)

Step 3: Checking pod health...
⚠ Found pod issues:
  - my-connect-connect-2: OOMKilled (3 restarts in last hour)

Step 4: Collecting Connect logs...
Found errors in my-connect-connect-2:
  - "java.lang.OutOfMemoryError: Java heap space"
  - "Worker rebalance triggered due to member leaving group"

Root Cause: Pod my-connect-connect-2 is running out of memory, causing
OOMKills and repeated worker group rebalances. The s3-sink connector
failure may be a consequence of the rebalance instability.

Recommendation: Increase memory limits for the KafkaConnect pods and
review the s3-sink connector configuration for excessive buffering.
```

### Connector troubleshooting

**Question**: "Why is my-debezium-connector failing?"

**What happens**:
- AI calls `diagnose_kafka_connector` diagnostic tool
- Checks connector status, parent KafkaConnect cluster, pod health, logs
- Uses Sampling to analyze task failures

**Example interaction**:
```
Running diagnostic workflow for KafkaConnector my-debezium-connector...

Step 1: Checking connector status...
⚠ Connector is in FAILED state
  - Type: source (Debezium PostgreSQL)
  - Tasks: 0/1 running
  - Task 0 error: "io.debezium.DebeziumException: Failed to connect to
    PostgreSQL server"

Step 2: Checking parent KafkaConnect cluster...
✓ KafkaConnect cluster my-connect is Ready
✓ All Connect pods are healthy (3/3)

Step 3: Collecting logs...
Found errors related to my-debezium-connector:
  - "Connection refused to database host: postgres.default:5432"
  - "Connector task failed, marking connector as FAILED"

Root Cause: The Debezium connector cannot reach the PostgreSQL database
at postgres.default:5432. The KafkaConnect platform itself is healthy.

Recommendation:
1. Verify the database hostname and port in the connector configuration
2. Check NetworkPolicies blocking traffic from the Connect namespace
3. Verify the PostgreSQL service is running in the default namespace
```

### Topic diagnosis

**Question**: "Diagnose issues with order-events topic"

**What happens**:
- AI calls `diagnose_kafka_topic` diagnostic tool
- Checks topic status, related topics, cluster health, operator logs, events, Kafka Exporter metrics
- Detects whether the issue is topic-specific or cluster-wide

**Example interaction**:
```
Running diagnostic workflow for KafkaTopic order-events...

Step 1: Checking topic status...
⚠ Topic is NotReady
  - Partitions: 12, Replication factor: 3
  - Condition: NotReady — "Replication factor 3 larger than available brokers: 2"

Step 2: Scope detection...
Checking related topics on cluster mcp-cluster:
  - 15 other topics are also NotReady with the same condition
  → This is a cluster-wide issue, not specific to order-events

Step 3: Checking cluster health...
⚠ Cluster mcp-cluster: 2/3 brokers ready
  - Node pool broker: 2/3 replicas ready

Step 4: Collecting operator logs...
Found relevant entries:
  - "Reconciliation of KafkaTopic order-events failed: replication factor
    3 larger than available brokers: 2"

Root Cause: Broker mcp-cluster-kafka-2 is down, reducing available
brokers below the replication factor. This affects all topics with
replication factor 3, not just order-events.

Recommendation: Investigate why the third broker is unavailable. Run
`diagnose_kafka_cluster` for a full cluster diagnosis.
```

### MirrorMaker2 diagnosis

**Question**: "Check replication health for my-mirror-maker"

**What happens**:
- AI calls `diagnose_kafka_mirror_maker` diagnostic tool
- Gathers MM2 status, connector health, pod status, logs, and events

**Example interaction**:
```
Running diagnostic workflow for KafkaMirrorMaker2 my-mirror-maker...

Step 1: Checking MM2 status...
⚠ MM2 is Ready but showing connector issues
  - Source cluster: mcp-cluster → Target cluster: dr-cluster
  - Connectors: 3 configured (MirrorSourceConnector, MirrorCheckpointConnector,
    MirrorHeartbeatConnector)

Step 2: Checking connector health...
⚠ MirrorSourceConnector: Running with warnings
  - Tasks: 4/4 running
  - Topic pattern: "order-.*|user-.*"
  - Matched topics: 8

✓ MirrorCheckpointConnector: Running (1/1 tasks)
✓ MirrorHeartbeatConnector: Running (1/1 tasks)

Step 3: Checking pod health...
✓ All MM2 pods are healthy (2/2)

Step 4: Collecting logs...
Found warnings:
  - "Consumer group offset sync lagging for group order-processor:
    source offset 15234, target offset 12890 (lag: 2344)"

Root Cause: The MirrorSourceConnector is running but consumer group
offset synchronization is lagging for the order-processor group.

Recommendation:
1. Check network latency between source and target clusters
2. Review sync.group.offsets.interval.seconds configuration
3. Monitor if the lag is growing or stabilizing
```

### Cluster comparison

**Question**: "Compare dev-cluster and prod-cluster configurations"

**What happens**:
- AI calls `compare_kafka_clusters` diagnostic tool
- Gathers broker config, resources, JVM options, listeners, and component settings for both clusters
- Uses Sampling to analyze differences by impact category

**Example interaction**:
```
Comparing configuration of dev-cluster and prod-cluster...

Gathering configurations...
✓ dev-cluster (namespace: kafka-dev): 1 broker, version 3.9.0
✓ prod-cluster (namespace: kafka-prod): 3 brokers, version 3.9.0

Configuration Differences by Impact:

CRITICAL:
- Replication factor: dev=1, prod=3
- Min in-sync replicas: dev=1, prod=2

HIGH:
- Broker count: dev=1, prod=3
- Memory limits: dev=2Gi, prod=8Gi
- CPU limits: dev=500m, prod=2000m
- Log retention hours: dev=24, prod=168

MEDIUM:
- JVM heap: dev=-Xmx1g, prod=-Xmx4g
- num.io.threads: dev=8 (default), prod=16
- num.network.threads: dev=3 (default), prod=8

LOW:
- Listeners: dev has plain only, prod has plain + tls + external

Summary: The clusters have significant differences in resilience and
capacity. The dev cluster is not suitable for production workloads due
to single-broker deployment and replication factor of 1.
```

### Upgrade readiness assessment

**Question**: "Is mcp-cluster ready to upgrade to Kafka 4.2.0?"

**What happens**:
- AI calls `assess_upgrade_readiness` diagnostic tool
- Runs pre-flight checks: cluster health, operator status, pods, replication, rebalances, certificates
- Issues GO/NO-GO/CONDITIONAL verdict

**Example interaction**:
```
Assessing upgrade readiness for mcp-cluster (target: Kafka 4.2.0)...

Pre-flight Checks:
✓ Cluster status: Ready
✓ Operator status: Ready (version 0.45.0)
✓ All pods healthy: 3/3 brokers, 3/3 controllers
✓ Under-replicated partitions: 0
✓ No active KafkaRebalance in progress

Safety Checks:
✓ CPU headroom: 55% available
✓ Memory headroom: 40% available
✓ Drain Cleaner: Ready (webhook validated)
⚠ Certificate expiry: cluster-ca expires in 12 days

Events:
✓ No warning events in the last hour

Verdict: CONDITIONAL GO

The cluster is healthy and has sufficient resource headroom for a
rolling update. However, the cluster CA certificate expires in 12 days.

Pre-upgrade checklist:
1. ⚠ Renew or rotate the cluster-ca certificate before upgrading
2. ✓ Ensure a maintenance window of approximately 30-45 minutes
3. ✓ Verify client applications handle broker restarts gracefully
4. ✓ Take a backup of the Kafka custom resource
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

