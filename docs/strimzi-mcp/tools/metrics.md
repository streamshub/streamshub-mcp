+++
title = 'Metrics tools'
weight = 5
+++

Tools for retrieving and analyzing Prometheus metrics from Kafka brokers, Kafka Exporter, KafkaBridge, and Strimzi operator components.

## get_kafka_metrics

Retrieves Prometheus metrics from Kafka cluster pods by category or explicit metric names.
Returns samples with an interpretation guide for thresholds and diagnostics.

**Parameters**:
- `clusterName` (required) -- Name of the Kafka cluster
- `namespace` (optional) -- Kubernetes namespace
- `category` (optional) -- Metric category: "replication", "throughput", "performance", "resources"
- `metricNames` (optional) -- Comma-separated list of explicit metric names
- `rangeMinutes` (optional) -- Range duration in minutes
- `startTime` (optional) -- Absolute start time (ISO 8601 format)
- `endTime` (optional) -- Absolute end time (ISO 8601 format)
- `stepSeconds` (optional) -- Range query step in seconds
- `aggregation` (optional) -- Aggregation level: "partition" (full detail), "topic" (avg across partitions), "broker" (avg across topics+partitions, default), or "cluster" (single avg across all dimensions). Automatically clamped to the finest level supported by the requested category.
- `requestTypes` (optional) -- Comma-separated list of Kafka request types to include (e.g., "Produce,Fetch,FindCoordinator"). Filters performance metrics that have a "request" label. Omit to include all request types.

**Returns**: Aggregated metrics with summary statistics (min, max, avg, latest), interpretation guide, and diagnostic thresholds

**Example**:
```
Get replication metrics for mcp-cluster at cluster aggregation level
```

## get_kafka_exporter_metrics

Retrieves Prometheus metrics from Kafka Exporter pods by category or explicit metric names.
Returns consumer group lag, topic partition offsets, and JVM metrics with interpretation guide.

**Parameters**:
- `clusterName` (required) -- Name of the Kafka cluster
- `namespace` (optional) -- Kubernetes namespace
- `category` (optional) -- Metric category: "consumer_lag", "topic_partition", "jvm"
- `metricNames` (optional) -- Comma-separated list of explicit metric names
- `rangeMinutes` (optional) -- Range duration in minutes
- `startTime` (optional) -- Absolute start time (ISO 8601 format)
- `endTime` (optional) -- Absolute end time (ISO 8601 format)
- `stepSeconds` (optional) -- Range query step in seconds
- `aggregation` (optional) -- Aggregation level: "partition" (full detail), "topic" (avg across partitions), "broker" (avg across topics+partitions, default), or "cluster" (single avg across all dimensions). Automatically clamped to the finest level supported by the requested category.

**Returns**: Aggregated Kafka Exporter metrics with summary statistics and interpretation guide

**Example**:
```
Get consumer lag metrics for mcp-cluster at topic level
```

## get_kafka_bridge_metrics

Retrieves Prometheus metrics from KafkaBridge pods by category or explicit metric names.
Returns HTTP request, producer, consumer, and JVM metrics with interpretation guide.

**Parameters**:
- `bridgeName` (required) -- Name of the KafkaBridge
- `namespace` (optional) -- Kubernetes namespace
- `category` (optional) -- Metric category: "http", "producer", "consumer", "resources"
- `metricNames` (optional) -- Comma-separated list of explicit metric names
- `rangeMinutes` (optional) -- Range duration in minutes
- `startTime` (optional) -- Absolute start time (ISO 8601 format)
- `endTime` (optional) -- Absolute end time (ISO 8601 format)
- `stepSeconds` (optional) -- Range query step in seconds

**Returns**: KafkaBridge metrics with samples and interpretation guide

**Example**:
```
Get HTTP metrics for my-bridge
```

## get_strimzi_operator_metrics

Retrieves Prometheus metrics from Strimzi operator pods by category or explicit metric names.
When clusterName is provided, also includes entity operator (user-operator and topic-operator) metrics.

**Parameters**:
- `operatorName` (optional) -- Operator deployment name
- `namespace` (optional) -- Kubernetes namespace
- `clusterName` (optional) -- Kafka cluster name for entity operator metrics
- `category` (optional) -- Metric category: "reconciliation", "resources", "jvm"
- `metricNames` (optional) -- Comma-separated list of explicit metric names
- `rangeMinutes` (optional) -- Range duration in minutes
- `startTime` (optional) -- Absolute start time (ISO 8601 format)
- `endTime` (optional) -- Absolute end time (ISO 8601 format)
- `stepSeconds` (optional) -- Range query step in seconds
- `aggregation` (optional) -- Aggregation level: "partition" (full detail), "topic" (avg across partitions), "broker" (avg across topics+partitions, default), or "cluster" (single avg across all dimensions). Automatically clamped to the finest level supported by the requested category.

**Returns**: Aggregated operator metrics with summary statistics and interpretation guide

**Example**:
```
Get reconciliation metrics for the Strimzi operator at cluster level
```

## Next steps

- **[Prompts, resources, and subscriptions](prompts-and-resources.md)** -- Prompt templates, resource templates, and subscriptions
- **[Tools reference](.)** -- Back to tools overview
