+++
title = 'Getting started'
weight = 1
+++

Get StreamsHub MCP running and connected to an AI assistant.

## What is StreamsHub MCP?

StreamsHub MCP connects AI assistants to your Apache Kafka clusters running on Kubernetes.
It uses the [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) — an open standard that lets AI assistants call tools to interact with external systems.
Any MCP-compatible client can connect to the server.

With StreamsHub MCP, you can ask your AI assistant questions like "What is the status of my Kafka cluster?" or "Diagnose why consumers are lagging" — and it will query your actual cluster to give you a real answer.

## Available MCP servers

- **[MCP Server for Strimzi](strimzi-mcp/)** — Tools for managing and troubleshooting Apache Kafka clusters deployed with [Strimzi](https://strimzi.io/)

## Choose your setup path

Pick the option that matches your environment:

| Option | Prerequisites | Best for |
|--------|--------------|----------|
| [A — Deploy to Kubernetes](#option-a-deploy-to-kubernetes) | `kubectl` | Evaluators with an existing Kubernetes cluster |
| [B — Run as a container](#option-b-run-as-a-container) | Docker or Podman | Quick evaluation on any machine with a container runtime |
| [C — Build from source](#option-c-build-from-source) | Java 21, Maven, `git` | Development and contributions |

All options end with the same [connect your AI assistant](#connect-your-ai-assistant) step.

---

## Option A: Deploy to Kubernetes

Deploy the MCP server directly to your Kubernetes cluster using the published container image.
No build tools required.

**Prerequisites:**

| Tool | Minimum version | Install link |
|------|----------------|--------------|
| `kubectl` | 1.25+ | [Install kubectl](https://kubernetes.io/docs/tasks/tools/) |
| An MCP client | — | [MCP-compatible clients](https://modelcontextprotocol.io/clients) |

**Kubernetes cluster:** You need access to a Kubernetes cluster.
For local evaluation, [minikube](https://minikube.sigs.k8s.io/docs/start/), [kind](https://kind.sigs.k8s.io/), or [Docker Desktop](https://www.docker.com/products/docker-desktop/) all work.

### Step 1: Deploy Strimzi and a Kafka cluster

Get the repository files on disk and run the setup script from the root directory.
You can clone with git, or download and extract a ZIP from the
[GitHub repository page](https://github.com/streamshub/streamshub-mcp) (the **Code → Download ZIP** button):

```bash
git clone https://github.com/streamshub/streamshub-mcp.git
cd streamshub-mcp
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

### Step 2: Deploy the MCP server

Replace `<version>` with a tag from the [releases page](https://github.com/streamshub/streamshub-mcp/releases) (e.g., `v0.1.0`):

```bash
kubectl apply -k "https://github.com/streamshub/streamshub-mcp/install/strimzi-mcp/overlays/dev?ref=<version>"
```

> **Note:** The `dev` overlay is sized for local evaluation (1 replica, `imagePullPolicy: Always`).
> For production use, see the [Production deployment](#production-deployment) section at the bottom of this page.

**Wait for the server to be ready:**

```bash
kubectl -n streamshub-mcp rollout status deployment/streamshub-mcp-strimzi --timeout=120s
```

### Step 3: Access the server

Use port-forwarding to reach the server from your local machine:

```bash
kubectl -n streamshub-mcp port-forward svc/streamshub-mcp-strimzi 8080:8080
```

Leave this running in a terminal. The MCP endpoint is now available at `http://localhost:8080/mcp`.

**Verify it is healthy:**

```bash
curl -s http://localhost:8080/q/health/ready
```

**Expected output:** `{"status":"UP", ...}`

Now proceed to [Connect your AI assistant](#connect-your-ai-assistant).

---

## Option B: Run as a container

Run the MCP server locally using Docker or Podman, with your existing kubeconfig mounted into the container.
No Kubernetes deployment or build tools required — only a container runtime and a valid kubeconfig.

**Prerequisites:**

| Tool | Install link |
|------|--------------|
| Docker or Podman | [Install Docker](https://docs.docker.com/get-docker/) / [Install Podman](https://podman.io/getting-started/installation) |
| A kubeconfig with cluster access | Usually `~/.kube/config` |
| An MCP client | [MCP-compatible clients](https://modelcontextprotocol.io/clients) |

The image is available for both `linux/amd64` and `linux/arm64` (Apple Silicon).

### Step 1: Run the container

**Docker:**

```bash
docker run -i --rm \
  -p 8080:8080 \
  -v "$HOME/.kube:/home/jboss/.kube:ro" \
  -e QUARKUS_KUBERNETES_CLIENT_TRUST_CERTS=true \
  quay.io/streamshub/strimzi-mcp:latest
```

**Podman:**

```bash
podman run -i --rm \
  -p 8080:8080 \
  -v "$HOME/.kube:/home/jboss/.kube:ro,z" \
  -e QUARKUS_KUBERNETES_CLIENT_TRUST_CERTS=true \
  quay.io/streamshub/strimzi-mcp:latest
```

The server starts in a few seconds. The MCP endpoint is available at `http://localhost:8080/mcp`.

**Verify it is healthy** in a second terminal:

```bash
curl -s http://localhost:8080/q/health/ready
```

**Expected output:** `{"status":"UP", ...}`

### Notes on the container run options

**`-v "$HOME/.kube:/home/jboss/.kube:ro"`**
Mounts your kubeconfig directory into the container read-only.
The server reads credentials and cluster addresses from it but never writes to it.
If your kubeconfig is at a non-standard path, set the `KUBECONFIG` environment variable instead:

```bash
-v "/path/to/kubeconfig:/tmp/kubeconfig:ro" \
-e KUBECONFIG=/tmp/kubeconfig
```

**`QUARKUS_KUBERNETES_CLIENT_TRUST_CERTS=true`**
Required for local clusters (minikube, kind, Docker Desktop) whose self-signed CA certificates are not trusted by the container's system trust store.
**Do not use this in production** — it disables TLS certificate validation for all Kubernetes API connections.
For production use, deploy the server inside the cluster instead (see [Option A](#option-a-deploy-to-kubernetes) or the [installation guide](strimzi-mcp/installation.md)).

**Kubernetes API on localhost**
If your cluster's API server address in kubeconfig is `localhost` or `127.0.0.1` (common with Docker Desktop and kind), the container cannot reach it via those addresses.
Use `host.docker.internal` (Docker) or `host.containers.internal` (Podman) instead, or add `--network host` to the run command:

```bash
# Docker — use host network (Linux only)
docker run -i --rm --network host \
  -v "$HOME/.kube:/home/jboss/.kube:ro" \
  -e QUARKUS_KUBERNETES_CLIENT_TRUST_CERTS=true \
  quay.io/streamshub/strimzi-mcp:latest
```

On macOS, `--network host` is not supported. Update your kubeconfig server URL to use `host.docker.internal`:

```bash
# Show current server URL
kubectl config view --minify -o jsonpath='{.clusters[0].cluster.server}'

# Replace localhost with host.docker.internal if needed
# e.g. https://127.0.0.1:6443 → https://host.docker.internal:6443
```

> **Tip:** If you do not have a Strimzi cluster to connect to yet, follow [Option A Step 1](#step-1-deploy-strimzi-and-a-kafka-cluster) first to deploy one, then return here to run the container.

Now proceed to [Connect your AI assistant](#connect-your-ai-assistant).

---

## Option C: Build from source

Use this option for development, or if you want to run the server with local code changes.

**Prerequisites:**

| Tool | Minimum version | Install link |
|------|----------------|--------------|
| `kubectl` | 1.25+ | [Install kubectl](https://kubernetes.io/docs/tasks/tools/) |
| `git` | Any | [Install git](https://git-scm.com/downloads) |
| Java (JDK) | 21+ | [Install Java](https://adoptium.net/) |
| Maven | 3.8+ | Included in repo as `mvnw` |
| An MCP client | — | [MCP-compatible clients](https://modelcontextprotocol.io/clients) |

**Verify your setup** before continuing:

```bash
kubectl version --client
java -version
```

Both commands should succeed.

### Step 1: Clone the repository

```bash
git clone https://github.com/streamshub/streamshub-mcp.git
cd streamshub-mcp
```

### Step 2: Deploy Strimzi and a Kafka cluster

```bash
./dev/scripts/setup-strimzi.sh deploy
```

**Wait for the cluster to be ready** (this may take 2-5 minutes):

```bash
kubectl wait kafka/mcp-cluster --for=condition=Ready --timeout=300s -n strimzi-kafka
```

**Expected output:**

```
kafka.kafka.strimzi.io/mcp-cluster condition met
```

> **Troubleshooting:** If the wait times out, check pod status with `kubectl get pods -n strimzi-kafka`.
> See [troubleshooting](strimzi-mcp/troubleshooting.md#pods-not-starting) for common issues.

### Step 3: Start the MCP server

From the repository root, start the server in Quarkus dev mode:

```bash
./mvnw quarkus:dev -pl strimzi-mcp
```

**Verify the server is running** by opening a new terminal and running:

```bash
curl -s http://localhost:8080/q/health/ready
```

**Expected output:** `{"status":"UP", ...}`

The MCP endpoint is available at `http://localhost:8080/mcp`.

> **Troubleshooting:** If the server fails to start, check that port 8080 is not in use (`lsof -i :8080`) and that Java 21+ is installed (`java -version`).
> See [troubleshooting](strimzi-mcp/troubleshooting.md#server-does-not-start) for more details.

Now proceed to [Connect your AI assistant](#connect-your-ai-assistant).

---

## Connect your AI assistant

The MCP server uses **HTTP Streamable transport** on `http://localhost:8080/mcp`.
Connect any MCP-compatible client by pointing it to this URL.

Most MCP clients require two pieces of information:

- **Transport type:** HTTP (or "Streamable HTTP")
- **Server URL:** `http://localhost:8080/mcp`

Refer to your AI client's documentation for how to add an MCP server.
A full list of MCP-compatible clients is available at [modelcontextprotocol.io](https://modelcontextprotocol.io/clients).

### Example: Claude Code

```bash
claude mcp add --transport http strimzi http://localhost:8080/mcp
```

### Example: Claude Desktop

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

### Example: VS Code (GitHub Copilot)

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

---

## Verify the setup

Ask your AI assistant one of these questions:

- *"List all Kafka clusters"*
- *"What is the status of mcp-cluster?"*
- *"Diagnose issues with mcp-cluster"*

**What a successful response looks like:**

The AI assistant should return information about your `mcp-cluster`, including its status (`Ready`), the number of brokers, and listeners.
If it says it cannot find any clusters or cannot connect, check that the MCP server is running and the connection is configured correctly.

## Production deployment

For production use, deploy the MCP server inside your Kubernetes cluster using the prod overlay:

```bash
# Standard Kubernetes
kubectl apply -k "https://github.com/streamshub/streamshub-mcp/install/strimzi-mcp/overlays/prod?ref=<version>"

# OpenShift (includes TLS Route)
kubectl apply -k "https://github.com/streamshub/streamshub-mcp/install/strimzi-mcp/overlays/prod-openshift?ref=<version>"
```

See the [installation guide](strimzi-mcp/installation.md) for detailed instructions on Kubernetes deployment, RBAC configuration, and external access.

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
