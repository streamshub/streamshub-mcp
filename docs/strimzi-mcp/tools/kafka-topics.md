+++
title = 'Kafka topic and node pool tools'
weight = 2
+++

Tools for listing and inspecting Kafka topics and KafkaNodePool resources.

## Topic management

### list_kafka_topics

List Kafka topics for a cluster with partitions, replicas, and status.
Returns paginated results.

**Parameters**:
- `clusterName` (required) -- Name of the Kafka cluster
- `namespace` (optional) -- Kubernetes namespace
- `limit` (optional) -- Maximum number of topics to return per page (default: 100)
- `offset` (optional) -- Zero-based offset for pagination (default: 0)

**Returns**: Paginated list of topics with name, partitions, replication factor, status, and pagination metadata

**Example**:
```
List all topics in mcp-cluster
```

### get_kafka_topic

Get detailed information for a specific Kafka topic including configuration, partitions, replicas, and status.

**Parameters**:
- `clusterName` (required) -- Name of the Kafka cluster
- `topicName` (required) -- Name of the topic
- `namespace` (optional) -- Kubernetes namespace

**Returns**: Detailed topic information including configuration, partition count, replication factor, and status

**Example**:
```
Get details for my-topic in mcp-cluster
```

## Node pool management

### list_kafka_node_pools

List KafkaNodePools for a cluster showing roles, replicas, and storage configuration.

**Parameters**:
- `clusterName` (required) -- Name of the Kafka cluster
- `namespace` (optional) -- Kubernetes namespace

**Returns**: List of node pools with name, roles (broker/controller), replica counts, storage, and resource allocation

**Example**:
```
List node pools for mcp-cluster
```

### get_kafka_node_pool

Get detailed information about a specific KafkaNodePool including roles, replicas, and storage.

**Parameters**:
- `clusterName` (required) -- Name of the Kafka cluster
- `nodePoolName` (required) -- Name of the node pool
- `namespace` (optional) -- Kubernetes namespace

**Returns**: Detailed node pool information including roles, ready replicas, storage configuration, and resource allocation

**Example**:
```
Get details for broker-pool in mcp-cluster
```

### get_kafka_node_pool_pods

Get pod information for a specific KafkaNodePool.

**Parameters**:
- `clusterName` (required) -- Name of the Kafka cluster
- `nodePoolName` (required) -- Name of the node pool
- `namespace` (optional) -- Kubernetes namespace

**Returns**: List of pods in the node pool with status, readiness, and resource usage

**Example**:
```
Show me the pods for broker-pool
```

## Next steps

- **[Strimzi operator tools](strimzi-operators.md)** -- Manage operators and view events
- **[Tools reference](.)** -- Back to tools overview
