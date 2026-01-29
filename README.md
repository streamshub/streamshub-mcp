# Strimzi MCP Tools

A Quarkus application that provides Strimzi Kafka management tools via **MCP (Model Context Protocol)** for AI assistants.

## Features

- 🔧 **Real Kubernetes Integration**: Live Strimzi operator management with K8s API calls
- 🌐 **MCP Server**: Standard Model Context Protocol for AI assistants (Claude, etc.)
- 🎯 **Smart Discovery**: Auto-finds operators and clusters across namespaces
- 📊 **Structured Results**: Rich JSON responses with health analysis

## Quick Start

### 1. Setup

```bash
# Clone and build
git clone <repo>
cd strimzi-mcp
mvn clean package

# Start the server
mvn quarkus:dev
```

**Server runs on:** http://localhost:8080/mcp

### 2. Configure Claude Desktop

Add to `~/Library/Application Support/Claude/claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "strimzi-kafka": {
      "command": "npx",
      "args": [
        "@modelcontextprotocol/server-fetch",
        "http://localhost:8080/mcp"
      ],
      "env": {}
    }
  }
}
```

Restart Claude Desktop and look for "🔨 strimzi-kafka" indicator.

### 3. Use with Claude

```
You: "Help me check my Kafka cluster in the kafka namespace"

Claude: I'll help you check your Kafka cluster. Let me examine the operator
        status and pod health in the kafka namespace.

[Claude automatically calls MCP tools and provides analysis]
```

## Available Tools

- **`strimzi_operator_logs`** - Get operator logs with error analysis
- **`strimzi_cluster_pods`** - Get cluster pod status and health
- **`strimzi_operator_status`** - Check operator deployment health
- **`strimzi_kafka_clusters`** - Discover and list all Kafka clusters
- **`strimzi_kafka_topics`** - Get topic configuration and status

All tools support **smart discovery** - if you don't specify a namespace, they automatically find Strimzi installations.

## Test MCP Connection

```bash
# Test endpoint
curl http://localhost:8080/mcp

# Use MCP inspector
npx @modelcontextprotocol/inspector http://localhost:8080/mcp
```

## Configuration

The application automatically uses your current Kubernetes context from `~/.kube/config`.

**Optional environment variables:**
```bash
export K8S_NAMESPACE=kafka        # Default namespace fallback
export QUARKUS_HTTP_PORT=8080     # HTTP port
```

## Container Deployment

```bash
# Build
docker build -f src/main/docker/Dockerfile.jvm -t strimzi-mcp .

# Run
docker run -d \
  --name strimzi-mcp \
  -p 8080:8080 \
  -v ~/.kube/config:/root/.kube/config \
  strimzi-mcp
```

## Troubleshooting

### Server Issues
```bash
# Check if server is running
curl http://localhost:8080/mcp

# View logs
mvn quarkus:dev
```

### Claude Desktop Issues
1. Check MCP endpoint: `curl http://localhost:8080/mcp`
2. Restart Claude Desktop after config changes
3. Look for "🔨 strimzi-kafka" indicator in Claude chat
4. Check Claude Desktop logs (Help > View Logs)

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

### Project Structure
```
src/main/java/io/strimzi/mcp/
├── mcp/StrimziOperatorMcpTools.java    # MCP tool definitions
├── service/                            # Specialized service classes
│   ├── StrimziOperatorService.java     # Operator operations
│   ├── KafkaClusterService.java        # Cluster operations
│   ├── KafkaTopicService.java          # Topic operations
│   └── StrimziDiscoveryService.java    # Discovery utilities
└── dto/                                # Result objects
```

### Adding New Tools

1. **Add business logic** to appropriate service class
1. **Add Tool** to `StrimziOperatorTools.java`
2. **Add MCP tool** to `StrimziOperatorMcpTools.java`
3. **Create result DTO** in `dto/` package

Example:
```java
// In StrimziOperatorMcpTools.java
@Tool(name = "new_tool", description = "Tool description")
public Object newTool(@ToolArg(description = "Parameter") String param) {
    try {
        return appropriateService.newOperation(param);
    } catch (Exception e) {
        return ToolError.of("Failed to execute", e);
    }
}
```

## Requirements

- Java 21+
- Maven 3.8+
- Access to Kubernetes cluster with Strimzi

## License

Apache 2.0