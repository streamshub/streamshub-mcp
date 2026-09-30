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

**Returns**: Detailed user information including authentication type, ACL rules (type, resource type, name, pattern, operations, host), quotas (producer/consumer byte rates, request percentage, controller mutation rate), Kafka principal name, credential secret name (not the secret data), readiness, and status conditions

**Example**:
```
Get details for user alice in my-cluster
```

### get_kafka_user_acls_matrix

Build an ACL matrix (resource to principal to operations) aggregated across all KafkaUsers on a Kafka cluster, to answer questions like "who can write to this topic". Flags over-broad allow grants (wildcard resource name or the `All` operation).

**Parameters**:
- `clusterName` (required) -- Kafka cluster name
- `namespace` (optional) -- Kubernetes namespace
- `resourceType` (optional) -- ACL resource type to filter on: `topic` (default), `group`, `transactionalId`, or `cluster`

**Returns**: `matrix` (resource name to principal to sorted operations for allow rules), `denied` (same shape for deny rules, omitted when there are none), `principals` (sorted list of principals in `matrix`), `resource_count`, `principal_count`, `broad_grants` (principal, resource, operations, and a reason such as `"wildcard resource"` or `"All operations"`), and a summary `message`

On clusters with many users or ACL rules the response may be truncated by the guardrail that caps response size; narrow the query with `resourceType` or inspect individual users with `get_kafka_user`.

**Example**:
```
Who can write to topics on mcp-cluster, and are there any over-broad grants?
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
