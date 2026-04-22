# Strimzi MCP Server

A Quarkus application that provides Strimzi Kafka management tools via MCP (Model Context Protocol) for AI assistants and automation.

> [!WARNING]
> This project is in early alpha version and under active development. APIs, tool definitions, and configuration may change without notice.

## Overview

The Strimzi MCP Server enables AI assistants to manage and troubleshoot Strimzi-managed Kafka clusters on Kubernetes.
Ask your AI assistant questions like:

- "List all Kafka clusters"
- "Show me error logs from the last hour"
- "Are there any under-replicated partitions?"
- "Diagnose connectivity issues"

The AI assistant uses MCP tools to interact with your Kubernetes cluster and provide answers.

## Quick start

### Prerequisites

- Kubernetes cluster with Strimzi installed
- `kubectl` configured to access your cluster
- Java 21 or later and Maven 3.8 or later (for local development)

#### Deploy Strimzi (if needed)

```bash
../dev/scripts/setup-strimzi.sh deploy
```

The script also supports optional flags for deploying observability infrastructure:

```bash
# Deploy Strimzi with Prometheus and Loki
../dev/scripts/setup-strimzi.sh deploy --prometheus --loki

# Or deploy them separately
../dev/scripts/setup-prometheus.sh deploy
../dev/scripts/setup-loki.sh deploy
```

### Run locally

```bash
../mvnw clean package
../mvnw quarkus:dev
```

The server starts on `http://localhost:8080/mcp`.

### Connect AI assistant

For Claude Code:

```bash
claude mcp add --transport http strimzi http://localhost:8080/mcp
```

For Claude Desktop, add the following to your configuration file:

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

### Verify

Ask your AI assistant:

- "List all Kafka clusters"
- "What is the status of mcp-cluster?"
- "Diagnose issues with mcp-cluster"

## Documentation

For comprehensive documentation, see [`../docs/strimzi-mcp/`](../docs/strimzi-mcp/):

- **[Installation](../docs/strimzi-mcp/installation.md)** -- Strimzi MCP deployment locally or on top of Kubernetes
- **[Configuration](../docs/strimzi-mcp/configuration.md)** -- Details about configuration options, integrations, security
- **[Tools reference](../docs/strimzi-mcp/tools/)** -- Complete tool catalog with parameters
- **[Usage examples](../docs/strimzi-mcp/usage-examples.md)** -- Practical workflows
- **[Troubleshooting](../docs/strimzi-mcp/troubleshooting.md)** -- Common issues and solutions

## Key features

### Composite diagnostic tools

Multi-step workflows with LLM-guided triage:

- **`diagnose_kafka_cluster`** -- Comprehensive cluster health check
- **`diagnose_kafka_connectivity`** -- Connectivity troubleshooting
- **`diagnose_kafka_metrics`** -- Metrics analysis and anomaly detection
- **`diagnose_operator_metrics`** -- Operator performance analysis

### Cluster management tools

- **List and inspect** -- Clusters, topics, node pools, operators
- **Pod operations** -- Status, logs, events
- **Bootstrap servers** -- Connection endpoints
- **Certificates** -- TLS certificate information

### Log collection

Collects logs from Kafka and Strimzi operator pods with automatic error detection and categorization, filtering by level, keywords, and time range, and support for multiple providers (Kubernetes API or Grafana Loki).
Long-running collections report real-time progress to the client.

### Metrics analysis

Queries metrics from Kafka brokers and the Strimzi operator with category-based queries (replication, throughput, performance, resources), built-in interpretation guides with thresholds, and support for multiple providers (pod scraping or Prometheus).
Also includes Kafka Exporter metrics for consumer lag and topic partition analysis.

### Security guardrails

- **Log redaction** -- Automatic removal of sensitive patterns
- **Response size limits** -- Prevents excessive responses
- **Rate limiting** -- Per-category request throttling

### Resource monitoring

Real-time subscriptions to Kubernetes resources with automatic notifications when state changes.

## Kubernetes deployment

```bash
# Build container image
../mvnw clean package -DskipTests \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.push=true

# Deploy to Kubernetes
kubectl apply -f install/

# Verify
kubectl -n streamshub-mcp get pods
```

For detailed instructions, see the [installation guide](../docs/strimzi-mcp/installation.md).

## Configuration

The following table shows key environment variables for configuring log and metrics providers:

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `MCP_LOG_TAIL_LINES` | `200` | Default log lines per pod |
| `MCP_LOG_PROVIDER` | `streamshub-kubernetes` | Log provider: `streamshub-kubernetes` or `streamshub-loki` |
| `MCP_METRICS_PROVIDER` | `streamshub-pod-scraping` | Metrics provider: `streamshub-pod-scraping` or `streamshub-prometheus` |
| `QUARKUS_REST_CLIENT_LOKI_URL` | - | Loki endpoint URL |
| `QUARKUS_REST_CLIENT_PROMETHEUS_URL` | - | Prometheus endpoint URL |

For complete configuration options, see the [configuration guide](../docs/strimzi-mcp/configuration.md).

## Requirements

- Java 21 or later
- Maven 3.8 or later
- Access to a Kubernetes cluster with Strimzi

## License

[Apache License 2.0](../LICENSE)
