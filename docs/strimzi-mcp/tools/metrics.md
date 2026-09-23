+++
title = 'Metrics tools'
weight = 5
+++

Tools for retrieving and analyzing Prometheus metrics from Kafka brokers, Kafka Exporter, KafkaConnect, KafkaBridge, and Strimzi operator components.

## get_kafka_metrics

Retrieves Prometheus metrics from Kafka cluster pods by category or explicit metric names.
Returns samples with an interpretation guide for thresholds and diagnostics.

**Parameters**:
- `clusterName` (required) -- Name of the Kafka cluster
- `namespace` (optional) -- Kubernetes namespace
- `category` (optional) -- Metric category: "replication", "throughput", "performance", "resources", "kraft", "partitions"
- `metricNames` (optional) -- Comma-separated list of explicit metric names
- `rangeMinutes` (optional) -- Range duration in minutes
- `startTime` (optional) -- Absolute start time (ISO 8601 format)
- `endTime` (optional) -- Absolute end time (ISO 8601 format)
- `stepSeconds` (optional) -- Range query step in seconds
- `aggregation` (optional) -- Aggregation level: "partition" (full detail), "topic" (avg across partitions), "broker" (avg across topics+partitions), or "cluster" (single avg across all dimensions). An explicit value is clamped to the finest level the requested category supports. When omitted the default is "cluster", except for the "partitions" category, which defaults to "partition" -- its metrics are per-partition 0/1 gauges that lose their meaning when averaged.
- `requestTypes` (optional) -- Comma-separated list of Kafka request types to include (e.g., "Produce,Fetch,FindCoordinator"). Filters performance metrics that have a "request" label. Omit to include all request types.

**Returns**: Aggregated metrics with summary statistics (min, max, avg, latest), interpretation guide, and diagnostic thresholds

**Example**:
```
Get replication metrics for mcp-cluster at cluster aggregation level
```

## get_kafka_exporter_metrics

Retrieves Prometheus metrics from Kafka Exporter pods by category or explicit metric names.
Returns consumer group lag, topic partition offsets, and process metrics with interpretation guide.

The `resources` category returns Go process metrics (`process_cpu_seconds_total`, `process_resident_memory_bytes`, `process_open_fds`, `go_goroutines`) — Kafka Exporter is a Go binary and does not expose JVM metrics.

**Parameters**:
- `clusterName` (required) -- Name of the Kafka cluster
- `namespace` (optional) -- Kubernetes namespace
- `category` (optional) -- Metric category: "consumer_lag", "partitions", "resources"
- `metricNames` (optional) -- Comma-separated list of explicit metric names
- `rangeMinutes` (optional) -- Range duration in minutes
- `startTime` (optional) -- Absolute start time (ISO 8601 format)
- `endTime` (optional) -- Absolute end time (ISO 8601 format)
- `stepSeconds` (optional) -- Range query step in seconds
- `aggregation` (optional) -- Aggregation level: "partition" (full detail), "topic" (avg across partitions), "broker" (avg across topics+partitions), or "cluster" (single avg across all dimensions). An explicit value is clamped to the finest level the requested category supports. When omitted the default is "cluster", except for the "partitions" category, which defaults to "partition" -- its metrics are per-partition 0/1 gauges that lose their meaning when averaged.

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
- `aggregation` (optional) -- Aggregation level (automatically clamped to supported levels for bridge categories)

**Returns**: KafkaBridge metrics with samples and interpretation guide. The `resources` category uses Micrometer JVM metric names (`jvm_gc_pause_seconds_count`, `process_cpu_usage`, `jvm_threads_live_threads`) — the Bridge runs on Micrometer/Vert.x, not the JMX Prometheus Exporter.

**Example**:
```
Get HTTP metrics for my-bridge
```

## get_kafka_connect_metrics

Retrieves Prometheus metrics from KafkaConnect pods by category or explicit metric names.
Returns worker, connector, source, sink, and JVM metrics with interpretation guide.

**Parameters**:
- `connectName` (required) -- Name of the KafkaConnect cluster
- `namespace` (optional) -- Kubernetes namespace
- `category` (optional) -- Metric category: "worker", "connector", "source", "sink", "resources"
- `metricNames` (optional) -- Comma-separated list of explicit metric names
- `rangeMinutes` (optional) -- Range duration in minutes
- `startTime` (optional) -- Absolute start time (ISO 8601 format)
- `endTime` (optional) -- Absolute end time (ISO 8601 format)
- `stepSeconds` (optional) -- Range query step in seconds
- `aggregation` (optional) -- Aggregation level (automatically clamped to "cluster" for all KafkaConnect categories)

**Returns**: KafkaConnect metrics with samples and interpretation guide

**Example**:
```
Get worker metrics for my-connect-cluster
```

## get_strimzi_operator_metrics

Retrieves Prometheus metrics from Strimzi operator pods by category or explicit metric names.
When clusterName is provided, also includes entity operator (user-operator and topic-operator) metrics.

The `jvm` category uses Micrometer JVM metric names (`jvm_gc_pause_seconds_count`, `process_cpu_usage`, `jvm_threads_live_threads`) — the Strimzi operator runs on Micrometer, not the JMX Prometheus Exporter. The `resources` category includes `strimzi_certificate_expiration_timestamp_ms` for certificate lifecycle monitoring.

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
- `aggregation` (optional) -- Aggregation level: "partition" (full detail), "topic" (avg across partitions), "broker" (avg across topics+partitions), or "cluster" (single avg across all dimensions). An explicit value is clamped to the finest level the requested category supports. When omitted the default is "cluster", except for the "partitions" category, which defaults to "partition" -- its metrics are per-partition 0/1 gauges that lose their meaning when averaged.

**Returns**: Aggregated operator metrics with summary statistics and interpretation guide

**Example**:
```
Get reconciliation metrics for the Strimzi operator at cluster level
```

## get_cruise_control_metrics

Retrieves Prometheus metrics from Cruise Control pods by category or explicit metric names.
Returns sample collection, partition monitoring, and anomaly detection metrics with interpretation guide.

**Parameters**:
- `clusterName` (required) -- Name of the Kafka cluster
- `namespace` (optional) -- Kubernetes namespace
- `category` (optional) -- Metric category: "sampling", "anomaly"
- `metricNames` (optional) -- Comma-separated list of explicit metric names
- `rangeMinutes` (optional) -- Range duration in minutes
- `startTime` (optional) -- Absolute start time (ISO 8601 format)
- `endTime` (optional) -- Absolute end time (ISO 8601 format)
- `stepSeconds` (optional) -- Range query step in seconds
- `aggregation` (optional) -- Aggregation level (always clamped to "cluster")

**Returns**: Aggregated Cruise Control metrics with summary statistics and interpretation guide

**Example**:
```
Get Cruise Control sampling metrics for my-cluster
```

## Next steps

- **[Prompts, resources, and subscriptions](prompts-and-resources.md)** -- Prompt templates, resource templates, and subscriptions
- **[Tools reference](.)** -- Back to tools overview
