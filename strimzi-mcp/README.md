# MCP Server for Strimzi

A Quarkus application that provides management tools for Apache Kafka clusters deployed by Strimzi via MCP (Model Context Protocol) for AI assistants and automation.

> [!WARNING]
> This project is in early alpha version and under active development. APIs, tool definitions, and configuration may change without notice.

## Overview

The MCP Server for Strimzi enables AI assistants to manage and troubleshoot Strimzi-managed Kafka clusters on Kubernetes.
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

Full documentation lives in the [`documentation/`](../documentation/) directory as modular AsciiDoc. Build it to HTML with `./mvnw -N -Pdocs package` (output in `target/documentation/`).

- **[Getting started](../documentation/assemblies/assembly-getting-started.adoc)** -- Quick start guide
- **[Installation](../documentation/assemblies/assembly-installing.adoc)** -- Kubernetes deployment, RBAC, and access configuration
- **[Configuration](../documentation/assemblies/assembly-configuring.adoc)** -- Environment variables, Loki, Prometheus, and security settings
- **[Tools reference](../documentation/assemblies/assembly-tools-reference.adoc)** -- Complete tool catalog with parameters
- **[Usage examples](../documentation/assemblies/assembly-usage-examples.adoc)** -- Practical workflows
- **[Troubleshooting](../documentation/assemblies/assembly-troubleshooting.adoc)** -- Common issues and solutions

## License

[Apache License 2.0](../LICENSE)
