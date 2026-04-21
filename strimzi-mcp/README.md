# Strimzi MCP Server

A Quarkus application that provides Strimzi Kafka management tools via MCP (Model Context Protocol) for AI assistants and automation.

> [!WARNING]
> This project is in early alpha and under active development. APIs, tool definitions, and configuration may change without notice.

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

### Deploy Strimzi (if needed)

```bash
../dev/scripts/setup-strimzi.sh
```

### Run locally

```bash
mvn clean package
mvn quarkus:dev
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

- **[Installation](../docs/strimzi-mcp/installation.md)** -- Local and Kubernetes deployment
- **[Configuration](../docs/strimzi-mcp/configuration.md)** -- Environment variables, integrations, security
- **[Tools reference](../docs/strimzi-mcp/tools.md)** -- Complete tool catalog with parameters
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

- **Error analysis** -- Automatic detection and categorization
- **Advanced filtering** -- By log level, keywords, time ranges
- **Multiple providers** -- Kubernetes API or Loki
- **Progress tracking** -- Real-time updates

### Metrics analysis

- **Category-based queries** -- Replication, throughput, performance, resources
- **Interpretation guides** -- Thresholds and diagnostic recommendations
- **Multiple providers** -- Pod scraping or Prometheus
- **Kafka Exporter metrics** -- Consumer lag, topic partitions

### Security guardrails

- **Log redaction** -- Automatic removal of sensitive patterns
- **Response size limits** -- Prevents excessive responses
- **Rate limiting** -- Per-category request throttling

### Resource monitoring

Real-time subscriptions to Kubernetes resources with automatic notifications when state changes.

## Kubernetes deployment

```bash
# Build container image
mvn clean package -DskipTests \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.push=true

# Deploy to Kubernetes
kubectl apply -f install/

# Verify
kubectl -n streamshub-mcp get pods
```

For detailed instructions, see the [installation guide](../docs/strimzi-mcp/installation.md).

## Configuration

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
