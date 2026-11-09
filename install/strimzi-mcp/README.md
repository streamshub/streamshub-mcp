# MCP Server for Strimzi -- Kustomize Installation

This directory contains [Kustomize](https://kustomize.io/) manifests for deploying the MCP Server for Strimzi to Kubernetes.

## Directory structure

```
install/strimzi-mcp/
├── base/                        # Base resources (all environments)
│   ├── kustomization.yaml
│   ├── namespace.yaml
│   ├── serviceaccount.yaml
│   ├── clusterrole.yaml
│   ├── clusterrolebinding.yaml
│   ├── deployment.yaml
│   └── service.yaml
├── optional/                    # Optional resources (applied manually)
│   ├── role-sensitive.yaml
│   ├── rolebinding-sensitive.yaml
│   ├── clusterrole-loki-application-view.yaml
│   ├── clusterrolebinding-loki-application-view.yaml
│   └── podmonitor.yaml
└── overlays/
    ├── dev/                     # Local development
    │   ├── kustomization.yaml
    │   └── deployment-patch.yaml
    ├── dev-openshift/           # Local development (OpenShift)
    │   ├── kustomization.yaml
    │   └── route.yaml
    ├── prod/                    # Production (Kubernetes)
    │   ├── kustomization.yaml
    │   ├── deployment-patch.yaml
    │   └── networkpolicy.yaml
    └── prod-openshift/          # Production (OpenShift)
        ├── kustomization.yaml
        ├── route.yaml
        └── networkpolicy.yaml
```

## Overlays

### Base

Contains the core resources shared by all environments: namespace, service account, RBAC, deployment, and service.

### Dev

For local development with images built and loaded into a local cluster (Kind, minikube).

- `imagePullPolicy: Always`

```bash
kubectl apply -k install/strimzi-mcp/overlays/dev/
```

### Dev-OpenShift

Extends the dev overlay with an OpenShift Route for external access (used by `dev-deploy.sh --ocp`).

- Edge-terminated TLS Route
- `imagePullPolicy: Always` (inherited from dev)

```bash
kubectl apply -k install/strimzi-mcp/overlays/dev-openshift/
```

### Prod

For production Kubernetes deployments.

- 2 replicas
- Higher resource requests and limits
- Finite rate limits (30 log / 60 metrics / 120 general requests per minute, counted per pod and shared by all clients of that pod) from the generated `strimzi-mcp-defaults` ConfigMap
- NetworkPolicy admitting only pods in the `streamshub-mcp` namespace (the endpoint has no authentication; add a policy for your clients' namespaces and, if a PodMonitor scrapes the server, for the Prometheus namespace on port `management` only)
- Optional ConfigMap (`strimzi-mcp-config`) and Secret (`strimzi-mcp-secrets`) for configuration via `envFrom`

```bash
kubectl apply -k install/strimzi-mcp/overlays/prod/
```

### Prod-OpenShift

Extends the prod overlay with an OpenShift Route for external access.

- Edge-terminated TLS Route
- Redirects insecure traffic to HTTPS
- NetworkPolicy admitting the OpenShift router (MCP port) and cluster monitoring (management port only) (requires OpenShift 4.20 or later, which uses the OVN-Kubernetes `policy-group.network.openshift.io/ingress` namespace label)

> **Warning**: The Route is unauthenticated. Anyone who can reach the router can call every MCP tool.
> Put an authenticating proxy in front of it or remove the Route in shared environments.

```bash
kubectl apply -k install/strimzi-mcp/overlays/prod-openshift/
```

## Configuration

The prod overlay references an optional ConfigMap and Secret for environment-based configuration.
See the [configuration guide](../../documentation/assemblies/assembly-configuring.adoc) for all available settings.

Example ConfigMap:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: strimzi-mcp-config
  namespace: streamshub-mcp
data:
  MCP_LOG_TAIL_LINES: "500"
  MCP_METRICS_PROVIDER: "streamshub-prometheus"
  QUARKUS_REST_CLIENT_PROMETHEUS_URL: "http://prometheus.monitoring:9090"
  # OpenSearch log provider (uncomment to use instead of default Kubernetes logs)
  # MCP_LOG_PROVIDER: "streamshub-opensearch"
  # QUARKUS_REST_CLIENT_OPENSEARCH_URL: "http://opensearch.opensearch:9200"
  # MCP_LOG_OPENSEARCH_AUTH_MODE: "none"
```

## Customization

The base manifests use `quay.io/streamshub/strimzi-mcp:latest`, which Kubernetes always pulls. Release tags pin the version in `base/kustomization.yaml`.
Override the container image:

```bash
cd install/strimzi-mcp/base
kustomize edit set image quay.io/streamshub/strimzi-mcp=my-registry.io/my-org/strimzi-mcp:1.0.0
kubectl apply -k ../overlays/prod/
```

## Sensitive RBAC

The sensitive Role grants access to Secrets and `pods/proxy` (for direct metrics scraping).
These are not included in any overlay by default. The default `streamshub-pod-scraping` metrics provider needs `pods/proxy`;
without this Role, switch to the Prometheus provider (`MCP_METRICS_PROVIDER=streamshub-prometheus`).

To grant sensitive permissions in the namespaces where you need them:

```bash
kubectl apply -f install/strimzi-mcp/optional/role-sensitive.yaml -n <kafka-namespace>
kubectl apply -f install/strimzi-mcp/optional/rolebinding-sensitive.yaml -n <kafka-namespace>
```

## OpenSearch

When using the OpenSearch log provider (`MCP_LOG_PROVIDER=streamshub-opensearch`), no special Kubernetes RBAC is required.
The MCP server connects to OpenSearch over HTTP/HTTPS and authenticates using the configured auth mode (`none`, `basic`, `bearer-token`, or `sa-token`).

Configure via environment variables or the ConfigMap:

```yaml
MCP_LOG_PROVIDER: "streamshub-opensearch"
QUARKUS_REST_CLIENT_OPENSEARCH_URL: "http://opensearch.opensearch:9200"
MCP_LOG_OPENSEARCH_AUTH_MODE: "none"
```

For OpenShift with ECK Operator (basic auth + TLS):

```yaml
MCP_LOG_PROVIDER: "streamshub-opensearch"
QUARKUS_REST_CLIENT_OPENSEARCH_URL: "https://elasticsearch-es-http.elasticsearch-logging.svc:9200"
MCP_LOG_OPENSEARCH_AUTH_MODE: "basic"
QUARKUS_TLS_TRUST_ALL: "true"
```

The same provider works with OpenSearch -- just point the URL at your OpenSearch endpoint.

See the [configuration guide](../../documentation/assemblies/assembly-configuring.adoc) for field mapping and all available settings.

## Loki RBAC (OpenShift Logging)

When using the Loki log provider with OpenShift Logging (LokiStack), the service account needs permission to read application logs via the Loki gateway.
OpenShift Logging v6.x does not auto-create the required `cluster-logging-application-view` ClusterRole (v5.x did), so you may need to create it manually:

```bash
kubectl apply -f install/strimzi-mcp/optional/clusterrole-loki-application-view.yaml
kubectl apply -f install/strimzi-mcp/optional/clusterrolebinding-loki-application-view.yaml
```

Without this, Loki queries will return `403 Forbidden` with the message "You don't have permission to access this tenant."

> **Note**: The `dev-deploy.sh --loki` script creates this ClusterRole automatically if it is missing.

## Metrics monitoring

The MCP server exposes Prometheus metrics at `/q/metrics` on the management port 9000 (container port `management`,
not exposed by the Service; `/q/health` is there too). To enable scraping, apply the optional PodMonitor
(requires the Prometheus Operator CRD to be installed):

```bash
kubectl apply -f install/strimzi-mcp/optional/podmonitor.yaml -n streamshub-mcp
```

This creates a PodMonitor that scrapes `mcp.tool.calls` and `mcp.tool.call.duration` metrics from the MCP server pods.
If you use a ServiceMonitor instead, add a `management` port (9000) to the Service in your own overlay.

## Verification

```bash
# Dry-run to validate manifests
kubectl apply -k install/strimzi-mcp/overlays/prod/ --dry-run=client

# Check deployment status
kubectl -n streamshub-mcp rollout status deployment/streamshub-mcp-strimzi

# Verify pods are running
kubectl -n streamshub-mcp get pods
```
