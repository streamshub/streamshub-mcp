+++
title = 'Drain Cleaner tools'
weight = 4
+++

Tools for managing and monitoring the Strimzi Drain Cleaner, which handles graceful pod evictions during Kubernetes node drains.

## Overview

The Strimzi Drain Cleaner intercepts pod eviction requests via a `ValidatingWebhookConfiguration` and annotates pods so the Strimzi operator performs proper rolling updates instead of abrupt evictions.
Without it, node maintenance can cause data loss or broker unavailability.

The drain cleaner operates in two modes:

- **Standard mode** (default) -- Denies eviction requests and annotates pods with `strimzi.io/manual-rolling-update` for the Strimzi operator to handle
- **Legacy mode** -- Allows evictions while relying on Pod Disruption Budgets with `maxUnavailable: 0` to prevent actual pod termination

## Deployment management

### list_drain_cleaners

List Strimzi Drain Cleaner deployments with status and webhook configuration.

**Parameters**:
- `namespace` (optional) -- Limit search to specific namespace

**Returns**: List of drain cleaner deployments with name, namespace, status, version, operating mode, and readiness

**Example**:
```
List all Strimzi Drain Cleaners
```

### get_drain_cleaner

Get detailed information about a Strimzi Drain Cleaner deployment including webhook configuration, failure policy, operating mode, and TLS certificate status.

**Parameters**:
- `drainCleanerName` (required) -- Name of the drain cleaner deployment
- `namespace` (optional) -- Kubernetes namespace

**Returns**: Detailed drain cleaner information including:
- Deployment status, version, image, replicas, and uptime
- Operating mode (standard or legacy)
- Webhook configuration status and failure policy
- Environment variable configuration (`STRIMZI_DENY_EVICTION`, `STRIMZI_DRAIN_KAFKA`, `STRIMZI_DRAIN_NAMESPACES`)

**Example**:
```
Get details for strimzi-drain-cleaner
```

## Logs

### get_drain_cleaner_logs

Get logs from Strimzi Drain Cleaner pods with filtering and error analysis. Useful for checking behavior during node drains and rolling updates.

**Parameters**:
- `namespace` (optional) -- Drain cleaner namespace
- `filter` (optional) -- Log level filter (e.g., "errors", "warnings", or a regex pattern)
- `keywords` (optional) -- List of keywords to search for in logs
- `sinceMinutes` (optional) -- Time window in minutes
- `startTime` (optional) -- Start time (ISO 8601 format)
- `endTime` (optional) -- End time (ISO 8601 format)
- `tailLines` (optional) -- Number of lines to tail from each pod
- `previous` (optional) -- Get logs from previous container instance

**Returns**: Aggregated drain cleaner logs with error analysis and statistics

**Example**:
```
Get drain cleaner logs with errors from the last 30 minutes
```

## Readiness assessment

### check_drain_cleaner_readiness

Check readiness of Strimzi Drain Cleaner. Runs a comprehensive assessment of the drain cleaner deployment.

**Parameters**:
- `namespace` (optional) -- Limit search to specific namespace

**Returns**: Readiness report including:
- **Deployment check** -- Is the drain cleaner deployed and all replicas ready?
- **Webhook check** -- Is the `ValidatingWebhookConfiguration` present?
- **Failure policy** -- What is the webhook failure policy (`Ignore` or `Fail`)?
- **Operating mode** -- Standard (recommended) or legacy?
- **TLS certificate** -- Is the webhook certificate valid and not expiring soon?
- **Namespace coverage** -- Which namespaces are monitored?

If the drain cleaner is not deployed, the tool reports this as a critical finding.

**Example**:
```
Check if drain cleaner is production ready
```

## RBAC requirements

The drain cleaner tools use the following Kubernetes API resources:

| Resource | API Group | Verbs | Purpose |
|----------|-----------|-------|---------|
| Deployments | `apps` | `get`, `list` | Discover drain cleaner deployments |
| Pods | `""` | `get`, `list` | List drain cleaner pods for log collection |
| Pod logs | `""` | `get` | Collect drain cleaner logs |
| ValidatingWebhookConfigurations | `admissionregistration.k8s.io` | `get`, `list` | Check webhook configuration |
| Secrets | `""` | `get` | Check TLS certificate expiry (requires optional sensitive Role) |

Deployments, Pods, and Pod logs are covered by the default ClusterRole.
The `ValidatingWebhookConfiguration` permission is included in the ClusterRole.
Secret access for TLS certificate checking requires the optional [sensitive Role](../installation.md#rbac-configuration).

## Integration with diagnostic tools

Drain Cleaner status is automatically included in cluster diagnostics:

- **[`diagnose_kafka_cluster`](diagnostics.md#diagnose_kafka_cluster)** -- Gathers Drain Cleaner readiness in Phase 1 and logs in Phase 2. If the drain cleaner is not deployed, the report flags this as a finding.
- **[`diagnose-cluster-issue` prompt](prompts-and-resources.md)** -- Includes a dedicated drain cleaner diagnostic step that guides the LLM to check readiness, webhook configuration, and logs.

## Next steps

- **[Strimzi operator tools](strimzi-operators.md)** -- Monitor Strimzi operators
- **[Diagnostic tools](diagnostics.md)** -- Run multi-step diagnostic workflows
- **[Tools reference](.)** -- Back to tools overview
