---
title: "Configuration"
weight: 2
---

Configure the Strimzi MCP Server with environment variables to customize behavior and integrate with external systems.

## Configuration Overview

The Strimzi MCP Server can be configured through:
- Environment variables (recommended for Kubernetes deployments)
- `application.properties` file (for local development)
- ConfigMaps and Secrets (for Kubernetes)

## Core Configuration

### Server Information

| Property | Default | Description |
|----------|---------|-------------|
| `quarkus.mcp.server.server-info.name` | `strimzi-mcp` | MCP server name |
| `quarkus.mcp.server.server-info.version` | `1.0.0` | Server version |
| `quarkus.http.port` | `8080` | HTTP port |

### CORS Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `quarkus.http.cors.enabled` | `true` | Enable CORS |
| `quarkus.http.cors.origins` | `http://localhost:*,https://localhost:*` | Allowed origins |
| `quarkus.http.cors.methods` | `GET,POST,OPTIONS` | Allowed HTTP methods |
| `quarkus.http.cors.headers` | `Accept,Content-Type,Mcp-Session-Id` | Allowed headers |

**Development**: In dev mode, CORS is permissive (`/.*/`) for testing.

**Production**: Override with environment variable:
```bash
export QUARKUS_HTTP_CORS_ORIGINS=https://your-domain.com
```

## Log Configuration

### Log Provider Selection

| Property | Default | Description |
|----------|---------|-------------|
| `mcp.log.provider` | `streamshub-kubernetes` | Log provider: `streamshub-kubernetes` or `streamshub-loki` |
| `mcp.log.tail-lines` | `200` | Default number of log lines to tail per pod |

### Kubernetes Log Provider (Default)

Reads logs directly from Kubernetes pods via the API. No additional configuration required.

**Environment Variable**:
```bash
export MCP_LOG_TAIL_LINES=500
```

### Loki Log Provider

For centralized log collection via Grafana Loki.

| Property | Default | Description |
|----------|---------|-------------|
| `quarkus.rest-client.loki.url` | `http://localhost:3100` | Loki endpoint URL |
| `mcp.log.loki.auth-mode` | `none` | Auth mode: `none`, `basic`, or `serviceaccount` |
| `mcp.log.loki.sa-token-path` | `/var/run/secrets/kubernetes.io/serviceaccount/token` | ServiceAccount token path |

**Environment Variables**:
```bash
export QUARKUS_REST_CLIENT_LOKI_URL=http://loki.monitoring:3100
export MCP_LOG_PROVIDER=streamshub-loki
```

#### Loki Authentication

**Basic Auth**:
```bash
export MCP_LOG_LOKI_AUTH_MODE=basic
export QUARKUS_REST_CLIENT_LOKI_USERNAME=your-username
export QUARKUS_REST_CLIENT_LOKI_PASSWORD=your-password
```

**ServiceAccount Token** (Kubernetes):
```bash
export MCP_LOG_LOKI_AUTH_MODE=serviceaccount
# Token is automatically read from the mounted ServiceAccount
```

#### Loki Label Mapping

Configure label names for namespace and pod (defaults match Promtail/Alloy):

| Property | Default | Description |
|----------|---------|-------------|
| `mcp.log.loki.label.namespace` | `namespace` | Loki label for namespace |
| `mcp.log.loki.label.pod` | `pod` | Loki label for pod name |

**For OpenShift Logging (ClusterLogForwarder)**:
```properties
mcp.log.loki.label.namespace=kubernetes_namespace_name
mcp.log.loki.label.pod=kubernetes_pod_name
```

#### Loki TLS Configuration

**Server Certificate Verification**:
```bash
export QUARKUS_REST_CLIENT_LOKI_TRUST_STORE=/etc/loki-tls/ca.crt
export QUARKUS_REST_CLIENT_LOKI_TRUST_STORE_TYPE=PEM
```

**mTLS Client Certificate**:
```bash
export QUARKUS_REST_CLIENT_LOKI_KEY_STORE=/etc/loki-tls/client.p12
export QUARKUS_REST_CLIENT_LOKI_KEY_STORE_PASSWORD=changeit
export QUARKUS_REST_CLIENT_LOKI_KEY_STORE_TYPE=PKCS12
```

## Metrics Configuration

### Metrics Provider Selection

| Property | Default | Description |
|----------|---------|-------------|
| `mcp.metrics.provider` | `streamshub-pod-scraping` | Metrics provider: `streamshub-pod-scraping` or `streamshub-prometheus` |
| `mcp.metrics.default-step-seconds` | `60` | Default query resolution in seconds |

### Pod Scraping Provider (Default)

Scrapes metrics directly from Kafka pods. Requires `pods/proxy` RBAC permission.

### Prometheus Provider

For centralized metrics via Prometheus.

| Property | Default | Description |
|----------|---------|-------------|
| `quarkus.rest-client.prometheus.url` | `http://localhost:9090` | Prometheus endpoint URL |
| `mcp.metrics.prometheus.auth-mode` | `none` | Auth mode: `none`, `basic`, or `serviceaccount` |
| `mcp.metrics.prometheus.sa-token-path` | `/var/run/secrets/kubernetes.io/serviceaccount/token` | ServiceAccount token path |

**Environment Variables**:
```bash
export QUARKUS_REST_CLIENT_PROMETHEUS_URL=http://prometheus.monitoring:9090
export MCP_METRICS_PROVIDER=streamshub-prometheus
```

#### Prometheus Authentication

**Basic Auth**:
```bash
export MCP_METRICS_PROMETHEUS_AUTH_MODE=basic
export QUARKUS_REST_CLIENT_PROMETHEUS_USERNAME=your-username
export QUARKUS_REST_CLIENT_PROMETHEUS_PASSWORD=your-password
```

**ServiceAccount Token** (Kubernetes):
```bash
export MCP_METRICS_PROMETHEUS_AUTH_MODE=serviceaccount
```

#### Prometheus TLS Configuration

**Server Certificate Verification**:
```bash
export QUARKUS_REST_CLIENT_PROMETHEUS_TRUST_STORE=/etc/prometheus-tls/ca.crt
export QUARKUS_REST_CLIENT_PROMETHEUS_TRUST_STORE_TYPE=PEM
export QUARKUS_REST_CLIENT_PROMETHEUS_VERIFY_HOST=true
```

**mTLS Client Certificate**:
```bash
export QUARKUS_REST_CLIENT_PROMETHEUS_KEY_STORE=/etc/prometheus-tls/client.p12
export QUARKUS_REST_CLIENT_PROMETHEUS_KEY_STORE_PASSWORD=changeit
export QUARKUS_REST_CLIENT_PROMETHEUS_KEY_STORE_TYPE=PKCS12
```

## Advanced Configuration

### Sampling Configuration

LLM-powered diagnostic analysis configuration (used by composite diagnostic tools).

| Property | Default | Description |
|----------|---------|-------------|
| `mcp.sampling.triage-max-tokens` | `200` | Max tokens for triage requests (decides investigation areas) |
| `mcp.sampling.analysis-max-tokens` | `1500` | Max tokens for analysis requests (root cause analysis) |

**Environment Variables**:
```bash
export MCP_SAMPLING_TRIAGE_MAX_TOKENS=300
export MCP_SAMPLING_ANALYSIS_MAX_TOKENS=2000
```

These control how much context the LLM receives during diagnostic workflows:
- **Triage**: Quick decision on which areas need deeper investigation (Phase 2 of diagnostic tools)
- **Analysis**: Detailed root cause analysis with more context (Phase 3 of diagnostic tools)

See [Diagnostic Tools](tools.md#diagnostic-tools) for more information on how Sampling is used.

### Resource Watch Configuration

Control Kubernetes resource watches that send MCP notifications when resources change.

| Property | Default | Description |
|----------|---------|-------------|
| `mcp.resource-watches.enabled` | `true` | Enable resource subscriptions |

**Environment Variable**:
```bash
export MCP_RESOURCE_WATCHES_ENABLED=false  # Disable for testing
```

When enabled, the server watches:
- Kafka custom resources
- KafkaNodePool custom resources
- KafkaTopic custom resources
- Strimzi operator Deployments

Changes trigger `notifications/resources/updated` messages to subscribed MCP clients.

### Events Configuration

Control Kubernetes events collection behavior.

| Property | Default | Description |
|----------|---------|-------------|
| `mcp.events.max-related-resources` | `50` | Max related resources (pods, PVCs) to query events for per cluster |

**Environment Variable**:
```bash
export MCP_EVENTS_MAX_RELATED_RESOURCES=100
```

Limits the number of related resources (pods, PVCs, node pools) for which events are collected when using [`get_strimzi_events`](tools.md#get_strimzi_events).

### Completion Cache Configuration

Control autocomplete caching for prompt and resource template parameters.

| Property | Default | Description |
|----------|---------|-------------|
| `mcp.completion.cache-ttl-seconds` | `5` | Cache TTL for completion results in seconds |

**Environment Variable**:
```bash
export MCP_COMPLETION_CACHE_TTL_SECONDS=10
```

Completion results (namespace lists, cluster names, etc.) are cached to improve performance.

### Topic Pagination Configuration

Control default pagination for topic listing.

| Property | Default | Description |
|----------|---------|-------------|
| `mcp.topics.default-page-size` | `100` | Default number of topics per page |

**Environment Variable**:
```bash
export MCP_TOPICS_DEFAULT_PAGE_SIZE=50
```

Used by [`list_kafka_topics`](tools.md#list_kafka_topics) when no explicit limit is provided.

## Security Configuration

### Log Redaction

The MCP server automatically redacts sensitive information from tool responses to prevent accidental exposure of credentials, tokens, and other secrets.

| Property | Default | Description |
|----------|---------|-------------|
| `mcp.guardrail.log-redaction.enabled` | `true` | Enable log redaction |
| `mcp.guardrail.log-redaction.custom-patterns[N]` | - | Custom regex patterns for redaction (indexed) |

**Environment Variables**:
```bash
# Disable log redaction (not recommended for production)
export MCP_GUARDRAIL_LOG_REDACTION_ENABLED=false

# Add custom redaction patterns
export MCP_GUARDRAIL_LOG_REDACTION_CUSTOM_PATTERNS_0='(?i)ssn\s*[=:]\s*\d{3}-\d{2}-\d{4}'
export MCP_GUARDRAIL_LOG_REDACTION_CUSTOM_PATTERNS_1='(?i)x-custom-header:\s*\S+'
```

**Built-in Redaction Patterns**:
- Bearer tokens (`bearer <token>`)
- Passwords in various formats (`password=...`, `pwd:...`)
- API keys and secrets (`apikey=...`, `secret=...`)
- Private keys and certificates (PEM format)
- Connection strings with credentials
- Authorization headers

**Custom Patterns**:
Add custom regex patterns via indexed configuration. Patterns are applied in addition to built-in rules:

```properties
# In application.properties
mcp.guardrail.log-redaction.custom-patterns[0]=(?i)ssn\\s*[=:]\\s*\\d{3}-\\d{2}-\\d{4}
mcp.guardrail.log-redaction.custom-patterns[1]=(?i)x-custom-header:\\s*\\S+
```

**Example**:
```
Original: Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
Redacted: Authorization: Bearer [REDACTED]
```

**Note**: Log redaction is enabled by default for security. Only disable in development environments where sensitive data exposure is acceptable.

### Response Size Limits

Prevent excessive response sizes that could impact client performance.

| Property | Default | Description |
|----------|---------|-------------|
| `mcp.guardrail.max-response-bytes` | `500000` | Maximum response size in bytes (500KB) |

When a response exceeds this limit, the largest text fields are truncated to fit within the limit.

**Environment Variable**:
```bash
export MCP_GUARDRAIL_MAX_RESPONSE_BYTES=1000000  # 1MB
```

### Rate Limiting

Control request rates per tool category to prevent resource exhaustion.

| Property | Default | Description |
|----------|---------|-------------|
| `mcp.guardrail.rate-limit.log-rpm` | `0` | Log collection requests per minute (0 = unlimited) |
| `mcp.guardrail.rate-limit.metrics-rpm` | `0` | Metrics query requests per minute (0 = unlimited) |
| `mcp.guardrail.rate-limit.general-rpm` | `0` | General tool requests per minute (0 = unlimited) |

**Environment Variables**:
```bash
export MCP_GUARDRAIL_RATE_LIMIT_LOG_RPM=30
export MCP_GUARDRAIL_RATE_LIMIT_METRICS_RPM=60
export MCP_GUARDRAIL_RATE_LIMIT_GENERAL_RPM=120
```

Rate limits are enforced per tool category:
- **Log category**: [`get_kafka_cluster_logs`](tools.md#get_kafka_cluster_logs), [`get_strimzi_operator_logs`](tools.md#get_strimzi_operator_logs)
- **Metrics category**: [`get_kafka_metrics`](tools.md#get_kafka_metrics), [`get_kafka_exporter_metrics`](tools.md#get_kafka_exporter_metrics), [`get_strimzi_operator_metrics`](tools.md#get_strimzi_operator_metrics)
- **General category**: All other tools

When a rate limit is exceeded, the tool returns an error indicating the limit and retry time.

## Kubernetes Configuration

### Using ConfigMaps

Create a ConfigMap for non-sensitive configuration:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: strimzi-mcp-config
  namespace: streamshub-mcp
data:
  MCP_LOG_TAIL_LINES: "500"
  LOKI_URL: "http://loki.monitoring:3100"
  LOKI_AUTH_ENABLED: "false"
  PROMETHEUS_URL: "http://prometheus.monitoring:9090"
  PROMETHEUS_AUTH_ENABLED: "false"
```

Apply the ConfigMap:
```bash
kubectl apply -f strimzi-mcp-config.yaml
```

### Using Secrets

Create a Secret for sensitive data:

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: strimzi-mcp-secrets
  namespace: streamshub-mcp
type: Opaque
stringData:
  LOKI_AUTH_TOKEN: "your-loki-token"
  PROMETHEUS_AUTH_TOKEN: "your-prometheus-token"
```

Apply the Secret:
```bash
kubectl apply -f strimzi-mcp-secrets.yaml
```

### Update Deployment

Reference ConfigMap and Secret in the Deployment:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: streamshub-strimzi-mcp
  namespace: streamshub-mcp
spec:
  template:
    spec:
      containers:
      - name: strimzi-mcp
        image: quay.io/streamshub/strimzi-mcp:latest
        # Load all ConfigMap values as environment variables
        envFrom:
        - configMapRef:
            name: strimzi-mcp-config
        # Load specific Secret values
        env:
        - name: LOKI_AUTH_TOKEN
          valueFrom:
            secretKeyRef:
              name: strimzi-mcp-secrets
              key: LOKI_AUTH_TOKEN
        - name: PROMETHEUS_AUTH_TOKEN
          valueFrom:
            secretKeyRef:
              name: strimzi-mcp-secrets
              key: PROMETHEUS_AUTH_TOKEN
```

Apply the updated Deployment:
```bash
kubectl apply -f install/005-Deployment.yaml
```

### Quick Configuration Update

Update environment variables without editing manifests:

```bash
# Set Loki URL
kubectl -n streamshub-mcp set env deployment/streamshub-strimzi-mcp \
  LOKI_URL=http://loki.monitoring:3100

# Set Prometheus URL
kubectl -n streamshub-mcp set env deployment/streamshub-strimzi-mcp \
  PROMETHEUS_URL=http://prometheus.monitoring:9090

# Set log tail lines
kubectl -n streamshub-mcp set env deployment/streamshub-strimzi-mcp \
  MCP_LOG_TAIL_LINES=500
```

## Loki Integration

### Overview

When configured, the MCP server uses Loki for centralized log collection instead of querying pod logs directly from Kubernetes.

**Benefits**:
- Historical log queries beyond pod retention
- Aggregated log search across multiple pods
- Advanced filtering with LogQL
- Better performance for large-scale deployments

### Setup Loki

Deploy Loki to your cluster (example using Loki Operator):

```bash
# Deploy Loki operator and instance
kubectl apply -k dev/manifests/loki/

# Verify Loki is running
kubectl -n loki get pods
```

### Configure MCP Server

```bash
# Get Loki service URL
LOKI_URL=$(kubectl -n loki get svc loki -o jsonpath='{.spec.clusterIP}')

# Configure MCP server
kubectl -n streamshub-mcp set env deployment/streamshub-strimzi-mcp \
  LOKI_URL=http://${LOKI_URL}:3100
```

### Authentication

If Loki requires authentication:

```bash
# Create Secret with token
kubectl -n streamshub-mcp create secret generic loki-auth \
  --from-literal=token=your-loki-token

# Update Deployment to use Secret
kubectl -n streamshub-mcp set env deployment/streamshub-strimzi-mcp \
  LOKI_AUTH_ENABLED=true \
  --from=secret/loki-auth \
  --keys=token \
  --prefix=LOKI_AUTH_
```

### Verify Integration

```bash
# Check MCP server logs
kubectl -n streamshub-mcp logs -l app=streamshub-strimzi-mcp

# Test log collection
# Ask your AI assistant: "Collect logs from mcp-cluster"
```

## Prometheus Integration

### Overview

When configured, the MCP server uses Prometheus for metrics queries instead of scraping pod metrics directly.

**Benefits**:
- Historical metrics with long-term retention
- Pre-aggregated metrics for better performance
- Advanced queries with PromQL
- Centralized metrics across the cluster

### Setup Prometheus

Deploy Prometheus to your cluster:

```bash
# Deploy Prometheus operator and instance
kubectl apply -k dev/manifests/prometheus/

# Verify Prometheus is running
kubectl get prometheus -A
```

### Configure MCP Server

```bash
# Get Prometheus service URL
PROM_URL=$(kubectl -n monitoring get svc prometheus-operated -o jsonpath='{.spec.clusterIP}')

# Configure MCP server
kubectl -n streamshub-mcp set env deployment/streamshub-strimzi-mcp \
  PROMETHEUS_URL=http://${PROM_URL}:9090
```

### Authentication

If Prometheus requires authentication:

```bash
# Create Secret with token
kubectl -n streamshub-mcp create secret generic prometheus-auth \
  --from-literal=token=your-prometheus-token

# Update Deployment to use Secret
kubectl -n streamshub-mcp set env deployment/streamshub-strimzi-mcp \
  PROMETHEUS_AUTH_ENABLED=true \
  --from=secret/prometheus-auth \
  --keys=token \
  --prefix=PROMETHEUS_AUTH_
```

### Verify Integration

```bash
# Check MCP server logs
kubectl -n streamshub-mcp logs -l app=streamshub-strimzi-mcp

# Test metrics query
# Ask your AI assistant: "Query Kafka metrics for mcp-cluster"
```

## Configuration Examples

### Minimal Configuration (Kubernetes Logs Only)

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: strimzi-mcp-config
  namespace: streamshub-mcp
data:
  MCP_LOG_TAIL_LINES: "200"
```

### Full Configuration (Loki + Prometheus)

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: strimzi-mcp-config
  namespace: streamshub-mcp
data:
  MCP_LOG_TAIL_LINES: "500"
  LOKI_URL: "http://loki.monitoring:3100"
  LOKI_AUTH_ENABLED: "true"
  PROMETHEUS_URL: "http://prometheus.monitoring:9090"
  PROMETHEUS_AUTH_ENABLED: "true"
---
apiVersion: v1
kind: Secret
metadata:
  name: strimzi-mcp-secrets
  namespace: streamshub-mcp
type: Opaque
stringData:
  LOKI_AUTH_TOKEN: "your-loki-token"
  PROMETHEUS_AUTH_TOKEN: "your-prometheus-token"
```

### Development Configuration

For local development, create a `.env` file:

```bash
# .env file for local development
MCP_LOG_TAIL_LINES=500
LOKI_URL=http://localhost:3100
LOKI_AUTH_ENABLED=false
PROMETHEUS_URL=http://localhost:9090
PROMETHEUS_AUTH_ENABLED=false
```

Load and run:
```bash
export $(cat .env | xargs)
mvn quarkus:dev
```

## Verification

### Check Configuration

```bash
# View current configuration
kubectl -n streamshub-mcp get deployment streamshub-strimzi-mcp -o yaml | grep -A 20 env:

# Check ConfigMap
kubectl -n streamshub-mcp get configmap strimzi-mcp-config -o yaml

# Check Secret (values are base64 encoded)
kubectl -n streamshub-mcp get secret strimzi-mcp-secrets -o yaml
```

### Test Connectivity

```bash
# Test Loki connectivity from MCP pod
kubectl -n streamshub-mcp exec -it deployment/streamshub-strimzi-mcp -- \
  curl http://loki.monitoring:3100/ready

# Test Prometheus connectivity from MCP pod
kubectl -n streamshub-mcp exec -it deployment/streamshub-strimzi-mcp -- \
  curl http://prometheus.monitoring:9090/-/ready
```

## Troubleshooting

### Loki Connection Issues

```bash
# Check Loki service
kubectl -n monitoring get svc loki

# Check Loki logs
kubectl -n monitoring logs -l app=loki

# Verify network connectivity
kubectl -n streamshub-mcp exec -it deployment/streamshub-strimzi-mcp -- \
  curl -v http://loki.monitoring:3100/ready
```

### Prometheus Connection Issues

```bash
# Check Prometheus service
kubectl -n monitoring get svc prometheus-operated

# Check Prometheus logs
kubectl -n monitoring logs -l app.kubernetes.io/name=prometheus

# Verify network connectivity
kubectl -n streamshub-mcp exec -it deployment/streamshub-strimzi-mcp -- \
  curl -v http://prometheus.monitoring:9090/-/ready
```

### Authentication Issues

```bash
# Verify Secret exists
kubectl -n streamshub-mcp get secret strimzi-mcp-secrets

# Check if environment variables are set
kubectl -n streamshub-mcp exec -it deployment/streamshub-strimzi-mcp -- \
  env | grep -E '(LOKI|PROMETHEUS)'

# Check MCP server logs for auth errors
kubectl -n streamshub-mcp logs -l app=streamshub-strimzi-mcp | grep -i auth
```

## Next Steps

- **[Tools Reference](tools.md)** — Explore available tools
- **[Usage Examples](usage-examples.md)** — See practical examples
- **[Troubleshooting](troubleshooting.md)** — Resolve common issues

## Related Documentation

- [Loki Module](../../modules/loki-log-provider.md) — Loki integration details
- [Prometheus Module](../../modules/metrics-prometheus.md) — Prometheus integration details
- [Deployment Guide](../../deployment.md) — Production deployment patterns