+++
title = 'Installation'
weight = 1
+++

This guide describes how to deploy the MCP Server for Strimzi to a Kubernetes cluster.
For a local development setup using `quarkus:dev`, see the [getting started guide](../getting-started.md).

## Prerequisites

Before you begin, ensure you have:

- A Kubernetes cluster with `kubectl` configured
- The Strimzi operator deployed (see [Deploy Strimzi](#deploy-strimzi))
- An AI assistant that supports MCP (Claude Desktop, Claude Code, or similar)

## Deploy Strimzi

If you do not have Strimzi deployed, follow these steps.

### Using the setup script (recommended)

The setup script automates the entire deployment process:

```bash
./dev/scripts/setup-strimzi.sh deploy
```

This script performs the following steps:

1. Deploys the Strimzi operator to the `strimzi` namespace
2. Waits for the operator to be ready
3. Creates the `strimzi-kafka` namespace
4. Deploys a sample Kafka cluster named `mcp-cluster`
5. Waits for the cluster to be ready

You can pass additional flags to deploy observability infrastructure alongside Strimzi:

```bash
# Deploy Strimzi with Prometheus for metrics collection
./dev/scripts/setup-strimzi.sh deploy --prometheus

# Deploy Strimzi with Loki for log collection (OpenShift only)
./dev/scripts/setup-strimzi.sh deploy --loki

# Deploy Strimzi with both
./dev/scripts/setup-strimzi.sh deploy --prometheus --loki

# Deploy Strimzi with KafkaConnect and a sample connector
./dev/scripts/setup-strimzi.sh deploy --connect

# Deploy Strimzi with Jaeger for tracing
./dev/scripts/setup-strimzi.sh deploy --jaeger

# Deploy Strimzi with Drain Cleaner for graceful node drains
./dev/scripts/setup-strimzi.sh deploy --drain-cleaner

# Deploy Strimzi with a secondary Kafka cluster and MirrorMaker2 for cross-cluster replication
./dev/scripts/setup-strimzi.sh deploy --mirror-maker
```

On OpenShift, add the `--ocp` flag to use Route listeners instead of NodePort:

```bash
./dev/scripts/setup-strimzi.sh deploy --ocp
```

### Manual deployment

If you prefer to deploy manually:

```bash
# Deploy the Strimzi operator
kubectl apply -k dev/manifests/strimzi/strimzi-operator/

# Wait for the operator to be ready
kubectl wait --for=condition=Available \
  deployment/strimzi-cluster-operator \
  -n strimzi \
  --timeout=120s

# Deploy a Kafka cluster
kubectl apply -k dev/manifests/strimzi/kafka/

# Wait for the cluster to be ready
kubectl wait kafka/mcp-cluster \
  --for=condition=Ready \
  -n strimzi-kafka \
  --timeout=300s
```

### Verify Strimzi installation

Check that Strimzi is running correctly:

```bash
# Check the operator
kubectl get deployment -n strimzi

# Check the Kafka cluster
kubectl get kafka -n strimzi-kafka

# Check the pods
kubectl get pods -n strimzi-kafka
```

You should see the operator running and the Kafka cluster in a Ready state.

## Kubernetes deployment

### Build container image

Build and push the container image to your registry:

```bash
cd strimzi-mcp

# Build and push to your registry
../mvnw clean package -DskipTests \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.push=true \
  -Dquarkus.container-image.registry=quay.io \
  -Dquarkus.container-image.group=your-org \
  -Dquarkus.container-image.name=strimzi-mcp \
  -Dquarkus.container-image.tag=latest
```

The default image location is `quay.io/streamshub/strimzi-mcp:latest`.

### Install with Helm

The Helm chart is the recommended way to install the MCP server.
It supports all deployment configurations through `values.yaml` overrides.

```bash
# Basic installation
helm install streamshub-mcp-strimzi install/strimzi-mcp/helm/ \
  -n streamshub-mcp --create-namespace

# Production: 2 replicas, higher resources
helm install streamshub-mcp-strimzi install/strimzi-mcp/helm/ \
  -n streamshub-mcp --create-namespace \
  --set replicaCount=2 \
  --set resources.requests.cpu=250m \
  --set resources.requests.memory=512Mi \
  --set resources.limits.cpu=1 \
  --set resources.limits.memory=1Gi
```

Install from a release tag without cloning the repository:

```bash
helm install streamshub-mcp-strimzi oci://quay.io/streamshub/charts/streamshub-mcp-strimzi \
  --version <version> \
  -n streamshub-mcp --create-namespace
```

Configure backend providers:

```bash
# Prometheus metrics
helm upgrade streamshub-mcp-strimzi install/strimzi-mcp/helm/ -n streamshub-mcp \
  --set mcp.metrics.provider=prometheus \
  --set mcp.metrics.prometheus.url=http://prometheus.monitoring:9090

# Loki logs
helm upgrade streamshub-mcp-strimzi install/strimzi-mcp/helm/ -n streamshub-mcp \
  --set mcp.log.provider=loki \
  --set mcp.log.loki.url=http://loki-gateway.openshift-logging:8080

# OpenTelemetry tracing
helm upgrade streamshub-mcp-strimzi install/strimzi-mcp/helm/ -n streamshub-mcp \
  --set mcp.tracing.enabled=true \
  --set mcp.tracing.endpoint=http://jaeger-collector.observability:4317
```

Enable optional features:

```bash
# Sensitive RBAC in specific namespaces
helm upgrade streamshub-mcp-strimzi install/strimzi-mcp/helm/ -n streamshub-mcp \
  --set sensitive.namespaces={strimzi-kafka,kafka-prod}

# Loki RBAC for OpenShift Logging
helm upgrade streamshub-mcp-strimzi install/strimzi-mcp/helm/ -n streamshub-mcp \
  --set loki.enabled=true

# PodMonitor for Prometheus scraping
helm upgrade streamshub-mcp-strimzi install/strimzi-mcp/helm/ -n streamshub-mcp \
  --set podMonitor.enabled=true

# Ingress (also works on OpenShift)
helm upgrade streamshub-mcp-strimzi install/strimzi-mcp/helm/ -n streamshub-mcp \
  --set ingress.enabled=true \
  --set ingress.hosts[0].host=mcp.example.com
```

See [`install/strimzi-mcp/helm/values.yaml`](../../install/strimzi-mcp/helm/values.yaml) for all available settings.

### Install with Kustomize

#### Choose an overlay

The deployment manifests use [Kustomize](https://kustomize.io/) with a base and environment-specific overlays:

| Overlay | Command | Use case |
|---------|---------|----------|
| `base` | `kubectl apply -k install/strimzi-mcp/kustomize/base/` | Minimal deployment with defaults |
| `overlays/dev` | `kubectl apply -k install/strimzi-mcp/kustomize/overlays/dev/` | Local development (Kind, minikube) |
| `overlays/dev-openshift` | `kubectl apply -k install/strimzi-mcp/kustomize/overlays/dev-openshift/` | Local development (OpenShift) |
| `overlays/prod` | `kubectl apply -k install/strimzi-mcp/kustomize/overlays/prod/` | Production Kubernetes |
| `overlays/prod-openshift` | `kubectl apply -k install/strimzi-mcp/kustomize/overlays/prod-openshift/` | Production OpenShift |

The **prod** overlay adds:

- 2 replicas for high availability
- Higher resource requests and limits
- Optional ConfigMap and Secret references for configuration via `envFrom`

The **prod-openshift** overlay extends prod with an edge-terminated TLS Route for external access.

The **dev-openshift** overlay extends dev with the same Route configuration, for local development on OpenShift (CRC, Red Hat OpenShift Local). It is deployed automatically by `dev-deploy.sh --ocp`.

### Deploy a released version

Install directly from a release tag without cloning the repository.
Replace `<version>` with a tag from the [releases page](https://github.com/streamshub/streamshub-mcp/releases) (e.g., `v0.1.0`):

```bash
# Production Kubernetes
kubectl apply -k "https://github.com/streamshub/streamshub-mcp/install/strimzi-mcp/kustomize/overlays/prod?ref=<version>"

# Production OpenShift
kubectl apply -k "https://github.com/streamshub/streamshub-mcp/install/strimzi-mcp/kustomize/overlays/prod-openshift?ref=<version>"

# Base (minimal deployment)
kubectl apply -k "https://github.com/streamshub/streamshub-mcp/install/strimzi-mcp/kustomize/base?ref=<version>"
```

### Deploy from a local clone

Deploy using the overlay that matches your environment:

```bash
# Production Kubernetes
kubectl apply -k install/strimzi-mcp/kustomize/overlays/prod/

# Production OpenShift
kubectl apply -k install/strimzi-mcp/kustomize/overlays/prod-openshift/

# Verify the deployment
kubectl -n streamshub-mcp rollout status deployment/streamshub-mcp-strimzi
kubectl -n streamshub-mcp get pods
```

To override the image tag or registry:

```bash
cd install/strimzi-mcp/kustomize/base
kustomize edit set image quay.io/streamshub/strimzi-mcp=my-registry.io/my-org/strimzi-mcp:1.0.0
kubectl apply -k ../overlays/prod/
```

### Deployment resources

The `install/strimzi-mcp/kustomize/base/` directory contains the following resources:

| File | Resource | Purpose |
|------|----------|---------|
| `namespace.yaml` | Namespace | Creates the `streamshub-mcp` namespace |
| `serviceaccount.yaml` | ServiceAccount | Provides identity for the MCP server |
| `clusterrole.yaml` | ClusterRole | Grants read-only permissions for non-sensitive resources |
| `clusterrolebinding.yaml` | ClusterRoleBinding | Binds the ClusterRole to the ServiceAccount |
| `deployment.yaml` | Deployment | Deploys the MCP server with health probes |
| `service.yaml` | Service | Exposes the MCP server on port 8080 |
| `../optional/role-sensitive.yaml` | Role | Optional per-namespace permissions for sensitive resources |
| `../optional/rolebinding-sensitive.yaml` | RoleBinding | Companion RoleBinding for the sensitive Role |

For the full directory structure and overlay details, see the [Kustomize README](../../install/strimzi-mcp/kustomize/README.md).

### RBAC configuration

The MCP server uses a two-tier RBAC model for security.

**ClusterRole (default, non-sensitive resources):**

The ClusterRole grants read-only access to:

- Strimzi custom resources -- `get`, `list`, `watch`
- Deployments -- `get`, `list`, `watch`
- Pods and logs -- `get`, `list`
- Services and ConfigMaps -- `get`, `list`
- Routes and Ingresses -- `get`, `list`
- Leases -- `get`, `list`
- ValidatingWebhookConfigurations -- `get`, `list`

**Role (opt-in per namespace, sensitive resources):**

The optional Role grants access to:

- Secrets -- `get` (for certificate metadata only, not secret data)
- Pods/proxy -- `get` (for direct metrics scraping from pods)

Deploy the sensitive Role and its RoleBinding only in namespaces where you need these features:

```bash
kubectl apply -f install/strimzi-mcp/kustomize/optional/role-sensitive.yaml -n kafka-namespace
kubectl apply -f install/strimzi-mcp/kustomize/optional/rolebinding-sensitive.yaml -n kafka-namespace
```

## Security model

The MCP Server for Strimzi is designed for platform engineering and SRE teams that already have Kubernetes access.
It provides a read-only view of Strimzi-managed resources and relies on Kubernetes RBAC as its sole authorization mechanism.

### Authentication and authorization

The MCP server has **no built-in authentication or authorization**.
It inherits the Kubernetes identity of its runtime environment:

- **Kubernetes deployment** -- The server uses its ServiceAccount token. The ServiceAccount's RBAC rules determine which resources the server can access.
- **Local development** (`quarkus:dev`) -- The server uses the current user's kubeconfig. All tools operate with the user's Kubernetes permissions.

The MCP HTTP endpoint (`/mcp`) does not require credentials.
Any client that can reach the endpoint can invoke all tools.

### Securing the MCP endpoint

In production, restrict access to the MCP endpoint using infrastructure-level controls:

- **Kubernetes NetworkPolicy** -- Limit which pods or namespaces can reach the MCP server
- **Authenticating reverse proxy** -- Deploy an OAuth2 proxy, mTLS termination, or API gateway in front of the MCP server
- **Service mesh** -- Use Istio, Linkerd, or similar to enforce mutual TLS and authorization policies
- **Ingress authentication** -- Configure ingress controller annotations for authentication (e.g., `nginx.ingress.kubernetes.io/auth-url`)

CORS is restricted to `localhost` origins by default.
Configure `quarkus.http.cors.origins` to allow other origins (see [configuration](configuration.md)).

### Data safety

All tools are read-only (`readOnlyHint=true`, `destructiveHint=false`).
The server never creates, modifies, or deletes Kubernetes resources.

Sensitive data is protected by multiple layers:

- **Credential secrets** are never exposed. The server reads Secrets only for certificate metadata (issuer, expiry) -- never for secret data such as passwords, keys, or tokens.
- **Log redaction** automatically strips sensitive patterns (tokens, passwords, API keys) from log output before returning it to the client.
- **Response size limits** truncate large responses to prevent excessive data transfer.
- **Rate limiting** throttles requests per category to prevent resource exhaustion.

### Production configuration

The prod overlay references an optional ConfigMap (`strimzi-mcp-config`) and Secret (`strimzi-mcp-secrets`) via `envFrom`.
Create these before deploying to configure the server:

```bash
# Create a ConfigMap with your configuration
kubectl -n streamshub-mcp create configmap strimzi-mcp-config \
  --from-literal=MCP_LOG_TAIL_LINES=500 \
  --from-literal=MCP_METRICS_PROVIDER=streamshub-prometheus \
  --from-literal=QUARKUS_REST_CLIENT_PROMETHEUS_URL=http://prometheus.monitoring:9090

# Create a Secret for sensitive values
kubectl -n streamshub-mcp create secret generic strimzi-mcp-secrets \
  --from-literal=QUARKUS_REST_CLIENT_PROMETHEUS_USERNAME=your-username \
  --from-literal=QUARKUS_REST_CLIENT_PROMETHEUS_PASSWORD=your-password
```

Both are marked as `optional` so the deployment works without them.
See the [configuration guide](configuration.md) for all available settings.

## Accessing the server

### Port-forward for development

Use port-forwarding to access the server from your local machine:

```bash
kubectl -n streamshub-mcp port-forward svc/streamshub-mcp-strimzi 8080:8080
```

Configure your MCP client to use `http://localhost:8080/mcp`.

### OpenShift route

The `prod-openshift` and `dev-openshift` overlays include an edge-terminated Route automatically:

```bash
kubectl apply -k install/strimzi-mcp/kustomize/overlays/prod-openshift/

# Get the Route hostname
ROUTE_HOST=$(oc -n streamshub-mcp get route streamshub-mcp-strimzi -o jsonpath='{.spec.host}')
echo "MCP Server URL: https://${ROUTE_HOST}/mcp"
```

Alternatively, create a Route manually:

```bash
oc -n streamshub-mcp create route edge streamshub-mcp-strimzi \
  --service=streamshub-mcp-strimzi \
  --port=http
```

Configure your MCP client with the HTTPS URL: `https://<route-hostname>/mcp`.

### Kubernetes ingress

Create an Ingress resource for external access on standard Kubernetes:

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: streamshub-mcp-strimzi
  namespace: streamshub-mcp
  annotations:
    cert-manager.io/cluster-issuer: letsencrypt-prod
spec:
  ingressClassName: nginx
  tls:
  - hosts:
    - mcp.example.com
    secretName: strimzi-mcp-tls
  rules:
  - host: mcp.example.com
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: streamshub-mcp-strimzi
            port:
              number: 8080
```

Apply the Ingress and configure your MCP client with: `https://mcp.example.com/mcp`.

## Verification

### Health checks

Verify the server is healthy:

```bash
# Liveness probe
curl http://localhost:8080/q/health/live

# Readiness probe
curl http://localhost:8080/q/health/ready

# Full health check
curl http://localhost:8080/q/health
```

### Test MCP endpoint

Test the MCP endpoint directly:

```bash
# List available tools
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

You should see a JSON response listing all available MCP tools.

## Troubleshooting installation

### Server does not start

If the server fails to start in Kubernetes:

```bash
# Check Kubernetes connectivity
kubectl cluster-info

# Verify Strimzi CRDs exist
kubectl get crd | grep strimzi

# Check server logs
kubectl -n streamshub-mcp logs deployment/streamshub-mcp-strimzi
```

### RBAC permission errors

If you see permission errors:

```bash
# Verify you have the necessary permissions
kubectl auth can-i list kafkas --all-namespaces

# Check the ServiceAccount exists
kubectl get serviceaccount -n streamshub-mcp

# Check the ClusterRoleBinding exists
kubectl get clusterrolebinding | grep streamshub-mcp
```

### Cannot connect from AI assistant

If your AI assistant cannot connect:

1. Verify the server is running: `curl http://localhost:8080/q/health`
2. Check firewall rules allow connections on port 8080
3. Verify your MCP client configuration has the correct URL
4. Check server logs for connection attempts

For more help, see the [troubleshooting guide](troubleshooting.md).

## Next steps

- **[Configuration](configuration.md)** -- Configure Loki, Prometheus, and other settings
- **[Tools reference](tools/)** -- Explore available tools and their parameters
- **[Usage examples](usage-examples.md)** -- See practical examples and workflows
- **[Troubleshooting](troubleshooting.md)** -- Resolve common issues
