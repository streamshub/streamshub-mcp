+++
title = 'Building'
weight = 2
+++

This guide covers building StreamsHub MCP from source.

## Prerequisites

- **Java 21+** — Download from [Adoptium](https://adoptium.net/) or use SDKMAN
- **Maven 3.8+** — Download from [Apache Maven](https://maven.apache.org/)
- **kubectl** — Kubernetes command-line tool
- **Access to a Kubernetes cluster** — Minikube, KIND, or remote cluster
- **Git** — Version control

### Optional Tools

- **Docker or Podman** — For building container images
- **MCP Inspector** — For testing MCP servers: `npm install -g @modelcontextprotocol/inspector`
- **Claude Desktop or Claude Code** — For testing with AI assistants

## Setting Up Your Development Environment

### 1. Clone the Repository

```bash
git clone https://github.com/streamshub/streamshub-mcp.git
cd streamshub-mcp
```

### 2. Verify Java and Maven

```bash
java -version  # Should show Java 21 or higher
mvn -version   # Should show Maven 3.8 or higher
```

### 3. Configure Kubernetes Access

Ensure kubectl is configured to access your cluster:

```bash
kubectl cluster-info
kubectl get nodes
```

### 4. Deploy Development Dependencies

For Strimzi MCP development, deploy the required Kubernetes resources:

```bash
# Deploy Strimzi operator and Kafka cluster
./dev/scripts/setup-strimzi.sh

# Optionally deploy Prometheus for metrics
kubectl apply -k dev/manifests/prometheus/

# Optionally deploy Loki for centralized logging
./dev/scripts/setup-loki.sh
```

## Building the Project

### Compile All Modules

```bash
mvn clean compile
```

This command:
- Compiles all Java source files
- Runs checkstyle validation
- Generates any required code

### Package Applications

```bash
# Package all modules
mvn clean package

# Skip tests during packaging
mvn clean package -DskipTests

# Package a specific module
cd strimzi-mcp
mvn clean package
```

## Running MCP Servers Locally

### Quarkus Dev Mode

Quarkus dev mode provides hot reload, debugging, and a dev UI:

```bash
cd strimzi-mcp
mvn quarkus:dev
```

The server starts on `http://localhost:8080` with:
- MCP endpoint: `http://localhost:8080/mcp`
- Health check: `http://localhost:8080/q/health`
- Dev UI: `http://localhost:8080/q/dev`

Press `h` in the terminal for dev mode commands.

### Running as a JAR

```bash
cd strimzi-mcp
mvn clean package
java -jar target/quarkus-app/quarkus-run.jar
```

### Configuration

Override configuration via environment variables or system properties:

```bash
# Using environment variables
export MCP_LOG_TAIL_LINES=500
export LOKI_URL=http://loki.monitoring:3100
mvn quarkus:dev

# Using system properties
mvn quarkus:dev -Dmcp.log.tail-lines=500 -Dloki.url=http://loki.monitoring:3100
```

## Testing MCP Servers

### Using MCP Inspector

The MCP Inspector provides a web UI for testing MCP servers:

```bash
# Start your MCP server
cd strimzi-mcp
mvn quarkus:dev

# In another terminal, start the inspector
npx @modelcontextprotocol/inspector http://localhost:8080/mcp
```

Open the URL shown in the terminal to access the inspector UI.

### Using curl

Test the MCP endpoint directly:

```bash
# Initialize connection
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "initialize",
    "params": {
      "protocolVersion": "2024-11-05",
      "capabilities": {},
      "clientInfo": {"name": "test", "version": "1.0"}
    }
  }'

# List available tools
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 2,
    "method": "tools/list",
    "params": {}
  }'
```

### Using Claude Desktop

Add the MCP server to Claude Desktop's configuration:

**macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`

**Windows**: `%APPDATA%\Claude\claude_desktop_config.json`

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

Restart Claude Desktop to load the configuration.

## Building Container Images

### Using Jib (Recommended)

Jib builds container images without Docker:

```bash
cd strimzi-mcp

# Build image locally
mvn clean package -DskipTests -Dquarkus.container-image.build=true

# Build and push to registry
mvn clean package -DskipTests \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.push=true \
  -Dquarkus.container-image.registry=quay.io \
  -Dquarkus.container-image.group=myorg \
  -Dquarkus.container-image.tag=1.0.0
```

### Using Docker/Podman

```bash
cd strimzi-mcp
mvn clean package -DskipTests

# Build with Docker
docker build -f src/main/docker/Dockerfile.jvm -t strimzi-mcp:latest .

# Build with Podman
podman build -f src/main/docker/Dockerfile.jvm -t strimzi-mcp:latest .
```

## Debugging

### Remote Debugging

Start the application with debug enabled:

```bash
mvn quarkus:dev -Ddebug=5005
```

Attach your IDE debugger to port 5005.

### Logging

Adjust log levels in `application.properties`:

```properties
# Root log level
quarkus.log.level=INFO

# Package-specific log levels
quarkus.log.category."io.streamshub.mcp".level=DEBUG
quarkus.log.category."io.fabric8.kubernetes".level=WARN
```

Or via environment variables:

```bash
export QUARKUS_LOG_LEVEL=DEBUG
export QUARKUS_LOG_CATEGORY__IO_STREAMSHUB_MCP__LEVEL=TRACE
mvn quarkus:dev
```

## Troubleshooting

### Build Failures

**Checkstyle violations**:
```bash
# View detailed checkstyle report
mvn checkstyle:checkstyle
open target/site/checkstyle.html
```

**Test failures**:
```bash
# Run tests with verbose output
mvn test -X

# Run a single test for debugging
mvn test -Dtest=MyTest#specificTestMethod
```

### Kubernetes Connection Issues

```bash
# Verify kubectl works
kubectl get pods

# Check kubeconfig
echo $KUBECONFIG
kubectl config view

# Test with a simple pod list
kubectl get pods --all-namespaces
```

### Quarkus Dev Mode Issues

```bash
# Clean and restart
mvn clean
mvn quarkus:dev

# Check for port conflicts
lsof -i :8080

# Increase memory if needed
export MAVEN_OPTS="-Xmx2g"
mvn quarkus:dev
```

## Next Steps

- **[Testing](testing.md)** — Run unit and system tests
- **[Contributing](contributing.md)** — Contribution guidelines
- **[Architecture](architecture.md)** — Understand the system design