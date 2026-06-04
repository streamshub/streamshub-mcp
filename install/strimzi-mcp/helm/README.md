# Strimzi MCP Server -- Helm Chart

Helm chart for deploying the Strimzi MCP Server to Kubernetes.

## Quick start

```bash
helm install streamshub-mcp-strimzi install/strimzi-mcp/helm/ \
  -n streamshub-mcp --create-namespace
```

## Configuration

Override values with `--set` or a values file:

```bash
# Production: 2 replicas, higher resources, Prometheus metrics
helm install streamshub-mcp-strimzi install/strimzi-mcp/helm/ \
  -n streamshub-mcp --create-namespace \
  --set replicaCount=1 \
  --set resources.requests.cpu=250m \
  --set resources.requests.memory=512Mi \
  --set resources.limits.cpu=1 \
  --set resources.limits.memory=1Gi \
  --set mcp.metrics.provider=prometheus \
  --set mcp.metrics.prometheus.url=http://prometheus.monitoring:9090

# OpenShift with Loki + Prometheus (service account auth)
helm install streamshub-mcp-strimzi install/strimzi-mcp/helm/ \
  -n streamshub-mcp --create-namespace \
  --set mcp.log.provider=loki \
  --set mcp.log.loki.url=https://logging-loki-gateway-http.openshift-logging.svc:8080/api/logs/v1/application \
  --set mcp.log.loki.authMode=sa-token \
  --set mcp.log.loki.labels.namespace=kubernetes_namespace_name \
  --set mcp.log.loki.labels.pod=kubernetes_pod_name \
  --set mcp.metrics.provider=prometheus \
  --set mcp.metrics.prometheus.url=https://thanos-querier.openshift-monitoring.svc:9091 \
  --set mcp.metrics.prometheus.authMode=sa-token \
  --set tls.trustAll=true \
  --set loki.enabled=true \
  --set monitoring.enabled=true

# Enable sensitive RBAC in Kafka namespaces
helm upgrade streamshub-mcp-strimzi install/strimzi-mcp/helm/ \
  -n streamshub-mcp \
  --set sensitive.namespaces={strimzi-kafka,kafka-prod}

# Enable tracing with Jaeger
helm upgrade streamshub-mcp-strimzi install/strimzi-mcp/helm/ \
  -n streamshub-mcp \
  --set mcp.tracing.enabled=true \
  --set mcp.tracing.endpoint=http://jaeger-collector.observability:4317

# OpenShift Route (edge-terminated TLS, 1h timeout for MCP/SSE)
helm upgrade streamshub-mcp-strimzi install/strimzi-mcp/helm/ \
  -n streamshub-mcp \
  --set route.enabled=true
```

## Configuration reference

### Core

| Parameter | Description | Default |
|-----------|-------------|---------|
| `replicaCount` | Number of replicas | `1` |
| `image.repository` | Container image repository | `quay.io/streamshub/strimzi-mcp` |
| `image.tag` | Container image tag | Chart appVersion |
| `image.pullPolicy` | Image pull policy | `IfNotPresent` |
| `imagePullSecrets` | Image pull secrets | `[]` |
| `serviceAccount.create` | Create a ServiceAccount | `true` |
| `serviceAccount.name` | Override ServiceAccount name | `""` |
| `service.type` | Service type | `ClusterIP` |
| `service.port` | Service port | `8080` |

### MCP server

| Parameter | Description | Default |
|-----------|-------------|---------|
| `mcp.logLevel` | Application log level | `INFO` |
| `mcp.cors.origins` | CORS allowed origins | `http://localhost:*,https://localhost:*` |

### Log provider

| Parameter | Description | Default |
|-----------|-------------|---------|
| `mcp.log.provider` | `kubernetes` or `loki` | `kubernetes` |
| `mcp.log.tailLines` | Number of log lines to tail | `200` |
| `mcp.log.loki.url` | Loki endpoint URL | `""` |
| `mcp.log.loki.authMode` | Auth mode: `none`, `basic`, `bearer-token`, `sa-token` | `none` |
| `mcp.log.loki.tenantId` | X-Scope-OrgID header | `""` |
| `mcp.log.loki.saTokenPath` | Override SA token path | `""` |
| `mcp.log.loki.labels.namespace` | Loki namespace label | `""` |
| `mcp.log.loki.labels.pod` | Loki pod label | `""` |
| `mcp.log.loki.connectTimeout` | Connect timeout (ms) | `5000` |
| `mcp.log.loki.readTimeout` | Read timeout (ms) | `30000` |

### Metrics provider

| Parameter | Description | Default |
|-----------|-------------|---------|
| `mcp.metrics.provider` | `pod-scraping` or `prometheus` | `pod-scraping` |
| `mcp.metrics.stepSeconds` | Default step interval (seconds) | `60` |
| `mcp.metrics.maxRangeMinutes` | Max query time range (minutes) | `10080` |
| `mcp.metrics.maxSamples` | Max samples per query (0=unlimited) | `10000` |
| `mcp.metrics.prometheus.url` | Prometheus endpoint URL | `""` |
| `mcp.metrics.prometheus.authMode` | Auth mode: `none`, `basic`, `bearer-token`, `sa-token` | `none` |
| `mcp.metrics.prometheus.saTokenPath` | Override SA token path | `""` |
| `mcp.metrics.prometheus.connectTimeout` | Connect timeout (ms) | `5000` |
| `mcp.metrics.prometheus.readTimeout` | Read timeout (ms) | `30000` |

### Tracing

| Parameter | Description | Default |
|-----------|-------------|---------|
| `mcp.tracing.enabled` | Enable OpenTelemetry tracing | `false` |
| `mcp.tracing.endpoint` | OTLP exporter endpoint | `http://localhost:4317` |
| `mcp.tracing.serviceName` | OTEL service name | `strimzi-mcp` |
| `mcp.tracing.protocol` | OTLP protocol (`""` for grpc, `http/protobuf`) | `""` |
| `mcp.tracing.propagators` | OTEL propagators | `tracecontext,baggage` |

### Sampling

| Parameter | Description | Default |
|-----------|-------------|---------|
| `mcp.sampling.triageMaxTokens` | Max tokens for triage | `200` |
| `mcp.sampling.analysisMaxTokens` | Max tokens for analysis | `1500` |

### Topics

| Parameter | Description | Default |
|-----------|-------------|---------|
| `mcp.topics.defaultPageSize` | Default page size | `100` |
| `mcp.topics.maxListSize` | Max total topics returned | `5000` |

### Events

| Parameter | Description | Default |
|-----------|-------------|---------|
| `mcp.events.maxRelatedResources` | Max related resources per cluster | `50` |

### Diagnostic

| Parameter | Description | Default |
|-----------|-------------|---------|
| `mcp.diagnostic.restartThreshold` | Restart count threshold | `3` |

### Watch

| Parameter | Description | Default |
|-----------|-------------|---------|
| `mcp.watch.enabled` | Enable resource watches | `true` |
| `mcp.watch.reconnectInitialDelayMs` | Initial reconnect delay (ms) | `1000` |
| `mcp.watch.reconnectMaxDelayMs` | Max reconnect delay (ms) | `60000` |
| `mcp.watch.reconnectMaxAttempts` | Max reconnect attempts | `10` |

### Completion

| Parameter | Description | Default |
|-----------|-------------|---------|
| `mcp.completion.cacheTtlSeconds` | Completion cache TTL (seconds) | `5` |

### Guardrails

| Parameter | Description | Default |
|-----------|-------------|---------|
| `mcp.guardrail.logRedaction.enabled` | Enable log redaction | `true` |
| `mcp.guardrail.logRedaction.customPatterns` | Additional redaction regexes | `[]` |
| `mcp.guardrail.maxResponseBytes` | Max response size (bytes) | `500000` |
| `mcp.guardrail.rateLimit.logRpm` | Log tool rate limit (RPM, 0=unlimited) | `0` |
| `mcp.guardrail.rateLimit.metricsRpm` | Metrics tool rate limit | `0` |
| `mcp.guardrail.rateLimit.generalRpm` | General tool rate limit | `0` |

### TLS

| Parameter | Description | Default |
|-----------|-------------|---------|
| `tls.trustAll` | Trust all TLS certificates | `false` |

### Deployment

| Parameter | Description | Default |
|-----------|-------------|---------|
| `resources.requests.cpu` | CPU request | `100m` |
| `resources.requests.memory` | Memory request | `384Mi` |
| `resources.limits.cpu` | CPU limit | `500m` |
| `resources.limits.memory` | Memory limit | `768Mi` |
| `strategy` | Deployment strategy | `{}` |
| `terminationGracePeriodSeconds` | Termination grace period | `30` |
| `topologySpreadConstraints` | Topology spread constraints | `[]` |
| `nodeSelector` | Node selector | `{}` |
| `tolerations` | Tolerations | `[]` |
| `affinity` | Affinity rules | `{}` |
| `podAnnotations` | Pod annotations | `{}` |
| `podLabels` | Additional pod labels | `{}` |
| `extraVolumes` | Extra volumes (e.g., TLS certs) | `[]` |
| `extraVolumeMounts` | Extra volume mounts | `[]` |
| `env` | Additional environment variables | `{}` |
| `envFrom` | ConfigMap/Secret references | `[]` |

### Optional features

| Parameter | Description | Default |
|-----------|-------------|---------|
| `sensitive.namespaces` | Namespaces for sensitive RBAC | `[]` |
| `loki.enabled` | Enable Loki ClusterRole (OpenShift) | `false` |
| `monitoring.enabled` | Enable monitoring RBAC bindings | `false` |
| `podMonitor.enabled` | Enable PodMonitor | `false` |
| `podMonitor.labels` | PodMonitor labels | `{}` |
| `podDisruptionBudget.enabled` | Enable PDB | `false` |
| `podDisruptionBudget.minAvailable` | Min available pods | `1` |
| `route.enabled` | Enable OpenShift Route | `false` |
| `route.host` | Route hostname (empty=auto) | `""` |
| `route.tls.termination` | TLS termination type | `edge` |
| `ingress.enabled` | Enable Kubernetes Ingress | `false` |

See [values.yaml](values.yaml) for all available settings.

For detailed installation instructions, see the [installation guide](../../../docs/strimzi-mcp/installation.md).
