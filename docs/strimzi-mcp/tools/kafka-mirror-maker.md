+++
title = 'KafkaMirrorMaker2 tools'
weight = 3
+++

Tools for listing and inspecting KafkaMirrorMaker2 instances, which manage cross-cluster replication using MirrorSourceConnector, MirrorCheckpointConnector, and MirrorHeartbeatConnector.

## list_kafka_mirror_makers

List KafkaMirrorMaker2 instances with status, replicas, and source/target cluster pairs. Optionally filter by namespace.

**Parameters**:
- `namespace` (optional) -- Kubernetes namespace

**Returns**: List of MM2 instances with name, namespace, readiness, replicas, target cluster, and source cluster aliases

**Example**:
```
List all MirrorMaker2 instances
```

## get_kafka_mirror_maker

Get detailed KafkaMirrorMaker2 information including mirror configurations, cluster connections, connector statuses, and replication topic/group patterns.

**Parameters**:
- `mirrorMakerName` (required) -- Name of the KafkaMirrorMaker2
- `namespace` (optional) -- Kubernetes namespace

**Returns**: Detailed MM2 information including:
- **Status and replicas** -- readiness, expected/ready replicas
- **Cluster connections** -- source and target clusters with aliases, bootstrap servers, auth type, TLS
- **Mirror configurations** -- topics pattern, topics exclude pattern, groups pattern, groups exclude pattern, per-connector settings
- **Connector statuses** -- MirrorSourceConnector, MirrorCheckpointConnector states from CR status
- **Version and bootstrap** -- Kafka Connect version, target cluster bootstrap servers

**Example**:
```
Get details for my-mirror-maker
```

## get_kafka_mirror_maker_pods

Get pod summaries for a KafkaMirrorMaker2 instance with phase, readiness, restart counts, and age.

**Parameters**:
- `mirrorMakerName` (required) -- Name of the KafkaMirrorMaker2
- `namespace` (optional) -- Kubernetes namespace

**Returns**: Pod summaries with status, readiness, and resource information

**Example**:
```
Show pods for my-mirror-maker
```

## get_kafka_mirror_maker_logs

Get logs from KafkaMirrorMaker2 pods with error analysis. Supports filtering, time ranges, and keyword search.

**Parameters**:
- `mirrorMakerName` (required) -- Name of the KafkaMirrorMaker2
- `namespace` (optional) -- Kubernetes namespace
- `filter` (optional) -- Log filter ("errors", "warnings", or regex)
- `keywords` (optional) -- Keywords to search for
- `sinceMinutes` (optional) -- Time window in minutes
- `startTime` (optional) -- Absolute start time (ISO 8601)
- `endTime` (optional) -- Absolute end time (ISO 8601)
- `tailLines` (optional) -- Number of lines per pod
- `previous` (optional) -- Get logs from previous container instance

**Returns**: Aggregated logs with error analysis and statistics

**Example**:
```
Get error logs from my-mirror-maker for the last 30 minutes
```

## Next steps

- **[Diagnostic tools](diagnostics.md)** -- Run `diagnose_kafka_mirror_maker` for automated diagnosis
- **[Prompts, resources, and subscriptions](prompts-and-resources.md)** -- Use the `troubleshoot-mirror-maker` prompt
- **[Tools reference](.)** -- Back to tools overview
