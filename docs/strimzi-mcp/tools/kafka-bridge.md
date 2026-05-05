+++
title = 'KafkaBridge tools'
weight = 4
+++

Tools for managing KafkaBridge HTTP REST API bridges deployed with Strimzi.

## Bridge management

### list_kafka_bridges

List KafkaBridge resources with status, replicas, bootstrap servers, and HTTP URL.

**Parameters**:
- `namespace` (optional) -- Limit search to specific namespace

**Returns**: List of KafkaBridge resources with name, namespace, status, replicas, bootstrap servers, and HTTP URL

**Example**:
```
List all KafkaBridge resources
```

### get_kafka_bridge

Get detailed information about a specific KafkaBridge including status, HTTP configuration, CORS, producer/consumer config, authentication, and TLS.

**Parameters**:
- `bridgeName` (required) -- Name of the KafkaBridge
- `namespace` (optional) -- Kubernetes namespace

**Returns**: Detailed KafkaBridge information including status, HTTP port, CORS configuration, producer/consumer/admin client config, authentication type, TLS, and logging

**Example**:
```
Get details for my-bridge
```

### get_kafka_bridge_pods

Get pod summaries for a KafkaBridge with phase, readiness, restarts, and age.

**Parameters**:
- `bridgeName` (required) -- Name of the KafkaBridge
- `namespace` (optional) -- Kubernetes namespace

**Returns**: List of bridge pods with status, readiness, and resource usage

**Example**:
```
Show me the pods for my-bridge
```

### get_kafka_bridge_logs

Get logs from KafkaBridge pods with error analysis. Returns logs from all pods belonging to the bridge.

**Parameters**:
- `bridgeName` (required) -- Name of the KafkaBridge
- `namespace` (optional) -- Kubernetes namespace
- `filter` (optional) -- Log level filter (e.g., "errors", "warnings", or a regex pattern)
- `keywords` (optional) -- List of keywords to search for in logs
- `sinceMinutes` (optional) -- Time window in minutes
- `startTime` (optional) -- Start time (ISO 8601 format)
- `endTime` (optional) -- End time (ISO 8601 format)
- `tailLines` (optional) -- Number of lines to tail from each pod
- `previous` (optional) -- Get logs from previous container instance

**Returns**: Aggregated bridge logs with error analysis and statistics

**Example**:
```
Get error logs from my-bridge for the last 30 minutes
```

## Next steps

- **[Metrics tools](metrics.md)** -- Retrieve KafkaBridge metrics including HTTP request, producer, and consumer metrics
- **[KafkaConnect tools](kafka-connect.md)** -- Manage KafkaConnect clusters and connectors
- **[Diagnostic tools](diagnostics.md)** -- Run multi-step diagnostic workflows
- **[Tools reference](.)** -- Back to tools overview
