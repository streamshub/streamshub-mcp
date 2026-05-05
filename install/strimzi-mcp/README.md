# Strimzi MCP Server -- Kustomize Installation

This directory contains [Kustomize](https://kustomize.io/) manifests for deploying the Strimzi MCP Server to Kubernetes.

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
├── optional/                    # Per-namespace sensitive RBAC
│   ├── role-sensitive.yaml
│   └── rolebinding-sensitive.yaml
└── overlays/
    ├── dev/                     # Local development
    │   ├── kustomization.yaml
    │   └── deployment-patch.yaml
    ├── dev-openshift/           # Local development (OpenShift)
    │   ├── kustomization.yaml
    │   └── route.yaml
    ├── prod/                    # Production (Kubernetes)
    │   ├── kustomization.yaml
    │   └── deployment-patch.yaml
    └── prod-openshift/          # Production (OpenShift)
        ├── kustomization.yaml
        └── route.yaml
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
- Optional ConfigMap (`strimzi-mcp-config`) and Secret (`strimzi-mcp-secrets`) for configuration via `envFrom`

```bash
kubectl apply -k install/strimzi-mcp/overlays/prod/
```

### Prod-OpenShift

Extends the prod overlay with an OpenShift Route for external access.

- Edge-terminated TLS Route
- Redirects insecure traffic to HTTPS

```bash
kubectl apply -k install/strimzi-mcp/overlays/prod-openshift/
```

## Configuration

The prod overlay references an optional ConfigMap and Secret for environment-based configuration.
See the [configuration guide](../../docs/strimzi-mcp/configuration.md) for all available settings.

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
```

## Customization

Override the container image:

```bash
cd install/strimzi-mcp/base
kustomize edit set image quay.io/streamshub/strimzi-mcp=my-registry.io/my-org/strimzi-mcp:1.0.0
kubectl apply -k ../overlays/prod/
```

## Sensitive RBAC

The sensitive Role grants access to Secrets and `pods/proxy` (for direct metrics scraping).
These are not included in any overlay by default.

To grant sensitive permissions in the namespaces where you need them:

```bash
kubectl apply -f install/strimzi-mcp/optional/role-sensitive.yaml -n <kafka-namespace>
kubectl apply -f install/strimzi-mcp/optional/rolebinding-sensitive.yaml -n <kafka-namespace>
```

## Verification

```bash
# Dry-run to validate manifests
kubectl apply -k install/strimzi-mcp/overlays/prod/ --dry-run=client

# Check deployment status
kubectl -n streamshub-mcp rollout status deployment/streamshub-strimzi-mcp

# Verify pods are running
kubectl -n streamshub-mcp get pods
```
