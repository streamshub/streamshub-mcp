+++
title = 'KafkaConnect tools'
weight = 3
+++

Tools for managing KafkaConnect clusters and KafkaConnectors deployed with Strimzi.

## Connect cluster management

### list_kafka_connects

List KafkaConnect clusters with status, replicas, and connector plugin counts.

**Parameters**:
- `namespace` (optional) -- Limit search to specific namespace

**Returns**: List of KafkaConnect clusters with name, namespace, readiness, replicas, and available connector plugins

**Example**:
```
List all KafkaConnect clusters
```

### get_kafka_connect

Get detailed information about a specific KafkaConnect cluster including status, bootstrap servers, REST API URL, and available connector plugins.

**Parameters**:
- `connectName` (required) -- Name of the KafkaConnect cluster
- `namespace` (optional) -- Kubernetes namespace

**Returns**: Detailed KafkaConnect information including readiness, bootstrap servers, REST API URL, replicas, and connector plugins

**Example**:
```
Get details for my-connect-cluster
```

### get_kafka_connect_pods

Get pod summaries for a KafkaConnect cluster with phase, readiness, restarts, and age.

**Parameters**:
- `connectName` (required) -- Name of the KafkaConnect cluster
- `namespace` (optional) -- Kubernetes namespace

**Returns**: List of Connect pods with status, readiness, and resource usage

**Example**:
```
Show me the pods for my-connect-cluster
```

### get_kafka_connect_logs

Get logs from KafkaConnect pods with error analysis. Returns logs from all pods belonging to the Connect cluster.

**Parameters**:
- `connectName` (required) -- Name of the KafkaConnect cluster
- `namespace` (optional) -- Kubernetes namespace
- `filter` (optional) -- Log level filter (e.g., "errors", "warnings", or a regex pattern)
- `keywords` (optional) -- List of keywords to search for in logs
- `sinceMinutes` (optional) -- Time window in minutes
- `startTime` (optional) -- Start time (ISO 8601 format)
- `endTime` (optional) -- End time (ISO 8601 format)
- `tailLines` (optional) -- Number of lines to tail from each pod
- `previous` (optional) -- Get logs from previous container instance

**Returns**: Aggregated Connect logs with error analysis and statistics

**Example**:
```
Get error logs from my-connect-cluster for the last 30 minutes
```

## Connector management

### list_kafka_connectors

List KafkaConnectors with class, state, and task configuration. Optionally filter by namespace or parent KafkaConnect cluster.

**Parameters**:
- `namespace` (optional) -- Limit search to specific namespace
- `connectCluster` (optional) -- Filter by parent KafkaConnect cluster name

**Returns**: List of connectors with name, class_name, state, tasks_max, and parent Connect cluster

**Example**:
```
List all connectors in my-connect-cluster
```

### get_kafka_connector

Get detailed information about a specific KafkaConnector including class, state, tasks, auto-restart status, topics, and configuration.

**Parameters**:
- `connectorName` (required) -- Name of the KafkaConnector
- `namespace` (optional) -- Kubernetes namespace

**Returns**: Detailed connector information including class_name, state, tasks_max, auto-restart status, topics, config, and `offsets`

**Example**:
```
Get details for my-debezium-connector
```

#### Connector offsets

When the KafkaConnector declares `spec.listOffsets.toConfigMap`, the response carries an `offsets` object read from that ConfigMap:

| Field | Description |
|---|---|
| `config_map_name` | ConfigMap named by `spec.listOffsets.toConfigMap` |
| `available` | `true` when the operator has written the `offsets.json` key and it parsed |
| `offsets` | The parsed offsets payload (source connectors: partition/offset pairs; sink connectors: per topic-partition offsets) |
| `message` | Present only when `available` is `false` -- why the payload could not be read |

The Cluster Operator writes offsets on demand. If `available` is `false` with a "have not been written yet" message, annotate the resource and retry:

```bash
kubectl annotate kafkaconnector my-debezium-connector strimzi.io/connector-offsets=list
```

The `offsets` object is omitted entirely from `list_kafka_connectors` and from connectors that do not declare `spec.listOffsets`.

## Next steps

- **[Metrics tools](metrics.md)** -- Query KafkaConnect metrics (worker, connector, source, sink)
- **[Drain Cleaner tools](drain-cleaner.md)** -- Monitor Strimzi Drain Cleaner
- **[Diagnostic tools](diagnostics.md)** -- Run `diagnose_kafka_connect` (cluster) or `diagnose_kafka_connector` (individual)
- **[Prompts, resources, and subscriptions](prompts-and-resources.md)** -- Use `troubleshoot-connect` or `troubleshoot-connector` prompts
- **[Tools reference](.)** -- Back to tools overview
