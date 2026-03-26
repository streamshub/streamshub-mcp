# Strimzi MCP Server

A Quarkus application that provides Strimzi Kafka management tools via **MCP (Model Context Protocol)** for AI assistants and automation.

> **WARNING:** This project is in early alpha and under active development. APIs, tool definitions, and configuration may change without notice.


## Prerequisites

### Kubernetes Cluster with Strimzi

The MCP server requires access to a Kubernetes cluster with Strimzi installed.
Example manifests are provided in `dev/manifests/strimzi/`.

**Deploy using the setup script (recommended):**
```bash
../dev/scripts/setup-strimzi.sh
```

The script deploys the Strimzi operator and Kafka cluster sequentially, waiting for each component to become ready before proceeding.

**Or deploy manually:**
```bash
# 1. Deploy the Strimzi operator (CRDs, RBAC, operator deployment)
kubectl apply -k ../dev/manifests/strimzi/strimzi-operator/

# 2. Wait for the operator to be ready
kubectl wait --for=condition=Available deployment/strimzi-cluster-operator -n strimzi --timeout=120s

# 3. Deploy the Kafka cluster
kubectl apply -k ../dev/manifests/strimzi/kafka/

# 4. Wait for the Kafka cluster to be ready
kubectl wait kafka/mcp-cluster --for=condition=Ready -n strimzi-kafka --timeout=300s
```

**Tear down:**
```bash
../dev/scripts/setup-strimzi.sh teardown
```

## Quick Start

### 1. Start the Server

```bash
mvn clean package
mvn quarkus:dev
```

### 2. Configure AI Assistants

Add to Claude Code:

```shell
claude mcp add --transport http strimzi http://localhost:8080/mcp
```

## Available Tools

The server provides tools for managing Strimzi operators, Kafka clusters, topics, and node pools. All tools support **smart discovery** - the namespace parameter is always optional, and tools automatically search across the entire cluster when omitted.

Use the MCP inspector to browse all available tools and their parameters:

```bash
npx @modelcontextprotocol/inspector http://localhost:8080/mcp
```

## Prompt Templates

MCP prompt templates encode Strimzi domain knowledge and guide LLMs through structured diagnostic workflows. Instead of relying on the LLM to figure out what to check, prompts tell it exactly which tools to call and in what order.

| Prompt | Parameters | Description |
|--------|-----------|-------------|
| `diagnose-cluster-issue` | `cluster_name` (required), `namespace`, `symptom` | Step-by-step cluster diagnosis: checks status, node pools, operator logs, pod health, and correlates findings to identify root causes. |
| `troubleshoot-connectivity` | `cluster_name` (required), `namespace`, `listener_name` | Connectivity troubleshooting: checks listeners, bootstrap addresses, listener accessibility by type, and pod health. |

**How they work**: The MCP client discovers available prompts, the user selects one and fills in the parameters, and the client injects the structured instructions into the LLM conversation. The LLM then follows the steps, calling the MCP tools automatically.

## Resource Templates

MCP resource templates expose Strimzi data as structured JSON that clients can attach to conversations for immediate context — without the LLM needing to call tools first.

| Resource URI | Description |
|-------------|-------------|
| `strimzi://kafka.strimzi.io/namespaces/{namespace}/kafkas/{name}/status` | Kafka cluster status: readiness, version, replicas, listeners, authentication, and storage configuration. |
| `strimzi://kafka.strimzi.io/namespaces/{namespace}/kafkas/{name}/topology` | Cluster topology: node pools with roles, replica counts, and storage. |
| `strimzi://kafka.strimzi.io/namespaces/{namespace}/kafkanodepools/{name}/status` | KafkaNodePool status, ready replicas, roles, and storage configuration. |
| `strimzi://kafka.strimzi.io/namespaces/{namespace}/kafkatopics/{name}/status` | KafkaTopic status, conditions, and topic configuration (partitions, replicas). |
| `strimzi://operator.strimzi.io/namespaces/{namespace}/clusteroperator/{name}/status` | Strimzi operator deployment status, version, readiness, and uptime. |

### Resource Subscriptions

The server watches Kafka CRs, KafkaNodePool CRs, and Strimzi operator Deployments via Kubernetes watches. When a resource changes (e.g., a cluster goes from Ready to NotReady), subscribed MCP clients receive a `notifications/resources/updated` notification and can re-read the resource to get the latest data.

This enables reactive LLM agents that detect and investigate issues automatically without polling.

## Kubernetes Deployment

### Build Container Image

```bash
# Build the application and container image with Jib
mvn clean package -DskipTests -Dquarkus.container-image.build=true
```

The image defaults to `quay.io/streamshub/strimzi-mcp:latest`. Override with:

```bash
mvn clean package -DskipTests \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.registry=my-registry.io \
  -Dquarkus.container-image.group=my-org \
  -Dquarkus.container-image.tag=1.0.0
```

To push the image:

```bash
mvn clean package -DskipTests \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.push=true
```

### Deploy to Kubernetes

The `install/` directory contains all required manifests:

| File | Resource |
|------|----------|
| `001-Namespace.yaml` | `streamshub-mcp` namespace |
| `002-ServiceAccount.yaml` | Dedicated service account |
| `003-ClusterRole.yaml` | Read-only RBAC for non-sensitive resources (get, list, watch) |
| `004-ClusterRoleBinding.yaml` | Binds ClusterRole to ServiceAccount |
| `005-Deployment.yaml` | MCP server deployment with health probes |
| `006-Service.yaml` | ClusterIP service on port 8080 |
| `007-Role-sensitive.yaml` | Opt-in per-namespace Role for secrets and pod metrics |

```bash
# Deploy all resources
kubectl apply -f install/

# Verify the deployment
kubectl -n streamshub-mcp rollout status deployment/streamshub-strimzi-mcp

# Check the MCP server is ready
kubectl -n streamshub-mcp get pods
```

Update the image in the Deployment if using a custom registry:

```bash
kubectl -n streamshub-mcp set image deployment/streamshub-strimzi-mcp \
  strimzi-mcp=my-registry.io/my-org/strimzi-mcp:1.0.0
```

### RBAC

RBAC is split into two layers following the principle of least privilege:

**ClusterRole** (default, non-sensitive):
- **Strimzi CRs** (kafkas, kafkanodepools, kafkatopics, strimzipodsets): `get`, `list`, `watch`
- **Deployments**: `get`, `list`, `watch` (operator status and resource subscriptions)
- **Pods and logs**: `get`, `list` (pod status and log retrieval)
- **Services, configmaps**: `get`, `list` (connectivity and configuration)
- **Routes, ingresses**: `get`, `list` (external access discovery)
- **Leases**: `get`, `list` (operator leader election status)

**Role** (opt-in per namespace, sensitive resources — `007-Role-sensitive.yaml`):
- **Secrets**: `get` (TLS certificate metadata for `get_kafka_cluster_certificates`)
- **Pods/proxy**: `get` (direct pod metrics scraping)

Deploy the sensitive Role only in namespaces where certificate checking or metrics scraping is needed.

For namespace-scoped deployments, replace the ClusterRoleBinding with a namespaced RoleBinding.

### Connecting to the MCP Server

#### Port-forward (Kubernetes / OpenShift)

Forward the service port to your local machine and connect your LLM client:

```bash
kubectl -n streamshub-mcp port-forward svc/streamshub-strimzi-mcp 8080:8080
```

Then configure your MCP client to use `http://localhost:8080/mcp`:

```bash
claude mcp add --transport http strimzi http://localhost:8080/mcp
```

#### OpenShift Route

On OpenShift, expose the service via a Route to make it accessible outside the cluster:

```bash
# Create an edge-terminated Route
oc -n streamshub-mcp create route edge streamshub-strimzi-mcp \
  --service=streamshub-strimzi-mcp \
  --port=http

# Get the Route hostname
oc -n streamshub-mcp get route streamshub-strimzi-mcp -o jsonpath='{.spec.host}'
```

Then configure your MCP client with the Route URL:

```bash
claude mcp add --transport http strimzi https://<route-hostname>/mcp
```

For non-TLS access (not recommended for production), use a passthrough or plain Route:

```bash
oc -n streamshub-mcp expose svc/streamshub-strimzi-mcp
```

### Configuration

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `MCP_LOG_TAIL_LINES` | `200` | Default number of log lines to tail per pod |

### Local Container Run

```bash
podman run -d \
  --name strimzi-mcp \
  -p 8080:8080 \
  -v ~/.kube/config:/etc/kubernetes/config:ro \
  -e KUBECONFIG=/etc/kubernetes/config \
  quay.io/streamshub/strimzi-mcp:latest
```

## Troubleshooting

### Server Issues

```bash
# Check server startup logs
mvn quarkus:dev

# Verify MCP endpoint responds (expects POST, GET returns 405)
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}'
```

### Kubernetes Issues

```bash
# Verify kubectl works
kubectl get pods

# Look for Strimzi operator
kubectl get deployments --all-namespaces | grep strimzi

# Check if Strimzi CRDs exist
kubectl get crd | grep strimzi
```

## Requirements

- Java 21+
- Maven 3.8+
- Access to Kubernetes cluster with Strimzi

## License

Apache 2.0
