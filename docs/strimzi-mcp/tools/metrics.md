+++
title = 'Metrics tools'
weight = 5
+++

Tools for retrieving and analyzing Prometheus metrics from Kafka brokers, Kafka Exporter, and Strimzi operator components.

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

**Returns**: Metrics with samples, interpretation guide, and diagnostic thresholds

**Example**:
```
Get replication metrics for mcp-cluster
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

**Returns**: Kafka Exporter metrics with samples and interpretation guide

**Example**:
```
Get consumer lag metrics for mcp-cluster
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

**Returns**: Operator metrics with samples and interpretation guide

**Example**:
```
Get reconciliation metrics for the Strimzi operator
```

## Next steps

- **[Prompts, resources, and subscriptions](prompts-and-resources.md)** -- Prompt templates, resource templates, and subscriptions
- **[Tools reference](.)** -- Back to tools overview
