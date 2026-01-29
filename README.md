# Strimzi MCP & Chat Application

A Quarkus application that provides Strimzi Kafka management tools via both **MCP (Model Context Protocol)** and **REST Chat API** with configurable LLM providers.

## Features

- 🔧 **Real Kubernetes Integration**: Actual Strimzi operator management with live K8s API calls
- 🌐 **MCP Server**: Industry-standard Model Context Protocol for AI assistants (Claude, etc.)
- 💬 **REST Chat API**: HTTP endpoints for custom chat applications
- 🖥️ **CLI Client**: Interactive terminal chat that connects to server
- 🔄 **Dual LLM Support**: OpenAI (GPT-4) or Local (Ollama) - fully configurable
- 📊 **Structured Results**: Rich JSON responses with health analysis and diagnostics
- 🎯 **Smart Namespace Extraction**: Natural language parsing - no environment variables needed
- 🐳 **Container Ready**: Single JAR deployment for Docker/Kubernetes

## Quick Start

### 1. Local Development Setup

**Option A: Using Ollama (Local LLM)**
```bash
# 1. Start Ollama with our helper script
./hack/run-ollama.sh start

# 2. Start the server
mvn quarkus:dev

# 3. Test with CLI
./strimzi-cli chat "What pods are running in the default namespace?"
```

**Option B: Using OpenAI**
```bash
# 1. Set OpenAI API key
export LLM_PROVIDER=openai
export OPENAI_API_KEY=sk-your-key-here

# 2. Start the server
mvn quarkus:dev

# 3. Test with CLI
./strimzi-cli chat "Check operator status in kafka namespace"
```

### 2. Configuration

**Environment Variables (Optional):**
```bash
# LLM Provider (default: ollama)
export LLM_PROVIDER=ollama          # or "openai"

# For OpenAI
export OPENAI_API_KEY=sk-your-key-here

# For Ollama (defaults work with hack script)
export OLLAMA_BASE_URL=http://localhost:11434
export OLLAMA_MODEL=llama3.2:3b
```

**No Kubernetes Environment Variables Needed!**
The application extracts namespaces directly from your chat messages.

### 3. Start the Server

**Development Mode (Recommended):**
```bash
mvn quarkus:dev
```

**Production Mode:**
```bash
mvn clean package
java -jar target/quarkus-app/quarkus-run.jar
```

**Available Endpoints:**
- **Chat API**: http://localhost:8080/api/chat
- **MCP Server**: http://localhost:8080/mcp
- **Health**: http://localhost:8080/api/chat/health

### 4. Container Deployment

```bash
# Build container
mvn clean package
docker build -f src/main/docker/Dockerfile.jvm -t strimzi-mcp .

# Run with Ollama (connect to host Ollama)
docker run -d \
  --name strimzi-mcp \
  -p 8080:8080 \
  -e LLM_PROVIDER=ollama \
  -e OLLAMA_BASE_URL=http://host.docker.internal:11434 \
  -v ~/.kube/config:/root/.kube/config \
  strimzi-mcp

# Run with OpenAI
docker run -d \
  --name strimzi-mcp \
  -p 8080:8080 \
  -e LLM_PROVIDER=openai \
  -e OPENAI_API_KEY=sk-your-key-here \
  -v ~/.kube/config:/root/.kube/config \
  strimzi-mcp

# For Kubernetes deployment (using in-cluster auth)
kubectl create deployment strimzi-mcp \
  --image=strimzi-mcp \
  --port=8080
kubectl expose deployment strimzi-mcp \
  --type=ClusterIP \
  --port=8080
```

## Usage

### REST Chat API

**Send a message:**
```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "What Kafka pods are running in the kafka namespace?"}'
```

**Check health:**
```bash
curl http://localhost:8080/api/chat/health
```

**Response format:**
```json
{
  "response": "I found 3 Kafka pods running...",
  "provider": "ollama",
  "timestamp": "2026-01-29T10:30:00Z"
}
```

### CLI Client

**Interactive Chat:**
```bash
# Start interactive session
./strimzi-cli chat

# Example conversation:
💬 You: Show me operator logs from kafka namespace
🤖 Assistant: Let me check the operator logs...
💬 You: What about pods in production?
🤖 Assistant: Checking production namespace...
```

**Single Message:**
```bash
./strimzi-cli chat "What pods are running in the default namespace?"
./strimzi-cli chat "Check operator status in kafka namespace"
./strimzi-cli chat "Are there any errors in strimzi-system logs?"
```

**CLI Options:**
```bash
./strimzi-cli --server=http://localhost:8080 chat "message"
./strimzi-cli --verbose chat "message"
./strimzi-cli --help
```

### MCP Server Integration

**MCP Endpoint:** `http://localhost:8080/mcp`

**Available MCP Tools:**
- `strimzi_operator_logs(namespace)` - Get operator logs with error analysis
- `strimzi_cluster_pods(namespace, clusterName)` - Get cluster pod status
- `strimzi_operator_status(namespace)` - Check operator deployment health

#### Configure Claude Desktop

Add to your Claude Desktop configuration (`~/Library/Application Support/Claude/claude_desktop_config.json` on macOS):

```json
{
  "mcpServers": {
    "strimzi": {
      "command": "node",
      "args": ["-e", "require('http').createServer((req, res) => { const proxy = require('http').request('http://localhost:8080' + req.url, { method: req.method, headers: req.headers }, proxyRes => { res.writeHead(proxyRes.statusCode, proxyRes.headers); proxyRes.pipe(res); }); req.pipe(proxy); }).listen(3001)"],
      "env": {}
    }
  }
}
```

Or use a simpler setup with ngrok for external access:

```bash
# 1. Install ngrok: brew install ngrok
# 2. Start your server: mvn quarkus:dev
# 3. Expose via ngrok: ngrok http 8080
# 4. Use the ngrok URL in Claude Desktop:
```

```json
{
  "mcpServers": {
    "strimzi": {
      "command": "npx",
      "args": ["@modelcontextprotocol/server-fetch", "https://your-ngrok-url.ngrok.io/mcp"],
      "env": {}
    }
  }
}
```

#### Test MCP Connection

```bash
# Test MCP endpoint directly
curl http://localhost:8080/mcp

# Test with MCP client
npx @modelcontextprotocol/inspector http://localhost:8080/mcp
```

### Smart Namespace Extraction

**No Environment Variables Required!** The LLM automatically extracts namespace information from natural language:

**✅ These work automatically:**
```
"Show me pods in the kafka namespace"
"Check operator logs from production"
"What's the status in strimzi-system?"
"List pods for my-cluster in the dev environment"
"Are there any errors in the default namespace?"
```

**❓ Prompts for clarification when namespace is unclear:**
```
You: "Are there any errors in the operator logs?"
Assistant: "Which namespace should I check? (e.g., 'kafka', 'strimzi-system', 'default')"

You: "Check the kafka namespace"
Assistant: "Let me check the operator logs in the kafka namespace..."
```

### Example Conversations

**Via CLI:**
```bash
./strimzi-cli chat "What Kafka pods are running in the kafka namespace?"
# 🤖 I found 3 Kafka pods in the kafka namespace:
#    - my-cluster-kafka-0 (Running, Ready)
#    - my-cluster-kafka-1 (Running, Ready)
#    - my-cluster-kafka-2 (Running, Ready)

./strimzi-cli chat "Any errors in strimzi-system logs?"
# 🤖 Checking operator logs in strimzi-system namespace...
#    Found 2 error messages in the last 50 log lines...
```

**Via REST API:**
```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "Check operator status in the production namespace"}'

# Response:
# {
#   "response": "The Strimzi operator in production namespace is healthy...",
#   "provider": "ollama",
#   "timestamp": "2026-01-29T10:30:00Z"
# }
```

**Via Claude Desktop (MCP):**
```
You: "Help me troubleshoot my Kafka cluster in the dev namespace"
Claude: I'll help you troubleshoot your Kafka cluster. Let me check the operator status and logs in the dev namespace...

[Claude automatically calls the MCP tools and provides analysis]
```

## Configuration Reference

### LLM Providers

**Ollama (Default) - Local LLM:**
```bash
export LLM_PROVIDER=ollama
export OLLAMA_BASE_URL=http://localhost:11434  # default
export OLLAMA_MODEL=llama3.2:3b               # default
```

**OpenAI - Cloud LLM:**
```bash
export LLM_PROVIDER=openai
export OPENAI_API_KEY=sk-your-api-key-here
# Uses GPT-4 by default, configurable in application.properties
```

### Kubernetes Access

The application automatically uses your current Kubernetes context:
- **Local development**: Uses `~/.kube/config`
- **Container deployment**: Mount your kubeconfig or use in-cluster auth
- **Kubernetes deployment**: Uses in-cluster service account automatically

**No namespace environment variables needed** - namespaces are extracted from chat messages!

### Application Settings

```bash
# Optional overrides
export QUARKUS_HTTP_PORT=8080          # HTTP port (default: 8080)
export QUARKUS_LOG_LEVEL=INFO          # Log level
export QUARKUS_HTTP_CORS_ENABLED=true  # Enable CORS for MCP clients
```

## Local Development & Testing

### Quick Setup with Ollama

Use the included helper script for zero-config local setup:

```bash
# 1. Start Ollama in Podman (downloads llama3.2:3b automatically)
./hack/run-ollama.sh start

# 2. Start the application
mvn quarkus:dev

# 3. Test everything works
./strimzi-cli chat "Hello, show me operator status in default namespace"
```

**What the hack script does:**
- Runs Ollama in Podman container (isolated from your system)
- Downloads and configures `llama3.2:3b` model
- Sets up proper port forwarding (11434)
- Provides volume persistence for models
- Includes management commands (start/stop/status/logs)

**Ollama Management:**
```bash
./hack/run-ollama.sh status    # Check if running
./hack/run-ollama.sh logs      # View container logs
./hack/run-ollama.sh stop      # Stop container
./hack/run-ollama.sh restart   # Restart with same model
./hack/run-ollama.sh pull llama3.2:1b  # Download different model
```

**No Ollama?** Use OpenAI instead:
```bash
export LLM_PROVIDER=openai
export OPENAI_API_KEY=sk-your-key-here
mvn quarkus:dev
```

## Claude Desktop MCP Setup

### Complete Claude Desktop Configuration

1. **Start your server:**
   ```bash
   mvn quarkus:dev  # Runs on http://localhost:8080
   ```

2. **Configure Claude Desktop** (`~/Library/Application Support/Claude/claude_desktop_config.json` on macOS):

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

3. **Restart Claude Desktop** and you'll see "🔨 strimzi-kafka" in the chat interface

4. **Test the integration:**
   ```
   You: "Help me check my Kafka cluster in the kafka namespace"

   Claude: I'll help you check your Kafka cluster. Let me examine the operator
           status and pod health in the kafka namespace.

   [Claude automatically calls MCP tools and provides detailed analysis]
   ```

### Alternative: Ngrok for Remote Access

If you want Claude to access your server remotely:

```bash
# 1. Install ngrok: brew install ngrok
# 2. Start your server: mvn quarkus:dev
# 3. Expose publicly: ngrok http 8080
# 4. Use the ngrok URL in Claude Desktop config:
```

```json
{
  "mcpServers": {
    "strimzi-kafka": {
      "command": "npx",
      "args": [
        "@modelcontextprotocol/server-fetch",
        "https://abc123.ngrok.io/mcp"
      ],
      "env": {}
    }
  }
}
```

**Security Note:** Only use ngrok for testing. For production, use proper authentication and HTTPS.

## Architecture Improvements

This application follows enterprise-grade patterns inspired by real-world MCP implementations:

### **Shared Service Layer Pattern**
- **`ToolOperationsService`** - Single source of truth for all tool business logic
- Both MCP and LangChain4J tools delegate to the same service
- Ensures consistent behavior across all interfaces (MCP, REST, CLI)
- Easy to test and maintain

### **Structured Result Objects**
- **Type-safe returns** using Java records (`OperatorLogsResult`, `ClusterPodsResult`, etc.)
- **Jackson-controlled JSON** serialization for consistent API responses
- **Pre-formatted display values** optimized for LLM consumption
- **Graceful degradation** with meaningful messages when data isn't available

### **Input Normalization**
- **Namespace/cluster name normalization** handles various input formats
- **Forgiving input parsing** (case-insensitive, trimmed, defaults)
- **Environment variable integration** for default values

### **Enhanced Tool Documentation**
- **Comprehensive descriptions** with examples and format specifications
- **Parameter documentation** with `@ToolArg` annotations
- **Self-documenting tools** that guide LLM usage

### **Error Handling & Timeouts**
- **Structured error responses** via `ToolError` records
- **30-second timeouts** on all Kubernetes operations
- **Proper exception handling** with meaningful error messages

## Development

### Adding New Tools

Follow the shared service pattern for consistency across MCP and REST:

1. **Add business logic** to `ToolOperationsService.java`
2. **Add tool method** to `StrimziOperatorTools.java` (for LLM chat)
3. **Add MCP wrapper** to `StrimziOperatorMcpTools.java` (for external clients)
4. **Create result DTO** in `dto/` package for structured responses

**Example implementation:**

```java
// 1. In ToolOperationsService.java
public NewToolResult getNewToolData(String param) {
    String normalized = normalizeParam(param);
    // Business logic here
    return NewToolResult.of(data);
}

// 2. In StrimziOperatorTools.java
@Tool("Comprehensive description with examples")
public NewToolResult newTool(String param) {
    return operations.getNewToolData(param);
}

// 3. In StrimziOperatorMcpTools.java
@Tool(name = "new_tool", description = "Tool description for MCP")
public Object newTool(@ToolArg(description = "Parameter description") String param) {
    try {
        return operations.getNewToolData(param);
    } catch (Exception e) {
        return ToolError.of("Failed to execute tool", e);
    }
}

// 4. Result DTO in dto/ package
public record NewToolResult(
    @JsonProperty("param") String param,
    @JsonProperty("data") List<String> data,
    @JsonProperty("status") String status
) {
    public static NewToolResult of(String param, List<String> data) {
        return new NewToolResult(param, data, "SUCCESS");
    }
}
```

## Troubleshooting

### Server Won't Start

**Check logs for common issues:**
```bash
mvn quarkus:dev
# Look for error messages in startup logs
```

**Common fixes:**
- **Port 8080 busy**: `lsof -i :8080` to find conflicting process
- **Java version**: Requires Java 21+, check with `java -version`
- **Maven issues**: Try `mvn clean` then rebuild

### LLM Provider Issues

**Ollama Problems:**
```bash
# Check if Ollama container is running
./hack/run-ollama.sh status

# View Ollama logs
./hack/run-ollama.sh logs

# Test Ollama API directly
curl http://localhost:11434/api/tags

# Restart if needed
./hack/run-ollama.sh restart
```

**OpenAI Problems:**
```bash
# Test API key
curl -H "Authorization: Bearer $OPENAI_API_KEY" \
  https://api.openai.com/v1/models

# Check environment variable
echo $OPENAI_API_KEY

# Verify account has credits at https://platform.openai.com
```

### CLI Issues

**CLI can't connect to server:**
```bash
# Check if server is running
curl http://localhost:8080/api/chat/health

# Start server if needed
mvn quarkus:dev

# Test CLI with verbose output
./strimzi-cli --verbose chat "test message"
```

### Kubernetes Issues

**No pods found / Permission denied:**
```bash
# Verify kubectl works
kubectl get pods

# Check current context
kubectl config current-context

# Test specific namespace
kubectl get pods -n kafka
kubectl get pods -n strimzi-system
```

**Strimzi not deployed:**
```bash
# Look for Strimzi operator
kubectl get deployments --all-namespaces | grep strimzi

# Check if Strimzi CRDs exist
kubectl get crd | grep strimzi

# Install Strimzi if needed
kubectl create namespace kafka
kubectl create -f 'https://strimzi.io/install/latest?namespace=kafka' -n kafka
```

### MCP Client Issues

**Claude Desktop not seeing tools:**
1. Check MCP endpoint: `curl http://localhost:8080/mcp`
2. Restart Claude Desktop after config changes
3. Look for "🔨 strimzi-kafka" indicator in Claude chat
4. Check Claude Desktop logs (Help > View Logs)

**Connection errors:**
```bash
# Enable CORS for browser-based MCP clients
export QUARKUS_HTTP_CORS_ENABLED=true
mvn quarkus:dev

# Test MCP with inspector
npx @modelcontextprotocol/inspector http://localhost:8080/mcp
```

### Getting Help

**Enable debug logging:**
```bash
# Add to application.properties or environment
export QUARKUS_LOG_CATEGORY_IO_STRIMZI_MCP_LEVEL=DEBUG
mvn quarkus:dev
```

**Collect diagnostic info:**
```bash
# Server status
curl http://localhost:8080/api/chat/health

# CLI test
./strimzi-cli --verbose chat "test"

# Kubernetes access
kubectl version --client
kubectl get nodes
```

## Development

### Project Structure

```
src/main/java/io/strimzi/mcp/
├── StrimziMcpApplication.java     # Main application entry point
├── api/ChatResource.java          # REST API endpoints
├── service/
│   ├── ChatService.java           # Chat orchestration
│   ├── StrimziChatAssistant.java  # LangChain4J LLM interface
│   └── ToolOperationsService.java # Shared business logic
├── tool/StrimziOperatorTools.java # LangChain4J tool definitions
├── mcp/StrimziOperatorMcpTools.java # MCP tool wrapper
├── dto/                           # Structured result objects
└── cli/                           # CLI client implementation

hack/
└── run-ollama.sh                  # Ollama container management
```

### Adding New Tools

Follow the shared service pattern for consistency across MCP and REST:

1. **Add business logic** to `ToolOperationsService.java`
2. **Add tool method** to `StrimziOperatorTools.java` (for LLM chat)
3. **Add MCP wrapper** to `StrimziOperatorMcpTools.java` (for external clients)
4. **Create result DTO** in `dto/` package for structured responses

### Running Tests

```bash
# Unit tests
mvn test

# Integration test with different providers
mvn test -Dtest.llm.provider=openai
mvn test -Dtest.llm.provider=ollama

# End-to-end test
mvn quarkus:dev &
sleep 10
./strimzi-cli chat "test message"
```

## License

Apache 2.0 - see LICENSE file for details.

## Contributing

1. Fork the repository
2. Create feature branch: `git checkout -b feature/new-tool`
3. Follow the shared service pattern for new tools
4. Add tests and update documentation
5. Submit pull request

**Questions?** Open an issue or start a discussion.
