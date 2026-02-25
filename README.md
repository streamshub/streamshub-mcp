# Strimzi MCP Server

A Quarkus application that provides Strimzi Kafka management tools via **MCP (Model Context Protocol)** for AI assistants and automation.

## Features

- **Real Kubernetes Integration**: Live Strimzi operator management with K8s API calls
- **Pure MCP Server**: Standard Model Context Protocol for AI assistants (Claude, etc.)
- **Smart Discovery**: Auto-finds operators and clusters across namespaces
- **Structured Results**: Rich JSON responses with health analysis
- **Lightweight**: No LLM dependencies required

## Prerequisites

### Kubernetes Cluster with Strimzi

The MCP server requires access to a Kubernetes cluster with Strimzi installed.
Example manifests are provided in `examples/strimzi/`.

**Deploy using the setup script (recommended):**
```bash
./hack/setup-strimzi.sh
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
./hack/setup-strimzi.sh teardown
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

## Development

### Architecture

The application uses a clean layered architecture:

- **Tool Layer** (`tool/`): MCP tool definitions using `@Tool` and `@ToolArg` annotations. Uses `@WrapBusinessError(Exception.class)` for automatic error conversion to MCP error responses with `isError: true`.
- **Domain Services** (`service/domain/`): Business logic for Strimzi-specific operations (Kafka clusters, topics, node pools, operators). Throw `ToolCallException` for error cases.
- **Common Services** (`service/common/`): Generic Kubernetes operations (resource queries, pod descriptions, deployment utilities).
- **DTOs** (`dto/`): Typed response records for structured JSON output.
- **Config** (`config/`): Centralized constants and shared tool descriptions.

### Project Structure
```
src/main/java/io/streamshub/mcp/
├── tool/                                  # MCP tool definitions
│   ├── KafkaTools.java                   # Kafka cluster tools
│   ├── KafkaTopicTools.java              # Topic tools
│   ├── KafkaNodePoolTools.java           # Node pool tools
│   └── StrimziOperatorTools.java         # Operator tools
├── service/
│   ├── domain/                           # Strimzi-specific services
│   │   ├── KafkaService.java             # Cluster operations
│   │   ├── KafkaTopicService.java        # Topic operations
│   │   ├── KafkaNodePoolService.java     # Node pool operations
│   │   └── StrimziOperatorService.java   # Operator operations
│   └── common/                           # Generic Kubernetes services
│       ├── KubernetesResourceService.java # K8s API wrapper
│       ├── PodsService.java              # Pod operations
│       └── DeploymentService.java        # Deployment utilities
├── dto/                                   # Response records
└── config/
    ├── Constants.java                    # Centralized constants
    └── StrimziToolsPrompts.java          # Shared tool descriptions
```

### Adding New Tools

1. **Add service method** to the appropriate domain service (return typed response, throw `ToolCallException` for errors)
2. **Add MCP tool** to the corresponding tools class

```java
// Service method - typed return, exceptions for errors
public MyResponse myOperation(String namespace, String name) {
    String ns = InputUtils.normalizeInput(namespace);
    // ... business logic ...
    if (resource == null) {
        throw new ToolCallException("Resource not found");
    }
    return new MyResponse(/* ... */);
}

// MCP tool - thin wrapper, @WrapBusinessError handles exceptions
@Tool(name = "my_tool", description = "Tool description")
public MyResponse myTool(
    @ToolArg(description = "...") String name,
    @ToolArg(description = "...", required = false) String namespace
) {
    return myService.myOperation(namespace, name);
}
```

## Requirements

- Java 21+
- Maven 3.8+
- Access to Kubernetes cluster with Strimzi

## License

Apache 2.0
