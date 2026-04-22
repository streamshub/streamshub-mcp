+++
title = 'Installation'
weight = 1
+++

This guide describes how to deploy the Strimzi MCP Server to a Kubernetes cluster.
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

### Deploy to cluster

Deploy all resources to your Kubernetes cluster:

```bash
# Deploy all resources
kubectl apply -f install/

# Verify the deployment
kubectl -n streamshub-mcp rollout status deployment/streamshub-strimzi-mcp
kubectl -n streamshub-mcp get pods
```

### Deployment resources

The `install/` directory contains the following resources:

| File | Resource | Purpose |
|------|----------|---------|
| `001-Namespace.yaml` | Namespace | Creates the `streamshub-mcp` namespace |
| `002-ServiceAccount.yaml` | ServiceAccount | Provides identity for the MCP server |
| `003-ClusterRole.yaml` | ClusterRole | Grants read-only permissions for non-sensitive resources |
| `004-ClusterRoleBinding.yaml` | ClusterRoleBinding | Binds the ClusterRole to the ServiceAccount |
| `005-Deployment.yaml` | Deployment | Deploys the MCP server with health probes |
| `006-Service.yaml` | Service | Exposes the MCP server on port 8080 |
| `007-Role-sensitive.yaml` | Role | Optional per-namespace permissions for sensitive resources |

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

**Role (opt-in per namespace, sensitive resources):**

The optional Role grants access to:

- Secrets -- `get` (for certificate metadata only, not secret data)
- Pods/proxy -- `get` (for direct metrics scraping from pods)

Deploy the sensitive Role only in namespaces where you need these features:

```bash
# Apply the Role in a specific namespace
kubectl apply -f install/007-Role-sensitive.yaml -n kafka-namespace

# Create a RoleBinding to grant the permissions
kubectl create rolebinding streamshub-mcp-sensitive \
  --role=streamshub-mcp-sensitive \
  --serviceaccount=streamshub-mcp:streamshub-mcp \
  -n kafka-namespace
```

## Accessing the server

### Port-forward for development

Use port-forwarding to access the server from your local machine:

```bash
kubectl -n streamshub-mcp port-forward svc/streamshub-strimzi-mcp 8080:8080
```

Configure your MCP client to use `http://localhost:8080/mcp`.

### OpenShift route

Create an edge-terminated Route for external access on OpenShift:

```bash
# Create the Route
oc -n streamshub-mcp create route edge streamshub-strimzi-mcp \
  --service=streamshub-strimzi-mcp \
  --port=http

# Get the Route hostname
ROUTE_HOST=$(oc -n streamshub-mcp get route streamshub-strimzi-mcp -o jsonpath='{.spec.host}')
echo "MCP Server URL: https://${ROUTE_HOST}/mcp"
```

Configure your MCP client with the HTTPS URL: `https://<route-hostname>/mcp`.

### Kubernetes ingress

Create an Ingress resource for external access on standard Kubernetes:

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: streamshub-strimzi-mcp
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
            name: streamshub-strimzi-mcp
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
kubectl -n streamshub-mcp logs deployment/streamshub-strimzi-mcp
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
