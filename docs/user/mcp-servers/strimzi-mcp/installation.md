---
title: "Installation"
weight: 1
---

This guide covers deploying the Strimzi MCP Server locally for development or to a Kubernetes cluster for production use.

## Prerequisites

Before installing, ensure you have:

- **Kubernetes cluster** with kubectl configured
- **Strimzi operator** deployed (see [Deploy Strimzi](#deploy-strimzi))
- **Java 21+** and **Maven 3.8+** (for local development)
- **AI assistant** supporting MCP (Claude Desktop, Claude Code, etc.)

## Quick Start

Get up and running in 5 minutes:

### 1. Deploy Strimzi

```bash
git clone https://github.com/streamshub/streamshub-mcp.git
cd streamshub-mcp
./dev/scripts/setup-strimzi.sh
```

This deploys the Strimzi operator and a sample Kafka cluster named `mcp-cluster`.

### 2. Start the MCP Server

```bash
cd strimzi-mcp
mvn quarkus:dev
```

The server starts on `http://localhost:8080/mcp`

### 3. Connect Your AI Assistant

**Claude Code**:
```bash
claude mcp add --transport http strimzi http://localhost:8080/mcp
```

**Claude Desktop**: Add to config file (`~/Library/Application Support/Claude/claude_desktop_config.json` on macOS):
```json
{
  "mcpServers": {
    "strimzi": {
      "transport": "http",
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

Restart your AI assistant.

### 4. Verify Installation

Ask your AI assistant:
- "List all Kafka clusters"
- "What's the status of mcp-cluster?"
- "Diagnose issues with mcp-cluster"

## Local Development

### Build and Run

```bash
# Navigate to strimzi-mcp directory
cd strimzi-mcp

# Build the project
mvn clean package

# Start in development mode (with hot reload)
mvn quarkus:dev
```

The server starts on `http://localhost:8080/mcp` with:
- Hot reload enabled
- Dev UI available at `http://localhost:8080/q/dev`
- Health checks at `http://localhost:8080/q/health`

### Test with MCP Inspector

The MCP Inspector provides a web UI for testing tools:

```bash
npx @modelcontextprotocol/inspector http://localhost:8080/mcp
```

This opens a browser interface where you can:
- Browse available tools
- Test tool invocations
- View responses
- Debug issues

## Kubernetes Deployment

### Build Container Image

```bash
cd strimzi-mcp

# Build and push to registry
mvn clean package -DskipTests \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.push=true \
  -Dquarkus.container-image.registry=quay.io \
  -Dquarkus.container-image.group=your-org \
  -Dquarkus.container-image.name=strimzi-mcp \
  -Dquarkus.container-image.tag=latest
```

Default image location: `quay.io/streamshub/strimzi-mcp:latest`

### Deploy to Cluster

```bash
# Deploy all resources
kubectl apply -f install/

# Verify deployment
kubectl -n streamshub-mcp rollout status deployment/streamshub-strimzi-mcp
kubectl -n streamshub-mcp get pods
```

### Deployment Resources

The `install/` directory contains:

| File | Resource | Purpose |
|------|----------|---------|
| `001-Namespace.yaml` | Namespace | Creates `streamshub-mcp` namespace |
| `002-ServiceAccount.yaml` | ServiceAccount | Identity for the MCP server |
| `003-ClusterRole.yaml` | ClusterRole | Read-only permissions for non-sensitive resources |
| `004-ClusterRoleBinding.yaml` | ClusterRoleBinding | Binds ClusterRole to ServiceAccount |
| `005-Deployment.yaml` | Deployment | MCP server deployment with health probes |
| `006-Service.yaml` | Service | ClusterIP service on port 8080 |
| `007-Role-sensitive.yaml` | Role | Optional per-namespace permissions for sensitive resources |

### RBAC Configuration

**ClusterRole** (default, non-sensitive):
- Strimzi CRs: `get`, `list`, `watch`
- Deployments: `get`, `list`, `watch`
- Pods and logs: `get`, `list`
- Services, ConfigMaps: `get`, `list`
- Routes, Ingresses: `get`, `list`
- Leases: `get`, `list`

**Role** (opt-in per namespace, sensitive):
- Secrets: `get` (for certificate metadata only)
- Pods/proxy: `get` (for direct metrics scraping)

Deploy the sensitive Role only in namespaces where needed:

```bash
# Apply the Role in a specific namespace
kubectl apply -f install/007-Role-sensitive.yaml -n kafka-namespace

# Create RoleBinding
kubectl create rolebinding streamshub-mcp-sensitive \
  --role=streamshub-mcp-sensitive \
  --serviceaccount=streamshub-mcp:streamshub-mcp \
  -n kafka-namespace
```

## Accessing the Server

### Port-Forward (Development)

```bash
kubectl -n streamshub-mcp port-forward svc/streamshub-strimzi-mcp 8080:8080
```

Configure your MCP client to use `http://localhost:8080/mcp`

### OpenShift Route

```bash
# Create edge-terminated Route
oc -n streamshub-mcp create route edge streamshub-strimzi-mcp \
  --service=streamshub-strimzi-mcp \
  --port=http

# Get Route hostname
ROUTE_HOST=$(oc -n streamshub-mcp get route streamshub-strimzi-mcp -o jsonpath='{.spec.host}')
echo "MCP Server URL: https://${ROUTE_HOST}/mcp"
```

Configure your MCP client with: `https://<route-hostname>/mcp`

### Kubernetes Ingress

Create an Ingress resource for external access:

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

Apply and configure your MCP client with: `https://mcp.example.com/mcp`

## Deploy Strimzi

If you don't have Strimzi deployed yet:

### Using Setup Script (Recommended)

```bash
./dev/scripts/setup-strimzi.sh
```

This script:
1. Deploys Strimzi operator to `strimzi` namespace
2. Waits for operator to be ready
3. Creates `strimzi-kafka` namespace
4. Deploys a sample Kafka cluster named `mcp-cluster`
5. Waits for cluster to be ready

### Manual Deployment

```bash
# Deploy Strimzi operator
kubectl apply -k dev/manifests/strimzi/strimzi-operator/

# Wait for operator
kubectl wait --for=condition=Available \
  deployment/strimzi-cluster-operator \
  -n strimzi \
  --timeout=120s

# Deploy Kafka cluster
kubectl apply -k dev/manifests/strimzi/kafka/

# Wait for cluster
kubectl wait kafka/mcp-cluster \
  --for=condition=Ready \
  -n strimzi-kafka \
  --timeout=300s
```

### Verify Strimzi Installation

```bash
# Check operator
kubectl get deployment -n strimzi

# Check Kafka cluster
kubectl get kafka -n strimzi-kafka

# Check pods
kubectl get pods -n strimzi-kafka
```

## Verification

### Health Checks

```bash
# Liveness probe
curl http://localhost:8080/q/health/live

# Readiness probe
curl http://localhost:8080/q/health/ready

# Full health check
curl http://localhost:8080/q/health
```

### Test MCP Endpoint

```bash
# List available tools
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

## Next Steps

- **[Configuration](configuration.md)** — Configure Loki, Prometheus, and other settings
- **[Tools Reference](tools.md)** — Explore available tools
- **[Usage Examples](usage-examples.md)** — See practical examples
- **[Troubleshooting](troubleshooting.md)** — Resolve common issues

## Troubleshooting Installation

### Server Won't Start

```bash
# Check Kubernetes connectivity
kubectl cluster-info

# Verify Strimzi CRDs exist
kubectl get crd | grep strimzi

# Check server logs
mvn quarkus:dev
```

### RBAC Permission Errors

```bash
# Verify permissions
kubectl auth can-i list kafkas --all-namespaces

# Check ServiceAccount
kubectl get serviceaccount -n streamshub-mcp

# Check ClusterRoleBinding
kubectl get clusterrolebinding | grep streamshub-mcp
```

### Cannot Connect from AI Assistant

1. Verify server is running: `curl http://localhost:8080/q/health`
2. Check firewall rules
3. Verify MCP client configuration
4. Check server logs for connection attempts

For more help, see the [Troubleshooting Guide](troubleshooting.md).