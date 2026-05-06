+++
title = 'Diagnostic tools'
weight = 4
+++

Composite diagnostic tools run multi-step workflows in a single tool call, using Sampling for LLM-guided triage and Elicitation for user input (e.g., namespace disambiguation).

## diagnose_kafka_cluster

Runs a multi-step diagnostic workflow for a Kafka cluster.
Gathers cluster status, node pools, pods, Drain Cleaner readiness, operator logs, cluster logs, events, metrics, and Drain Cleaner logs in a single call.

**3-Phase workflow**:
1. **Phase 1 -- Initial data gathering**: Always runs.
Gathers cluster status, node pools, pods.
If namespace is ambiguous and Elicitation is supported, asks the user to choose.
2. **Phase 2 -- Deep investigation**: If Sampling is supported, sends Phase 1 results to LLM and asks which areas need deeper investigation.
If not supported, investigates all areas.
3. **Phase 3 -- Analysis**: If Sampling is supported, sends all gathered data to LLM for root cause analysis.
If not supported, returns raw data without analysis.

**Parameters**:
- `clusterName` (required) -- Name of the Kafka cluster
- `namespace` (optional) -- Kubernetes namespace
- `symptom` (optional) -- Observed symptom or issue description
- `sinceMinutes` (optional) -- Time window for logs/events (default: 30)

**Uses Sampling**: Yes -- LLM-guided triage and analysis
**Uses Elicitation**: Yes -- Namespace disambiguation

**Example**:
```
Diagnose issues with mcp-cluster
```

## diagnose_kafka_connectivity

Runs a multi-step connectivity diagnostic workflow.
Checks listener configuration, bootstrap addresses, TLS settings, pod health, and connection-related logs.

**Parameters**:
- `clusterName` (required) -- Name of the Kafka cluster
- `namespace` (optional) -- Kubernetes namespace
- `listenerName` (optional) -- Specific listener to check

**Uses Sampling**: Yes
**Uses Elicitation**: Yes

**Example**:
```
Troubleshoot connectivity for mcp-cluster
```

## diagnose_kafka_metrics

Runs a multi-step metrics diagnostic workflow.
Analyzes replication, throughput, performance, and resource metrics to identify anomalies and trends.

**Parameters**:
- `clusterName` (required) -- Name of the Kafka cluster
- `namespace` (optional) -- Kubernetes namespace
- `concern` (optional) -- Specific concern (e.g., "replication", "performance", "resources")

**Uses Sampling**: Yes
**Uses Elicitation**: Yes

**Example**:
```
Analyze metrics for mcp-cluster
```

## diagnose_operator_metrics

Runs a multi-step operator metrics diagnostic workflow.
Analyzes reconciliation, resource, and JVM metrics, correlating with operator logs.

**Parameters**:
- `namespace` (optional) -- Operator namespace
- `operatorName` (optional) -- Operator deployment name
- `clusterName` (optional) -- Specific cluster to analyze
- `concern` (optional) -- Specific concern (e.g., "reconciliation", "resources", "jvm")

**Uses Sampling**: Yes
**Uses Elicitation**: Yes

**Example**:
```
Analyze Strimzi operator metrics
```

## diagnose_kafka_connector

Runs a multi-step diagnostic workflow for a KafkaConnector.
Gathers connector status, parent KafkaConnect cluster health, Connect pod status, logs, and events in a single call.

**Parameters**:
- `connectorName` (required) -- Name of the KafkaConnector
- `namespace` (optional) -- Kubernetes namespace
- `symptom` (optional) -- Observed symptom or issue description
- `sinceMinutes` (optional) -- Time window for logs/events (default: 30)

**Uses Sampling**: Yes -- LLM-guided triage and analysis
**Uses Elicitation**: Yes -- Namespace disambiguation

**Example**:
```
Diagnose issues with my-debezium-connector
```

## diagnose_kafka_topic

Runs a multi-step diagnostic workflow for a KafkaTopic.
Gathers topic status, related topics (scope detection), cluster health, operator logs, events, and Kafka Exporter metrics in a single call.

**3-Phase workflow**:
1. **Phase 1 -- Initial data gathering**: Topic status, related topics for scope detection (isolated vs cluster-wide), parent cluster health gate.
2. **Phase 2 -- Deep investigation**: Operator logs (filtered for topic name), Kubernetes events, Kafka Exporter partition metrics.
3. **Phase 3 -- Analysis**: Root cause analysis distinguishing topic config issues, Topic Operator issues, and cluster-wide problems.

**Parameters**:
- `topicName` (required) -- Name of the KafkaTopic
- `clusterName` (optional) -- Kafka cluster name (auto-discovered from topic labels)
- `namespace` (optional) -- Kubernetes namespace
- `symptom` (optional) -- Observed symptom or issue description

**Uses Sampling**: Yes -- LLM-guided triage and analysis
**Uses Elicitation**: Yes -- Namespace disambiguation

**Example**:
```
Diagnose issues with my-topic on cluster my-cluster
```

## assess_upgrade_readiness

Assesses whether a Kafka cluster is ready for a Strimzi or Kafka version upgrade.
Checks cluster health, operator status, pod health, replication, resource headroom, Drain Cleaner, and certificates.

**3-Phase workflow**:
1. **Phase 1 -- Pre-flight checks**: Cluster health, operator status, node pools and pods, replication metrics.
2. **Phase 2 -- Safety checks**: Performance metrics (headroom), resource metrics, Drain Cleaner readiness, certificate expiry, events.
3. **Phase 3 -- Verdict**: GO/NO-GO/CONDITIONAL verdict with pre-flight checklist and maintenance window estimate.

**Parameters**:
- `clusterName` (required) -- Name of the Kafka cluster
- `namespace` (optional) -- Kubernetes namespace
- `targetVersion` (optional) -- Target Kafka or Strimzi version (e.g., "Kafka 4.2.0")

**Uses Sampling**: Yes -- GO/NO-GO verdict generation
**Uses Elicitation**: Yes -- Namespace disambiguation

**Example**:
```
Check if my-cluster is ready for upgrade to Kafka 4.2.0
```

## compare_kafka_clusters

Compares the effective configuration of two Kafka clusters.
Gathers broker config, resources, JVM options, listeners, and component settings for both clusters.

**Parameters**:
- `clusterName1` (required) -- Name of the first Kafka cluster
- `namespace1` (optional) -- Namespace for the first cluster
- `clusterName2` (required) -- Name of the second Kafka cluster
- `namespace2` (optional) -- Namespace for the second cluster

**Uses Sampling**: Yes -- Analyzes differences by impact category (CRITICAL/HIGH/MEDIUM/LOW)
**Uses Elicitation**: Yes -- Namespace disambiguation for each cluster

**Example**:
```
Compare the configuration of cluster-dev and cluster-prod
```

## Next steps

- **[Metrics tools](metrics.md)** -- Retrieve and analyze Prometheus metrics
- **[Tools reference](.)** -- Back to tools overview
