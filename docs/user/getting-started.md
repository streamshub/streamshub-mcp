+++
title = 'Getting Started'
weight = 1
+++

Get up and running with StreamsHub MCP in 5 minutes.

## What is StreamsHub MCP?

StreamsHub MCP provides AI assistants with direct access to your Kubernetes-based streaming infrastructure through the Model Context Protocol (MCP). Ask questions like "What's the status of my Kafka cluster?" or "Show me error logs" - your AI assistant uses MCP tools to interact with your cluster and provide answers.

## Available MCP Servers

StreamsHub MCP is a mono-repository containing multiple MCP servers:

- **[Strimzi MCP Server](mcp-servers/strimzi-mcp/)** — Manage and troubleshoot Apache Kafka clusters via Strimzi operator
- More MCP servers coming soon...

## Prerequisites

- **Kubernetes cluster** with kubectl access
- **AI assistant** that supports MCP (Claude Desktop, Claude Code, etc.)
- **Java 21+** and **Maven 3.8+** (for local development)

## Quick Start with Strimzi MCP

This example uses the Strimzi MCP Server. For detailed instructions, see the [Strimzi MCP Installation Guide](mcp-servers/strimzi-mcp/installation.md).

### 1. Clone the Repository

```bash
git clone https://github.com/streamshub/streamshub-mcp.git
cd streamshub-mcp
```

### 2. Deploy Prerequisites

For Strimzi MCP, deploy the Strimzi operator and a sample Kafka cluster:

```bash
./dev/scripts/setup-strimzi.sh
```

### 3. Start an MCP Server

```bash
# Start Strimzi MCP Server
cd strimzi-mcp
mvn quarkus:dev
```

The server starts on `http://localhost:8080/mcp`

### 4. Connect Your AI Assistant

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

### 5. Try It

Ask your AI assistant:
- "List all Kafka clusters"
- "What's the status of mcp-cluster?"
- "Diagnose issues with mcp-cluster"

## Next Steps

### For Strimzi MCP Users

- **[Strimzi MCP Documentation](mcp-servers/strimzi-mcp/)** — Complete feature reference
- **[Installation Guide](mcp-servers/strimzi-mcp/installation.md)** — Detailed setup instructions
- **[Configuration](mcp-servers/strimzi-mcp/configuration.md)** — Configure Loki and Prometheus
- **[Tools Reference](mcp-servers/strimzi-mcp/tools.md)** — Available tools and prompts
- **[Usage Examples](mcp-servers/strimzi-mcp/usage-examples.md)** — Practical workflows
- **[Troubleshooting](mcp-servers/strimzi-mcp/troubleshooting.md)** — Resolve common issues

### General Documentation

- **[Architecture](architecture.md)** — System design and components
- **[Development Guide](development.md)** — Build and contribute
- **[Deployment Guide](deployment.md)** — Production deployment patterns
- **[Modules](modules/)** — Shared library documentation

## Production Deployment

For production use, deploy MCP servers to Kubernetes:

```bash
# Example: Deploy Strimzi MCP Server
cd strimzi-mcp
kubectl apply -f install/
```

See the [Deployment Guide](deployment.md) for detailed instructions.

## Need Help?

- **[GitHub Issues](https://github.com/streamshub/streamshub-mcp/issues)** — Report bugs
- **[GitHub Discussions](https://github.com/streamshub/streamshub-mcp/discussions)** — Ask questions
- **[Troubleshooting](mcp-servers/strimzi-mcp/troubleshooting.md)** — Common issues

## What's Next?

Explore the available MCP servers:

- **[MCP Servers](mcp-servers/)** — Browse all available servers
- **[Strimzi MCP](mcp-servers/strimzi-mcp/)** — Kafka cluster management