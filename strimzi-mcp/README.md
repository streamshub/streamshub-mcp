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

## Key features

- **Diagnostic tools** -- Multi-step workflows with LLM-guided triage for cluster health, connectivity, metrics, and operator analysis
- **Cluster management** -- List and inspect clusters, topics, node pools, operators, pods, certificates, and bootstrap servers
- **Log collection** -- Automatic error detection with filtering by level, keywords, and time range (Kubernetes API or Grafana Loki)
- **Metrics analysis** -- Category-based queries for replication, throughput, performance, and resources (pod scraping or Prometheus)
- **Security guardrails** -- Log redaction, response size limits, and per-category rate limiting
- **Resource monitoring** -- Real-time Kubernetes watch subscriptions with automatic change notifications

## Documentation

- **[Getting started](../docs/getting-started.md)** -- Quick start guide
- **[Installation](../docs/strimzi-mcp/installation.md)** -- Kubernetes deployment, RBAC, and access configuration
- **[Configuration](../docs/strimzi-mcp/configuration.md)** -- Environment variables, Loki, Prometheus, and security settings
- **[Tools reference](../docs/strimzi-mcp/tools/)** -- Complete tool catalog with parameters
- **[Usage examples](../docs/strimzi-mcp/usage-examples.md)** -- Practical workflows
- **[Troubleshooting](../docs/strimzi-mcp/troubleshooting.md)** -- Common issues and solutions

## License

[Apache License 2.0](../LICENSE)
