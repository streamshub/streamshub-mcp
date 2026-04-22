+++
title = 'Getting started'
weight = 1
+++

This guide describes how to set up and start using StreamsHub MCP.

## What is StreamsHub MCP?

StreamsHub MCP connects AI assistants to Kubernetes-based streaming infrastructure through the Model Context Protocol (MCP).
AI assistants use MCP tools to query your cluster and provide answers based on real-time data.

## Available MCP servers

- **[Strimzi MCP Server](strimzi-mcp/)** -- Tools for managing and troubleshooting Apache Kafka clusters deployed with Strimzi

## Prerequisites

Before you begin, ensure you have:

- A Kubernetes cluster with `kubectl` access
- An AI assistant that supports MCP (Claude Desktop, Claude Code, or similar)
- Java 21 or later and Maven 3.8 or later (for local development)

## Quick start with Strimzi MCP

This example deploys the Strimzi MCP Server for local development.
For detailed instructions including Kubernetes deployment, see the [installation guide](strimzi-mcp/installation.md).

### Step 1: Clone the repository

```bash
git clone https://github.com/streamshub/streamshub-mcp.git
cd streamshub-mcp
```

### Step 2: Deploy prerequisites

To deploy the Strimzi operator and a sample Kafka cluster, run the setup script:

```bash
./dev/scripts/setup-strimzi.sh deploy
```

The script deploys the Strimzi operator and creates a Kafka cluster named `mcp-cluster`.

### Step 3: Start the MCP server

```bash
cd strimzi-mcp
../mvnw quarkus:dev
```

The server starts on `http://localhost:8080/mcp`.

### Step 4: Connect your AI assistant

For Claude Code:

```bash
claude mcp add --transport http strimzi http://localhost:8080/mcp
```

For Claude Desktop, add the following to your configuration file at `~/Library/Application Support/Claude/claude_desktop_config.json` (on macOS):

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

Restart Claude Desktop after making this change.

### Step 5: Verify the setup

Ask your AI assistant:

- "List all Kafka clusters"
- "What is the status of mcp-cluster?"
- "Diagnose issues with mcp-cluster"

The AI assistant uses the MCP tools to query your cluster and provide answers.

## Production deployment

For production use, deploy MCP servers to Kubernetes instead of running them locally:

```bash
kubectl apply -f strimzi-mcp/install/
```

For detailed instructions on Kubernetes deployments, RBAC configuration, and external access, see the [installation guide](strimzi-mcp/installation.md#kubernetes-deployment).

## Next steps

- **[Installation](strimzi-mcp/installation.md)** -- Detailed setup including Kubernetes deployment
- **[Configuration](strimzi-mcp/configuration.md)** -- Configure Loki and Prometheus integration
- **[Tools reference](strimzi-mcp/tools/)** -- Available tools and prompts
- **[Usage examples](strimzi-mcp/usage-examples.md)** -- Practical workflows
- **[Troubleshooting](strimzi-mcp/troubleshooting.md)** -- Resolve common issues
