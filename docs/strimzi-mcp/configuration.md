+++
title = 'Configuration'
weight = 2
+++

Configure the Strimzi MCP Server to customize its behavior and integrate with external systems.

## Configuration overview

You can configure the Strimzi MCP Server through:

- **Environment variables** — Recommended for Kubernetes deployments
- **application.properties file** — Convenient for local development
- **ConfigMaps and Secrets** — Standard Kubernetes configuration management

## Core configuration

### Server information

Configure basic server identity and network settings.

| Property | Default | Description |
|----------|---------|-------------|
| `quarkus.mcp.server.server-info.name` | `strimzi-mcp` | MCP server name shown to clients |
| `quarkus.mcp.server.server-info.version` | `1.0.0` | Server version |
| `quarkus.http.port` | `8080` | HTTP port the server listens on |

### CORS configuration

Control cross-origin resource sharing for web-based MCP clients.

| Property | Default | Description |
|----------|---------|-------------|
| `quarkus.http.cors.enabled` | `true` | Enable CORS support |
| `quarkus.http.cors.origins` | `http://localhost:*,https://localhost:*` | Allowed origins |
| `quarkus.http.cors.methods` | `GET,POST,OPTIONS` | Allowed HTTP methods |
| `quarkus.http.cors.headers` | `Accept,Content-Type,Mcp-Session-Id` | Allowed headers |

**Development mode:**
In dev mode, CORS is permissive (`/.*/`) to simplify testing.

**Production mode:**
Override with a specific domain:

```bash
export QUARKUS_HTTP_CORS_ORIGINS=https://your-domain.com
```

## Log configuration

### Log provider selection

Choose how the server collects logs from your Kafka pods.

| Property | Default | Description |
|----------|---------|-------------|
| `mcp.log.provider` | `streamshub-kubernetes` | Log provider: `streamshub-kubernetes` or `streamshub-loki` |
| `mcp.log.tail-lines` | `200` | Default number of log lines to retrieve per pod |

### Kubernetes log provider (default)

The default provider reads logs directly from Kubernetes pods through the API.
No additional configuration is required.

To change the default number of log lines:

```bash
export MCP_LOG_TAIL_LINES=500
```

### Loki log provider

Use Grafana Loki for centralized log collection and historical log queries.

| Property | Default | Description |
|----------|---------|-------------|
| `quarkus.rest-client.loki.url` | `http://localhost:3100` | Loki endpoint URL |
| `mcp.log.loki.auth-mode` | `none` | Authentication mode: `none`, `basic`, or `serviceaccount` |
| `mcp.log.loki.sa-token-path` | `/var/run/secrets/kubernetes.io/serviceaccount/token` | Path to ServiceAccount token |

To enable Loki:

```bash
export QUARKUS_REST_CLIENT_LOKI_URL=http://loki.monitoring:3100
export MCP_LOG_PROVIDER=streamshub-loki
```

#### Loki authentication

**Basic authentication:**

```bash
export MCP_LOG_LOKI_AUTH_MODE=basic
export QUARKUS_REST_CLIENT_LOKI_USERNAME=your-username
export QUARKUS_REST_CLIENT_LOKI_PASSWORD=your-password
```

**ServiceAccount token (Kubernetes):**

```bash
export MCP_LOG_LOKI_AUTH_MODE=serviceaccount
# The token is automatically read from the mounted ServiceAccount
```

#### Loki label mapping

Configure how Loki labels map to Kubernetes concepts.
The defaults work with Promtail and Grafana Alloy.

| Property | Default | Description |
|----------|---------|-------------|
| `mcp.log.loki.label.namespace` | `namespace` | Loki label for Kubernetes namespace |
| `mcp.log.loki.label.pod` | `pod` | Loki label for pod name |

**For OpenShift Logging (ClusterLogForwarder):**

```properties
mcp.log.loki.label.namespace=kubernetes_namespace_name
mcp.log.loki.label.pod=kubernetes_pod_name
```

#### Loki TLS configuration

**Server certificate verification:**

```bash
export QUARKUS_REST_CLIENT_LOKI_TRUST_STORE=/etc/loki-tls/ca.crt
export QUARKUS_REST_CLIENT_LOKI_TRUST_STORE_TYPE=PEM
```

**Mutual TLS with client certificate:**

```bash
export QUARKUS_REST_CLIENT_LOKI_KEY_STORE=/etc/loki-tls/client.p12
export QUARKUS_REST_CLIENT_LOKI_KEY_STORE_PASSWORD=changeit
export QUARKUS_REST_CLIENT_LOKI_KEY_STORE_TYPE=PKCS12
```

## Metrics configuration

### Metrics provider selection

Choose how the server collects metrics from your Kafka cluster.

| Property | Default | Description |
|----------|---------|-------------|
| `mcp.metrics.provider` | `streamshub-pod-scraping` | Metrics provider: `streamshub-pod-scraping` or `streamshub-prometheus` |
| `mcp.metrics.default-step-seconds` | `60` | Default query resolution in seconds |

### Pod scraping provider (default)

The default provider scrapes metrics directly from Kafka pods.
This requires the `pods/proxy` RBAC permission.

### Prometheus provider

Use Prometheus for centralized metrics with long-term retention.

| Property | Default | Description |
|----------|---------|-------------|
| `quarkus.rest-client.prometheus.url` | `http://localhost:9090` | Prometheus endpoint URL |
| `mcp.metrics.prometheus.auth-mode` | `none` | Authentication mode: `none`, `basic`, or `serviceaccount` |
| `mcp.metrics.prometheus.sa-token-path` | `/var/run/secrets/kubernetes.io/serviceaccount/token` | Path to ServiceAccount token |

To enable Prometheus:

```bash
export QUARKUS_REST_CLIENT_PROMETHEUS_URL=http://prometheus.monitoring:9090
export MCP_METRICS_PROVIDER=streamshub-prometheus
```

#### Prometheus authentication

**Basic authentication:**

```bash
export MCP_METRICS_PROMETHEUS_AUTH_MODE=basic
export QUARKUS_REST_CLIENT_PROMETHEUS_USERNAME=your-username
export QUARKUS_REST_CLIENT_PROMETHEUS_PASSWORD=your-password
```

**ServiceAccount token (Kubernetes):**

```bash
export MCP_METRICS_PROMETHEUS_AUTH_MODE=serviceaccount
```

#### Prometheus TLS configuration

**Server certificate verification:**

```bash
export QUARKUS_REST_CLIENT_PROMETHEUS_TRUST_STORE=/etc/prometheus-tls/ca.crt
export QUARKUS_REST_CLIENT_PROMETHEUS_TRUST_STORE_TYPE=PEM
export QUARKUS_REST_CLIENT_PROMETHEUS_VERIFY_HOST=true
```

**Mutual TLS with client certificate:**

```bash
export QUARKUS_REST_CLIENT_PROMETHEUS_KEY_STORE=/etc/prometheus-tls/client.p12
export QUARKUS_REST_CLIENT_PROMETHEUS_KEY_STORE_PASSWORD=changeit
export QUARKUS_REST_CLIENT_PROMETHEUS_KEY_STORE_TYPE=PKCS12
```

## Advanced configuration

### Sampling configuration

Configure LLM-powered diagnostic analysis used by composite diagnostic tools.

| Property | Default | Description |
|----------|---------|-------------|
| `mcp.sampling.triage-max-tokens` | `200` | Maximum tokens for triage requests (decides investigation areas) |
| `mcp.sampling.analysis-max-tokens` | `1500` | Maximum tokens for analysis requests (root cause analysis) |

These settings control how much context the LLM receives during diagnostic workflows:

- **Triage** — Quick decision on which areas need deeper investigation (Phase 2)
- **Analysis** — Detailed root cause analysis with more context (Phase 3)

To adjust token limits:

```bash
export MCP_SAMPLING_TRIAGE_MAX_TOKENS=300
export MCP_SAMPLING_ANALYSIS_MAX_TOKENS=2000
```

See [Diagnostic Tools](tools.md#diagnostic-tools) for more information.

### Resource watch configuration

Control Kubernetes resource watches that send MCP notifications when resources change.

| Property | Default | Description |
|----------|---------|-------------|
| `mcp.resource-watches.enabled` | `true` | Enable resource subscriptions |

When enabled, the server watches:

- Kafka custom resources
- KafkaNodePool custom resources
- KafkaTopic custom resources
- Strimzi operator Deployments

Changes trigger `notifications/resources/updated` messages to subscribed MCP clients.

To disable (useful for testing):

```bash
export MCP_RESOURCE_WATCHES_ENABLED=false
```

### Events configuration

Control Kubernetes events collection behavior.

| Property | Default | Description |
|----------|---------|-------------|
| `mcp.events.max-related-resources` | `50` | Maximum related resources (pods, PVCs) to query events for per cluster |

This limits the number of related resources for which events are collected when using [`get_strimzi_events`](tools.md#get_strimzi_events).

```bash
export MCP_EVENTS_MAX_RELATED_RESOURCES=100
```

### Completion cache configuration

Control autocomplete caching for prompt and resource template parameters.

| Property | Default | Description |
|----------|---------|-------------|
| `mcp.completion.cache-ttl-seconds` | `5` | Cache TTL for completion results in seconds |

Completion results (namespace lists, cluster names, etc.) are cached to improve performance.

```bash
export MCP_COMPLETION_CACHE_TTL_SECONDS=10
```

### Topic pagination configuration

Control default pagination for topic listing.

| Property | Default | Description |
|----------|---------|-------------|
| `mcp.topics.default-page-size` | `100` | Default number of topics per page |

Used by [`list_kafka_topics`](tools.md#list_kafka_topics) when no explicit limit is provided.

```bash
export MCP_TOPICS_DEFAULT_PAGE_SIZE=50
```

## Security configuration

### Log redaction

The server automatically redacts sensitive information from tool responses.
This prevents accidental exposure of credentials, tokens, and other secrets.

| Property | Default | Description |
|----------|---------|-------------|
| `mcp.guardrail.log-redaction.enabled` | `true` | Enable log redaction |
| `mcp.guardrail.log-redaction.custom-patterns[N]` | - | Custom regex patterns for redaction (indexed) |

**Built-in redaction patterns:**

- Bearer tokens (`bearer <token>`)
- Passwords in various formats (`password=...`, `pwd:...`)
- API keys and secrets (`apikey=...`, `secret=...`)
- Private keys and certificates (PEM format)
- Connection strings with credentials
- Authorization headers

**To disable (not recommended for production):**

```bash
export MCP_GUARDRAIL_LOG_REDACTION_ENABLED=false
```

**To add custom patterns:**

```bash
export MCP_GUARDRAIL_LOG_REDACTION_CUSTOM_PATTERNS_0='(?i)ssn\s*[=:]\s*\d{3}-\d{2}-\d{4}'
export MCP_GUARDRAIL_LOG_REDACTION_CUSTOM_PATTERNS_1='(?i)x-custom-header:\s*\S+'
```

Or in `application.properties`:

```properties
mcp.guardrail.log-redaction.custom-patterns[0]=(?i)ssn\\s*[=:]\\s*\\d{3}-\\d{2}-\\d{4}
mcp.guardrail.log-redaction.custom-patterns[1]=(?i)x-custom-header:\\s*\\S+
```

**Example redaction:**

```
Original: Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
Redacted: Authorization: Bearer [REDACTED]
```

### Response size limits

Prevent excessive response sizes that could impact client performance.

| Property | Default | Description |
|----------|---------|-------------|
| `mcp.guardrail.max-response-bytes` | `500000` | Maximum response size in bytes (500KB) |

When a response exceeds this limit, the largest text fields are truncated to fit.

```bash
export MCP_GUARDRAIL_MAX_RESPONSE_BYTES=1000000  # 1MB
```

### Rate limiting

Control request rates per tool category to prevent resource exhaustion.

| Property | Default | Description |
|----------|---------|-------------|
| `mcp.guardrail.rate-limit.log-rpm` | `0` | Log collection requests per minute (0 = unlimited) |
| `mcp.guardrail.rate-limit.metrics-rpm` | `0` | Metrics query requests per minute (0 = unlimited) |
| `mcp.guardrail.rate-limit.general-rpm` | `0` | General tool requests per minute (0 = unlimited) |

Rate limits are enforced per tool category:

- **Log category** — [`get_kafka_cluster_logs`](tools.md#get_kafka_cluster_logs), [`get_strimzi_operator_logs`](tools.md#get_strimzi_operator_logs)
- **Metrics category** — [`get_kafka_metrics`](tools.md#get_kafka_metrics), [`get_kafka_exporter_metrics`](tools.md#get_kafka_exporter_metrics), [`get_strimzi_operator_metrics`](tools.md#get_strimzi_operator_metrics)
- **General category** — All other tools

To enable rate limiting:

```bash
export MCP_GUARDRAIL_RATE_LIMIT_LOG_RPM=30
export MCP_GUARDRAIL_RATE_LIMIT_METRICS_RPM=60
export MCP_GUARDRAIL_RATE_LIMIT_GENERAL_RPM=120
```

When a rate limit is exceeded, the tool returns an error indicating the limit and retry time.

## Kubernetes configuration

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
  QUARKUS_REST_CLIENT_LOKI_URL: "http://loki.monitoring:3100"
  MCP_LOG_LOKI_AUTH_MODE: "none"
  QUARKUS_REST_CLIENT_PROMETHEUS_URL: "http://prometheus.monitoring:9090"
  MCP_METRICS_PROMETHEUS_AUTH_MODE: "none"
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
  QUARKUS_REST_CLIENT_LOKI_USERNAME: "your-username"
  QUARKUS_REST_CLIENT_LOKI_PASSWORD: "your-password"
  QUARKUS_REST_CLIENT_PROMETHEUS_USERNAME: "your-username"
  QUARKUS_REST_CLIENT_PROMETHEUS_PASSWORD: "your-password"
```

Apply the Secret:

```bash
kubectl apply -f strimzi-mcp-secrets.yaml
```

### Update deployment

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
        - secretRef:
            name: strimzi-mcp-secrets
```

Apply the updated Deployment:

```bash
kubectl apply -f install/005-Deployment.yaml
```

### Quick configuration update

Update environment variables without editing manifests:

```bash
# Set Loki URL
kubectl -n streamshub-mcp set env deployment/streamshub-strimzi-mcp \
  QUARKUS_REST_CLIENT_LOKI_URL=http://loki.monitoring:3100

# Set Prometheus URL
kubectl -n streamshub-mcp set env deployment/streamshub-strimzi-mcp \
  QUARKUS_REST_CLIENT_PROMETHEUS_URL=http://prometheus.monitoring:9090

# Set log tail lines
kubectl -n streamshub-mcp set env deployment/streamshub-strimzi-mcp \
  MCP_LOG_TAIL_LINES=500
```

## Loki integration

### Overview

When configured, the MCP server uses Loki for centralized log collection instead of querying pod logs directly from Kubernetes.

**Benefits:**

- Historical log queries beyond pod retention
- Aggregated log search across multiple pods
- Advanced filtering with LogQL
- Better performance for large-scale deployments

### Setup Loki

Deploy Loki to your cluster using the Loki Operator:

```bash
# Deploy Loki operator and instance
kubectl apply -k dev/manifests/loki/

# Verify Loki is running
kubectl -n loki get pods
```

### Configure MCP server

Get the Loki service URL and configure the MCP server:

```bash
# Get Loki service URL
LOKI_URL=$(kubectl -n loki get svc loki -o jsonpath='{.spec.clusterIP}')

# Configure MCP server
kubectl -n streamshub-mcp set env deployment/streamshub-strimzi-mcp \
  QUARKUS_REST_CLIENT_LOKI_URL=http://${LOKI_URL}:3100 \
  MCP_LOG_PROVIDER=streamshub-loki
```

### Authentication

If Loki requires authentication, create a Secret with the token:

```bash
# Create Secret with token
kubectl -n streamshub-mcp create secret generic loki-auth \
  --from-literal=username=your-username \
  --from-literal=password=your-password

# Update Deployment to use Secret
kubectl -n streamshub-mcp set env deployment/streamshub-strimzi-mcp \
  MCP_LOG_LOKI_AUTH_MODE=basic \
  --from=secret/loki-auth
```

### Verify integration

Check that the integration is working:

```bash
# Check MCP server logs
kubectl -n streamshub-mcp logs -l app=streamshub-strimzi-mcp

# Test log collection through your AI assistant
# Ask: "Collect logs from mcp-cluster"
```

## Prometheus integration

### Overview

When configured, the MCP server uses Prometheus for metrics queries instead of scraping pod metrics directly.

**Benefits:**

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

### Configure MCP server

Get the Prometheus service URL and configure the MCP server:

```bash
# Get Prometheus service URL
PROM_URL=$(kubectl -n monitoring get svc prometheus-operated -o jsonpath='{.spec.clusterIP}')

# Configure MCP server
kubectl -n streamshub-mcp set env deployment/streamshub-strimzi-mcp \
  QUARKUS_REST_CLIENT_PROMETHEUS_URL=http://${PROM_URL}:9090 \
  MCP_METRICS_PROVIDER=streamshub-prometheus
```

### Authentication

If Prometheus requires authentication, create a Secret with the token:

```bash
# Create Secret with token
kubectl -n streamshub-mcp create secret generic prometheus-auth \
  --from-literal=username=your-username \
  --from-literal=password=your-password

# Update Deployment to use Secret
kubectl -n streamshub-mcp set env deployment/streamshub-strimzi-mcp \
  MCP_METRICS_PROMETHEUS_AUTH_MODE=basic \
  --from=secret/prometheus-auth
```

### Verify integration

Check that the integration is working:

```bash
# Check MCP server logs
kubectl -n streamshub-mcp logs -l app=streamshub-strimzi-mcp

# Test metrics query through your AI assistant
# Ask: "Query Kafka metrics for mcp-cluster"
```

## Configuration examples

### Minimal configuration (Kubernetes logs only)

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: strimzi-mcp-config
  namespace: streamshub-mcp
data:
  MCP_LOG_TAIL_LINES: "200"
```

### Full configuration (Loki + Prometheus)

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: strimzi-mcp-config
  namespace: streamshub-mcp
data:
  MCP_LOG_TAIL_LINES: "500"
  QUARKUS_REST_CLIENT_LOKI_URL: "http://loki.monitoring:3100"
  MCP_LOG_PROVIDER: "streamshub-loki"
  MCP_LOG_LOKI_AUTH_MODE: "basic"
  QUARKUS_REST_CLIENT_PROMETHEUS_URL: "http://prometheus.monitoring:9090"
  MCP_METRICS_PROVIDER: "streamshub-prometheus"
  MCP_METRICS_PROMETHEUS_AUTH_MODE: "basic"
---
apiVersion: v1
kind: Secret
metadata:
  name: strimzi-mcp-secrets
  namespace: streamshub-mcp
type: Opaque
stringData:
  QUARKUS_REST_CLIENT_LOKI_USERNAME: "your-username"
  QUARKUS_REST_CLIENT_LOKI_PASSWORD: "your-password"
  QUARKUS_REST_CLIENT_PROMETHEUS_USERNAME: "your-username"
  QUARKUS_REST_CLIENT_PROMETHEUS_PASSWORD: "your-password"
```

### Development configuration

For local development, create a `.env` file:

```bash
# .env file for local development
MCP_LOG_TAIL_LINES=500
QUARKUS_REST_CLIENT_LOKI_URL=http://localhost:3100
MCP_LOG_PROVIDER=streamshub-loki
MCP_LOG_LOKI_AUTH_MODE=none
QUARKUS_REST_CLIENT_PROMETHEUS_URL=http://localhost:9090
MCP_METRICS_PROVIDER=streamshub-prometheus
MCP_METRICS_PROMETHEUS_AUTH_MODE=none
```

Load and run:

```bash
export $(cat .env | xargs)
mvn quarkus:dev
```

## Verification

### Check configuration

View the current configuration:

```bash
# View environment variables in the deployment
kubectl -n streamshub-mcp get deployment streamshub-strimzi-mcp -o yaml | grep -A 20 env:

# Check ConfigMap
kubectl -n streamshub-mcp get configmap strimzi-mcp-config -o yaml

# Check Secret (values are base64 encoded)
kubectl -n streamshub-mcp get secret strimzi-mcp-secrets -o yaml
```

### Test connectivity

Test connectivity to external services:

```bash
# Test Loki connectivity from MCP pod
kubectl -n streamshub-mcp exec -it deployment/streamshub-strimzi-mcp -- \
  curl http://loki.monitoring:3100/ready

# Test Prometheus connectivity from MCP pod
kubectl -n streamshub-mcp exec -it deployment/streamshub-strimzi-mcp -- \
  curl http://prometheus.monitoring:9090/-/ready
```

## Troubleshooting

### Loki connection issues

If you cannot connect to Loki:

```bash
# Check Loki service exists
kubectl -n monitoring get svc loki

# Check Loki logs for errors
kubectl -n monitoring logs -l app=loki

# Verify network connectivity from MCP pod
kubectl -n streamshub-mcp exec -it deployment/streamshub-strimzi-mcp -- \
  curl -v http://loki.monitoring:3100/ready
```

### Prometheus connection issues

If you cannot connect to Prometheus:

```bash
# Check Prometheus service exists
kubectl -n monitoring get svc prometheus-operated

# Check Prometheus logs for errors
kubectl -n monitoring logs -l app.kubernetes.io/name=prometheus

# Verify network connectivity from MCP pod
kubectl -n streamshub-mcp exec -it deployment/streamshub-strimzi-mcp -- \
  curl -v http://prometheus.monitoring:9090/-/ready
```

### Authentication issues

If you see authentication errors:

```bash
# Verify Secret exists
kubectl -n streamshub-mcp get secret strimzi-mcp-secrets

# Check if environment variables are set correctly
kubectl -n streamshub-mcp exec -it deployment/streamshub-strimzi-mcp -- \
  env | grep -E '(LOKI|PROMETHEUS)'

# Check MCP server logs for authentication errors
kubectl -n streamshub-mcp logs -l app=streamshub-strimzi-mcp | grep -i auth
```

## Next steps

- **[Tools Reference](tools.md)** — Explore available tools and their parameters
- **[Usage Examples](usage-examples.md)** — See practical examples and workflows
- **[Troubleshooting](troubleshooting.md)** — Resolve common issues

