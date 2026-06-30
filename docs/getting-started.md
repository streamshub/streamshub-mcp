+++
title = 'Getting started'
weight = 1
+++

Get StreamsHub MCP running and connected to an AI assistant in under 15 minutes.

## What is StreamsHub MCP?

StreamsHub MCP connects AI assistants to your Apache Kafka clusters running on Kubernetes.
It uses the [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) — an open standard that lets AI assistants call tools to interact with external systems.
Any MCP-compatible client can connect to the server.

With StreamsHub MCP, you can ask your AI assistant questions like "What is the status of my Kafka cluster?" or "Diagnose why consumers are lagging" — and it will query your actual cluster to give you a real answer.

**By the end of this guide, you will have:**

1. A Kafka cluster running on Kubernetes (via Strimzi)
2. The MCP server running and connected to that cluster
3. An AI assistant querying your cluster through MCP

## Available MCP servers

- **[MCP Server for Strimzi](strimzi-mcp/)** — Tools for managing and troubleshooting Apache Kafka clusters deployed with [Strimzi](https://strimzi.io/)

## Prerequisites

You need the following tools installed before starting:

| Tool | Minimum version | Purpose | Install link |
|------|----------------|---------|--------------|
| `kubectl` | 1.25+ | Kubernetes CLI | [Install kubectl](https://kubernetes.io/docs/tasks/tools/) |
| `git` | Any | Clone the repository | [Install git](https://git-scm.com/downloads) |
| Java (JDK) | 21+ | Build and run the MCP server locally | [Install Java](https://adoptium.net/) |
| Maven | 3.8+ | Build tool (or use the included `mvnw` wrapper) | Included in repo |
| An MCP client | - | AI assistant to connect | See Step 4 |

**Kubernetes cluster:** You need access to a Kubernetes cluster.
For local development, [minikube](https://minikube.sigs.k8s.io/docs/start/), [kind](https://kind.sigs.k8s.io/), or [Docker Desktop](https://www.docker.com/products/docker-desktop/) all work.

**Verify your setup** before continuing:

```bash
kubectl version --client
java -version
```

Both commands should succeed.
If either fails, install the missing tool from the links above.

## Quick start with MCP Server for Strimzi

This guide deploys the MCP Server for Strimzi for local development.
For Kubernetes deployment, see the [installation guide](strimzi-mcp/installation.md).

### Step 1: Clone the repository

```bash
git clone https://github.com/streamshub/streamshub-mcp.git
cd streamshub-mcp
```

### Step 2: Deploy Strimzi and a Kafka cluster

Run the setup script from the repository root:

```bash
./dev/scripts/setup-strimzi.sh deploy
```

The script installs the Strimzi operator and creates a Kafka cluster named `mcp-cluster`.

**Wait for the cluster to be ready** (this may take 2-5 minutes):

```bash
kubectl wait kafka/mcp-cluster --for=condition=Ready --timeout=300s -n strimzi-kafka
```

**Expected output:**

```
kafka.kafka.strimzi.io/mcp-cluster condition met
```

> **Troubleshooting:** If the wait times out, check pod status with `kubectl get pods -n strimzi-kafka`.
> If pods are in `Pending` state, your Kubernetes cluster may not have enough resources.
> See [troubleshooting](strimzi-mcp/troubleshooting.md#pods-not-starting) for common issues.

### Step 3: Start the MCP server

From the repository root, start the server in Quarkus dev mode:

```bash
cd strimzi-mcp
../mvnw quarkus:dev
```

**Verify the server is running** by opening a new terminal and running:

```bash
curl -s http://localhost:8080/q/health | head -1
```

**Expected output:**

```json
{"status":"UP", ...}
```

The MCP endpoint is available at `http://localhost:8080/mcp`.

> **Troubleshooting:** If the server fails to start, check that port 8080 is not in use (`lsof -i :8080`) and that Java 21+ is installed (`java -version`).
> See [troubleshooting](strimzi-mcp/troubleshooting.md#server-does-not-start) for more details.

### Step 4: Connect your AI assistant

The MCP server uses **HTTP Streamable transport** on `http://localhost:8080/mcp`.
Connect any MCP-compatible client by pointing it to this URL.

#### General setup

Most MCP clients require two pieces of information:

- **Transport type:** HTTP (or "Streamable HTTP")
- **Server URL:** `http://localhost:8080/mcp`

Refer to your AI client's documentation for how to add an MCP server.
A list of MCP-compatible clients is available at [modelcontextprotocol.io](https://modelcontextprotocol.io/clients).

#### Example: Claude Code

```bash
claude mcp add --transport http strimzi http://localhost:8080/mcp
```

#### Example: Claude Desktop

Add to your configuration file (`~/Library/Application Support/Claude/claude_desktop_config.json` on macOS, `%APPDATA%\Claude\claude_desktop_config.json` on Windows):

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

Restart the application after saving.

#### Example: VS Code (GitHub Copilot)

Add to your `.vscode/mcp.json`:

```json
{
  "servers": {
    "strimzi": {
      "type": "http",
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

> **Troubleshooting:** If your AI client does not detect the tools, verify the MCP server is running (`curl http://localhost:8080/q/health`) and that the URL is correct.
> See [troubleshooting](strimzi-mcp/troubleshooting.md#server-starts-but-ai-assistant-cannot-connect) for more details.

### Step 5: Verify the setup

Ask your AI assistant one of these questions:

- *"List all Kafka clusters"*
- *"What is the status of mcp-cluster?"*
- *"Diagnose issues with mcp-cluster"*

**What a successful response looks like:**

The AI assistant should return information about your `mcp-cluster`, including its status (`Ready`), the number of brokers, and listeners.
If it says it cannot find any clusters or cannot connect, check that the MCP server is running and the connection is configured correctly.

## Production deployment

For production use, deploy the MCP server to Kubernetes instead of running it locally:

```bash
# Standard Kubernetes
kubectl apply -k install/strimzi-mcp/overlays/prod/

# OpenShift (includes TLS Route)
kubectl apply -k install/strimzi-mcp/overlays/prod-openshift/
```

See the [installation guide](strimzi-mcp/installation.md#kubernetes-deployment) for detailed instructions on Kubernetes deployment, RBAC configuration, and external access.

## What's next?

Now that you have the MCP server running, explore these paths based on your goals:

**Explore your cluster:**

- Try asking *"Show me the topology of mcp-cluster"* or *"List all topics"*
- See [usage examples](strimzi-mcp/usage-examples.md) for more practical workflows

**Try diagnostics:**

- Ask *"Diagnose connectivity issues with mcp-cluster"* to see multi-step diagnostic workflows
- See [diagnostic tools](strimzi-mcp/tools/diagnostics.md) for all available diagnostics

**Set up monitoring:**

- Connect Loki for historical log queries — see [Loki integration](strimzi-mcp/configuration.md#loki-integration)
- Connect Prometheus for metrics — see [Prometheus integration](strimzi-mcp/configuration.md#prometheus-integration)

**Learn more:**

- **[Tools reference](strimzi-mcp/tools/)** — All available tools and prompts
- **[Configuration](strimzi-mcp/configuration.md)** — Customize server behavior
- **[AI agent best practices](strimzi-mcp/ai-agent-best-practices.md)** — Get the most out of AI-assisted Kafka management
- **[Troubleshooting](strimzi-mcp/troubleshooting.md)** — Resolve common issues
