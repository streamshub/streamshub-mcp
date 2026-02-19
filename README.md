# Strimzi MCP Server

A Quarkus application that provides Strimzi Kafka management tools via **MCP (Model Context Protocol)** for AI assistants and automation.

## Features

- **Real Kubernetes Integration**: Live Strimzi operator management with K8s API calls
- **Pure MCP Server**: Standard Model Context Protocol for AI assistants (Claude, etc.)
- **Smart Discovery**: Auto-finds operators and clusters across namespaces
- **Structured Results**: Rich JSON responses with health analysis
- **Standardized Error Handling**: Consistent ToolError responses across all operations
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

Add to Claude code:

```shell
claude mcp add --transport http strimzi-mcp http://localhost:8080/mcp
```

## Configuration

### Core Settings

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `K8S_NAMESPACE` | `kafka` | Default Kubernetes namespace |

## Available Tools

All tools support **smart discovery** - if you don't specify a namespace, they automatically find Strimzi installations across the entire cluster.

### Kafka Cluster Tools

- **`strimzi_kafka_clusters`** - Discover and list all Kafka clusters with status and configuration
- **`strimzi_cluster_pods`** - Get lightweight pod summaries with component analysis (name, phase, ready, restarts, age)
- **`strimzi_cluster_pod_describe`** - Get detailed description of specific pod (env vars, resources, volumes, placement)
- **`strimzi_kafka_topics`** - List all topics for a cluster with partitions, replicas, and status
- **`strimzi_topic_details`** - Get detailed information for a specific topic by name
- **`strimzi_bootstrap_servers`** - Get Kafka connection endpoints from all configured listeners

### Strimzi Operator Tools

- **`strimzi_operator_status`** - Check operator deployment health, replicas, version, and uptime
- **`strimzi_operator_logs`** - Get operator logs with error analysis and structured output
- **`strimzi_cluster_operators`** - Discover all operator deployments across namespaces
- **`strimzi_pod_describe`** - Get detailed description of operator pods

## Testing MCP Connection

```bash
# Basic connectivity
curl http://localhost:8080/mcp

# Use MCP inspector
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

# Verify MCP endpoint
curl http://localhost:8080/mcp
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

The application uses a clean architecture with standardized error handling:

- **Services** return `Object` type - either success response DTOs or `ToolError` for failures
- **MCP Tools** wrap service calls with try-catch and `ToolError.of()` for consistent error handling
- **Discovery Logic** uses `.inAnyNamespace()` when namespace is null for true cross-cluster discovery
- **Auto-Discovery** extracts namespace information from discovered resources, eliminating redundant API calls

### Project Structure
```
src/main/java/io/streamshub/mcp/
├── tool/                                  # MCP tool definitions
│   ├── StrimziOperatorMcpTools.java      # Operator MCP tools
│   └── KafkaClusterMcpTools.java         # Cluster MCP tools
├── service/                               # Business logic
│   ├── infra/                            # Infrastructure services
│   │   ├── StrimziOperatorService.java   # Operator operations
│   │   ├── KafkaClusterService.java      # Cluster operations
│   │   ├── KafkaTopicService.java        # Topic operations
│   │   └── StrimziDiscoveryService.java  # Discovery utilities
│   └── common/                           # Shared services
│       ├── KubernetesResourceService.java # Generic K8s API wrapper
│       ├── PodsService.java              # Pod operations
│       └── DeploymentService.java        # Deployment utilities
├── dto/                                   # Data transfer objects
│   └── ToolError.java                    # Standardized error responses
└── config/                               # Configuration
    └── StrimziToolsPrompts.java          # Shared tool descriptions
```

### Adding New Tools

1. **Add service method** to appropriate service class (returns `Object`)
2. **Add MCP tool** to corresponding MCP tools class
3. **Use standardized error handling**

```java
// Service Method
public Object newOperation(String param) {
    try {
        // Business logic
        return successResponse;
    } catch (Exception e) {
        LOG.errorf(e, "Error in new operation: %s", param);
        return ToolError.of("Failed to perform operation", e);
    }
}

// MCP Tool
@Tool(name = "new_tool", description = "Tool description")
public Object newTool(@ToolArg(description = "Parameter") String param) {
    try {
        return appropriateService.newOperation(param);
    } catch (Exception e) {
        return ToolError.of("Operation failed", e);
    }
}
```

### Error Handling

All tools return either:
- **Success**: Specific response DTO (e.g., `KafkaClusterResponse`, `List<KafkaTopicResponse>`)
- **Error**: `ToolError` with descriptive error message and optional exception details

This provides consistent error handling across all MCP tools while maintaining type safety.

## Requirements

- Java 21+
- Maven 3.8+
- Access to Kubernetes cluster with Strimzi

## License

Apache 2.0