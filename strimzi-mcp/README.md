# Strimzi MCP Server

A Quarkus application that provides Strimzi Kafka management tools via **MCP (Model Context Protocol)** for AI assistants and automation.

> [!WARNING]
> This project is in early alpha and under active development. APIs, tool definitions, and configuration may change without notice.

## What is Strimzi MCP?

Strimzi MCP enables AI assistants to manage and troubleshoot Strimzi-managed Kafka clusters on Kubernetes. Ask your AI assistant questions like:

- "What's the status of my Kafka cluster?"
- "Show me error logs from the last hour"
- "Are there any under-replicated partitions?"
- "Diagnose connectivity issues"

The AI assistant uses MCP tools to interact with your Kubernetes cluster and provide answers.

## Quick Start

### Prerequisites

- Kubernetes cluster with Strimzi installed
- kubectl configured to access your cluster
- Java 21+ and Maven 3.8+ (for local development)

### Deploy Strimzi (if needed)

```bash
# Deploy Strimzi operator and sample Kafka cluster
../dev/scripts/setup-strimzi.sh
```

### Run Locally

```bash
mvn clean package
mvn quarkus:dev
```

Server starts on `http://localhost:8080/mcp`

### Connect AI Assistant

**Claude Code**:
```bash
claude mcp add --transport http strimzi http://localhost:8080/mcp
```

**Claude Desktop**: Add to configuration file:
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

### Try It

Ask your AI assistant:
- "List all Kafka clusters"
- "What's the status of mcp-cluster?"
- "Diagnose issues with mcp-cluster"

## Documentation

**📚 For comprehensive documentation, see [`../docs/user/mcp-servers/strimzi-mcp/`](../docs/user/mcp-servers/strimzi-mcp/)**

The full documentation covers:
- **[Installation](../docs/user/mcp-servers/strimzi-mcp/installation.md)** — Local and Kubernetes deployment
- **[Configuration](../docs/user/mcp-servers/strimzi-mcp/configuration.md)** — Environment variables, integrations, security
- **[Tools Reference](../docs/user/mcp-servers/strimzi-mcp/tools.md)** — Complete tool catalog with parameters
- **[Usage Examples](../docs/user/mcp-servers/strimzi-mcp/usage-examples.md)** — Practical workflows
- **[Troubleshooting](../docs/user/mcp-servers/strimzi-mcp/troubleshooting.md)** — Common issues and solutions

## Key Features

### Composite Diagnostic Tools

Multi-step workflows with LLM-guided triage:
- **`diagnose_kafka_cluster`** — Comprehensive cluster health check
- **`diagnose_kafka_connectivity`** — Connectivity troubleshooting
- **`diagnose_kafka_metrics`** — Metrics analysis and anomaly detection
- **`diagnose_operator_metrics`** — Operator performance analysis

### Cluster Management Tools

- **List and inspect** — Clusters, topics, node pools, operators
- **Pod operations** — Status, logs, events
- **Bootstrap servers** — Get connection endpoints
- **Certificates** — TLS certificate information

### Advanced Log Collection

- **Error analysis** — Automatic detection and categorization
- **Advanced filtering** — By log level, keywords, time ranges
- **Multiple providers** — Kubernetes API or Loki
- **Progress tracking** — Real-time updates

### Metrics Analysis

- **Category-based queries** — Replication, throughput, performance, resources
- **Interpretation guides** — Thresholds and diagnostic recommendations
- **Multiple providers** — Pod scraping or Prometheus
- **Kafka Exporter metrics** — Consumer lag, topic partitions

### Security Guardrails

- **Log redaction** — Automatic removal of sensitive patterns
- **Response size limits** — Prevents excessive responses
- **Rate limiting** — Per-category request throttling

### Resource Monitoring

Real-time subscriptions to Kubernetes resources with automatic notifications when state changes.

## Kubernetes Deployment

```bash
# Build container image
mvn clean package -DskipTests -Dquarkus.container-image.build=true -Dquarkus.container-image.push=true

# Deploy to Kubernetes
kubectl apply -f install/

# Verify
kubectl -n streamshub-mcp get pods
```

See [Deployment Guide](../docs/deployment.md) for detailed instructions.

## Configuration

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `MCP_LOG_TAIL_LINES` | `200` | Default log lines per pod |
| `MCP_LOG_PROVIDER` | `streamshub-kubernetes` | Log provider: `streamshub-kubernetes` or `streamshub-loki` |
| `MCP_METRICS_PROVIDER` | `streamshub-pod-scraping` | Metrics provider: `streamshub-pod-scraping` or `streamshub-prometheus` |
| `QUARKUS_REST_CLIENT_LOKI_URL` | - | Loki endpoint URL |
| `QUARKUS_REST_CLIENT_PROMETHEUS_URL` | - | Prometheus endpoint URL |

See [Configuration Guide](../docs/user/mcp-servers/strimzi-mcp/configuration.md) for complete configuration options including security, integrations, and advanced settings.

## Requirements

- Java 21+
- Maven 3.8+
- Access to Kubernetes cluster with Strimzi

## License

[Apache License 2.0](../LICENSE)
