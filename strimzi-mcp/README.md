# Strimzi MCP Server

A Quarkus application that provides Strimzi Kafka management tools via **MCP (Model Context Protocol)** for AI assistants and automation.

## Prerequisites

### Kubernetes Cluster with Strimzi

The MCP server requires access to a Kubernetes cluster with Strimzi installed.
Example manifests are provided in `examples/strimzi/`.

**Deploy using the setup script (recommended):**
```bash
../hack/setup-strimzi.sh
```

The script deploys the Strimzi operator and Kafka cluster sequentially, waiting for each component to become ready before proceeding.

**Or deploy manually:**
```bash
# 1. Deploy the Strimzi operator (CRDs, RBAC, operator deployment)
kubectl apply -k examples/strimzi/strimzi-operator/

# 2. Wait for the operator to be ready
kubectl wait --for=condition=Available deployment/strimzi-cluster-operator -n strimzi --timeout=120s

# 3. Deploy the Kafka cluster
kubectl apply -k examples/strimzi/kafka/

# 4. Wait for the Kafka cluster to be ready
kubectl wait kafka/mcp-cluster --for=condition=Ready -n strimzi-kafka --timeout=300s
```

**Tear down:**
```bash
../hack/setup-strimzi.sh teardown
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
| `strimzi://cluster/{namespace}/{name}/status` | Kafka cluster status: readiness, version, replicas, listeners, authentication, and storage configuration. |
| `strimzi://cluster/{namespace}/{name}/topology` | Cluster topology: node pools with roles, replica counts, and storage. |
| `strimzi://operator/{namespace}/status` | Strimzi operator deployment status, version, readiness, and uptime. |

### Resource Subscriptions

The server watches Kafka CRs, KafkaNodePool CRs, and Strimzi operator Deployments via Kubernetes watches. When a resource changes (e.g., a cluster goes from Ready to NotReady), subscribed MCP clients receive a `notifications/resources/updated` notification and can re-read the resource to get the latest data.

This enables reactive LLM agents that detect and investigate issues automatically without polling.

## Container Deployment

### Build Container Image
```bash
# Build the application
./mvnw clean package -DskipTests=true

# Build container image
podman build -f src/main/docker/Dockerfile.jvm -t strimzi-mcp .
```

### Run Container

```bash
podman run -d \
  --name strimzi-mcp \
  -p 8080:8080 \
  -v ~/.kube/config:/etc/kubernetes/config:ro \
  -e KUBECONFIG=/etc/kubernetes/config \
  strimzi-mcp
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
