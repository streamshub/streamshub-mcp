+++
title = 'Kafka user tools'
weight = 3
+++

Tools for listing and inspecting KafkaUser resources, including authentication, ACL rules, and quotas. Credential secrets are never exposed.

## User management

### list_kafka_users

List KafkaUsers with authentication type, authorization, ACL count, and readiness. Optionally filter by Kafka cluster.

**Parameters**:
- `clusterName` (optional) -- Kafka cluster name to filter users
- `namespace` (optional) -- Kubernetes namespace

**Returns**: List of users with name, cluster, authentication type, authorization type, ACL count, readiness, and Kafka principal name

**Example**:
```
List all users for mcp-cluster
```

### get_kafka_user

Get detailed KafkaUser information including ACL rules, quotas, and Kafka principal name. Never exposes credential secrets.

**Parameters**:
- `userName` (required) -- Name of the KafkaUser
- `namespace` (optional) -- Kubernetes namespace

**Returns**: Detailed user information including authentication type, ACL rules (resource type, name, pattern, operations, host), quotas (producer/consumer byte rates, request percentage, controller mutation rate), Kafka principal name, credential secret name (not the secret data), and status conditions

**Example**:
```
Get details for user alice in my-cluster
```

## Security notes

- Credential secret data (passwords, certificates, keys) is **never** exposed
- Only the secret name from the KafkaUser status is shown
- ACL rules describe permissions and are safe to view
- Quotas describe resource limits and are safe to view

## Next steps

- **[Strimzi operator tools](strimzi-operators.md)** -- Manage operators and view events
- **[Prompts, resources, and subscriptions](prompts-and-resources.md)** -- Use the audit-security prompt for cluster security review
- **[Tools reference](.)** -- Back to tools overview
