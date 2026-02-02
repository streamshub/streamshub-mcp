# Strimzi MCP Server

A Quarkus application that provides Strimzi Kafka management tools via **MCP (Model Context Protocol)** with optional AI chat functionality.

## Features

- 🔧 **Real Kubernetes Integration**: Live Strimzi operator management with K8s API calls
- 🌐 **MCP Server**: Standard Model Context Protocol for AI assistants (Claude, etc.)
- 🤖 **Optional AI Chat**: LLM-powered chat interface (when enabled)
- 🎯 **Smart Discovery**: Auto-finds operators and clusters across namespaces
- 📊 **Structured Results**: Rich JSON responses with health analysis
- ⚡ **Lightweight by Default**: Pure MCP mode with no LLM dependencies

## Running Modes

### 🚀 Pure MCP Mode (Default)
- **Default behavior**: Server starts with only MCP functionality
- **Lightweight**: No LLM dependencies or configuration required
- **Always available**: MCP tools work regardless of LLM setup
- **Perfect for**: AI assistants, automation, direct tool access

### 🤖 Full Mode (Optional)
- **Enabled with**: `ENABLE_LLM=true`
- **Features**: MCP + Chat API with AI assistance
- **Requires**: LLM provider configuration (OpenAI or Ollama)

## Quick Start

### 1. Start the Server

**Pure MCP mode (default):**
```bash
mvn clean package
mvn quarkus:dev
```

**With AI chat enabled:**
```bash
export ENABLE_LLM=true
export LLM_PROVIDER=ollama  # or openai
export OLLAMA_BASE_URL=http://localhost:11434  # if using Ollama
mvn quarkus:dev
```

### 2. Interact with Chat API

#### **Terminal Client (Interactive)**
```bash
# Interactive chat
jbang hack/strimzi-chat-cli.java

# Single message
jbang hack/strimzi-chat-cli.java "Show me all clusters"

# Remote server
jbang hack/strimzi-chat-cli.java --server http://remote:8080
```

#### **Direct API calls (curl)**
```bash
# Send a chat message
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "Show me all Kafka clusters"}'

# Check chat service health
curl http://localhost:8080/api/chat/health

# Get service info
curl http://localhost:8080/api/chat/info
```

### 3. Configure AI Assistants

Add to Claude code:

```shell
claude mcp add --transport http strimzi-mcp http://localhost:8080/mcp
```

## Configuration

### Core Settings

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `ENABLE_LLM` | `false` | Enable AI chat functionality |
| `LLM_PROVIDER` | `none`  | AI provider: `openai`, `ollama`, or `none` |
| `K8S_NAMESPACE` | `kafka` | Default Kubernetes namespace |

### OpenAI Provider (when LLM_PROVIDER=openai)

```bash
export ENABLE_LLM=true
export LLM_PROVIDER=openai
export OPENAI_API_KEY=sk-...
```

### Ollama Provider (when LLM_PROVIDER=ollama)

```bash
export ENABLE_LLM=true
```

## Available Tools

All tools support **smart discovery** - if you don't specify a namespace, they automatically find Strimzi installations.

### MCP Tools (Always Available)

- **`strimzi_kafka_clusters`** - Discover and list all Kafka clusters
- **`strimzi_cluster_pods`** - Get cluster pod status and health
- **`strimzi_kafka_topics`** - Get topic configuration and status
- **`strimzi_operator_status`** - Check operator deployment health
- **`strimzi_operator_logs`** - Get operator logs with error analysis

### Chat API (When ENABLE_LLM=true)

- **`POST /api/chat`** - Send messages to AI assistant
- **`GET /api/chat/health`** - Check chat service health
- **`GET /api/chat/info`** - Get provider and configuration info

## API Usage

### Chat API Examples

#### **Send Chat Messages**
```bash
# Basic chat request
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "Show me all Kafka clusters"}'

# Response (when LLM enabled):
# {
#   "response": "I'll check your Kafka clusters...",
#   "provider": "ollama"
# }

# Response (when LLM disabled):
# HTTP 503: {
#   "error": "LLM service unavailable",
#   "message": "LLM functionality disabled (set ENABLE_LLM=true to enable)"
# }
```

#### **Health and Status Checks**
```bash
# Check server health (always available)
curl http://localhost:8080/mcp

# Check chat service health
curl http://localhost:8080/api/chat/health

# Get service configuration info
curl http://localhost:8080/api/chat/info
```

### Expected Responses

**Pure MCP mode:**
```bash
curl /api/chat/health
# HTTP 503: {"error": "LLM service unavailable", "message": "LLM functionality disabled"}
```

**Full mode:**
```bash
curl /api/chat/health
# HTTP 200: {"status": "healthy", "provider": "ollama"}
```

## CLI Examples

The standalone CLI automatically detects server mode and adapts accordingly:

**Pure MCP mode (LLM disabled):**
```bash
$ jbang hack/strimzi-chat-cli.java "Show me all clusters"
❌ LLM functionality disabled (set ENABLE_LLM=true to enable)
   Hint: Make sure ENABLE_LLM=true and provider is configured
```

**Full mode (LLM enabled):**
```bash
$ jbang hack/strimzi-chat-cli.java
🤖 Strimzi Chat CLI
Server: http://localhost:8080
Type your message or 'exit' to quit

You: Show me all clusters
🤖 I'll check your Kafka clusters across all namespaces...
[AI responds with cluster analysis]

You: exit
👋 Goodbye!
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

**Pure MCP mode (default):**
```bash
podman run -d \
  --name strimzi-mcp \
  -p 8080:8080 \
  -v ~/.kube/config:/etc/kubernetes/config:ro \
  -e KUBECONFIG=/etc/kubernetes/config \
  strimzi-mcp
```

**With AI enabled:**
```bash
podman run -d \
  --name strimzi-mcp \
  -p 8080:8080 \
  -e ENABLE_LLM=true \
  -e LLM_PROVIDER=ollama \
  -e OLLAMA_BASE_URL=http://host.containers.internal:11434 \
  -v ~/.kube/config:/etc/kubernetes/config:ro \
  -e KUBECONFIG=/etc/kubernetes/config \
  strimzi-mcp
```

**With OpenAI:**
```bash
podman run -d \
  --name strimzi-mcp \
  -p 8080:8080 \
  -e ENABLE_LLM=true \
  -e LLM_PROVIDER=openai \
  -e OPENAI_API_KEY=sk-your-key-here \
  -v ~/.kube/config:/etc/kubernetes/config:ro \
  -e KUBECONFIG=/etc/kubernetes/config \
  strimzi-mcp
```

## Testing

### Test MCP Connection
```bash
# Basic connectivity
curl http://localhost:8080/mcp

# Use MCP inspector
npx @modelcontextprotocol/inspector http://localhost:8080/mcp
```

### Test Chat API
```bash
# With LLM disabled (expect 503)
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "test"}'

# With LLM enabled (expect 200)
export ENABLE_LLM=true
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "Show me cluster status"}'
```

## Troubleshooting

### Server Issues

```bash
# Check server startup logs
mvn quarkus:dev

# Verify endpoints
curl http://localhost:8080/mcp  # Should always work
curl http://localhost:8080/api/chat/health  # Check LLM status
```

### LLM Configuration Issues

**Problem**: `LLM functionality disabled`
**Solution**: Set `ENABLE_LLM=true`

**Problem**: `OpenAI API key not configured`
**Solution**: Set `OPENAI_API_KEY=sk-...`

**Problem**: `Ollama server not reachable`
**Solution**: Check `OLLAMA_BASE_URL` and ensure Ollama is running

### Kubernetes Issues

```bash
# Verify kubectl works
kubectl get pods

# Look for Strimzi operator
kubectl get deployments --all-namespaces | grep strimzi

# Check if Strimzi CRDs exist
kubectl get crd | grep strimzi
```

### CLI Issues

```bash
# Install JBang if needed
curl -Ls https://sh.jbang.dev | bash -s - app setup

# Test CLI connectivity
jbang hack/strimzi-chat-cli.java "test message"
```

## Development

### Project Structure
```
src/main/java/io/strimzi/mcp/
├── api/ChatResource.java                  # REST API endpoints
├── config/                                # Configuration & startup
│   ├── LlmConfigurationDetector.java     # LLM availability detection
│   └── ApplicationStartupService.java     # Startup logic
├── mcp/StrimziOperatorMcpTools.java       # MCP tool definitions
├── service/                               # Business logic
│   ├── ChatService.java                  # LLM chat coordination
│   ├── StrimziOperatorService.java       # Operator operations
│   ├── KafkaClusterService.java          # Cluster operations
│   ├── KafkaTopicService.java            # Topic operations
│   └── StrimziDiscoveryService.java      # Discovery utilities
├── tool/StrimziOperatorTools.java        # LangChain4J tools
└── dto/                                   # Data transfer objects
```

### Adding New Tools

1. **Add service method** to appropriate service class
2. **Add MCP tool** to `StrimziOperatorMcpTools.java`
3. **Add LangChain4J tool** to `StrimziOperatorTools.java` (if LLM support needed)

```java
// MCP Tool (always available)
@Tool(name = "new_tool", description = "Tool description")
public Object newTool(@ToolArg(description = "Parameter") String param) {
    try {
        return appropriateService.newOperation(param);
    } catch (Exception e) {
        return ToolError.of("Operation failed", e);
    }
}

// LangChain4J Tool (for LLM integration)
@Tool("Tool description")
public OperationResult newTool(String param) {
    return appropriateService.newOperation(param);
}
```

### Conditional LLM Architecture

The application uses a **conditional loading pattern**:

1. **Primary gate**: `app.llm.enable` (defaults to false)
2. **Provider validation**: Only when enabled, check provider config
3. **Optional injection**: `Instance<StrimziChatAssistant>` handles missing beans
4. **Graceful degradation**: MCP always works, Chat API returns 503 when disabled

## Requirements

- Java 21+
- Maven 3.8+
- Access to Kubernetes cluster with Strimzi
- JBang (for CLI) - [Install guide](https://www.jbang.dev/download/)

## License

Apache 2.0