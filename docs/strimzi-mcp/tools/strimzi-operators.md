+++
title = 'Strimzi operator tools'
weight = 3
+++

Tools for managing Strimzi operators and viewing Kubernetes events for Kafka resources.

## Operator management

### list_strimzi_operators

List Strimzi cluster operators with deployment status and health.

**Parameters**:
- `namespace` (optional) -- Limit search to specific namespace

**Returns**: List of Strimzi operator deployments with name, namespace, status, version, and readiness

**Example**:
```
List all Strimzi operators
```

### get_strimzi_operator

Get detailed information about a specific Strimzi operator including status, version, image, and uptime.

**Parameters**:
- `operatorName` (required) -- Name of the operator deployment
- `namespace` (optional) -- Kubernetes namespace

**Returns**: Detailed operator information including deployment status, version, image, replicas, and uptime

**Example**:
```
Get details for strimzi-cluster-operator
```

### get_strimzi_operator_pod

Get detailed description of a Strimzi operator pod including environment, resources, volumes, and conditions.
Use `list_strimzi_operators` or `get_strimzi_operator` first to discover the pod name.

**Parameters**:
- `namespace` (required) -- Kubernetes namespace where the operator pod is deployed
- `podName` (required) -- Name of the pod (e.g., 'strimzi-cluster-operator-557fd4bbc-666r6')
- `sections` (optional) -- Detail sections to include

**Returns**: Detailed pod description with environment variables, resource limits, volumes, and conditions

**Example**:
```
Describe the operator pod strimzi-cluster-operator-557fd4bbc-666r6 in namespace strimzi
```

### get_strimzi_operator_logs

Get logs from Strimzi operator pods with error analysis and advanced filtering.

**Parameters**:
- `namespace` (optional) -- Operator namespace
- `filter` (optional) -- Log level filter (e.g., "ERROR", "WARN", "INFO")
- `keywords` (optional) -- List of keywords to search for in logs
- `sinceMinutes` (optional) -- Time window in minutes
- `startTime` (optional) -- Start time (ISO 8601 format or relative like "-1h")
- `endTime` (optional) -- End time (ISO 8601 format or "now")
- `tailLines` (optional) -- Number of lines to tail from each pod
- `previous` (optional) -- Get logs from previous container instance
- `operatorName` (optional) -- Specific operator deployment name

**Returns**: Aggregated operator logs with error analysis and statistics

**Example**:
```
Get Strimzi operator logs with ERROR level for the last 30 minutes
```

## Events

### get_strimzi_events

Get Kubernetes events for a Kafka cluster and all related resources (pods, PVCs, node pools).

**Parameters**:
- `clusterName` (required) -- Name of the Kafka cluster
- `namespace` (optional) -- Kubernetes namespace
- `sinceMinutes` (optional) -- Time window in minutes (default: 30)

**Returns**: Events grouped by resource type showing scheduling, restarts, volume issues, and operator reconciliation events

**Example**:
```
Get events for mcp-cluster in the last hour
```

## Next steps

- **[Diagnostic tools](diagnostics.md)** -- Run multi-step diagnostic workflows
- **[Tools reference](.)** -- Back to tools overview
