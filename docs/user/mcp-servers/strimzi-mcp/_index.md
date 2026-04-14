---
title: "Strimzi MCP Server"
weight: 1
---

The Strimzi MCP Server provides AI assistants with comprehensive access to Strimzi-managed Kafka clusters on Kubernetes through the Model Context Protocol.

> [!WARNING]
> This project is in early alpha and under active development. APIs, tool definitions, and configuration may change without notice.

## Overview

The Strimzi MCP Server enables AI assistants to:
- Manage and monitor Strimzi-managed Kafka clusters on Kubernetes
- Run comprehensive diagnostic workflows with LLM-guided triage
- Collect and analyze logs with advanced filtering
- Query and analyze metrics from Kafka and operators
- Troubleshoot connectivity and performance issues
- Monitor Strimzi operator health and reconciliation
- Track Kubernetes events for cluster resources

## Key Features

### Smart Discovery
All tools support automatic namespace discovery — the namespace parameter is always optional. Tools automatically search across the entire cluster when omitted, making it easy to work with multi-namespace deployments.

### Composite Diagnostic Tools
Multi-step diagnostic workflows that gather comprehensive data in a single tool call:
- **LLM-guided triage** using Sampling to decide which areas need investigation
- **Namespace disambiguation** using Elicitation when multiple namespaces match
- **Graceful degradation** — works without Sampling/Elicitation support
- **Step failure resilience** — individual step failures don't abort the workflow

Available diagnostics:
- [`diagnose_kafka_cluster`](tools.md#diagnose_kafka_cluster) — Comprehensive cluster health check
- [`diagnose_kafka_connectivity`](tools.md#diagnose_kafka_connectivity) — Connectivity troubleshooting
- [`diagnose_kafka_metrics`](tools.md#diagnose_kafka_metrics) — Metrics analysis and anomaly detection
- [`diagnose_operator_metrics`](tools.md#diagnose_operator_metrics) — Operator performance analysis

### Advanced Log Collection
Powerful log collection with:
- **Error analysis** — automatic detection and categorization of errors
- **Advanced filtering** — by log level, keywords, time ranges
- **Multiple providers** — Kubernetes API (default) or Loki for centralized logs
- **Progress tracking** — real-time progress updates for long-running collections

### Metrics Analysis
Comprehensive metrics support:
- **Category-based queries** — replication, throughput, performance, resources
- **Interpretation guides** — thresholds and diagnostic recommendations
- **Multiple providers** — pod scraping (default) or Prometheus for centralized metrics
- **Time range queries** — flexible time windows with ISO 8601 or relative formats

### Resource Monitoring
Real-time subscriptions to Kubernetes resources:
- Watches Kafka, KafkaNodePool, KafkaTopic CRs and operator Deployments
- Sends `notifications/resources/updated` when state changes
- Enables reactive agents that detect and investigate issues automatically
- Can be disabled for testing via [`mcp.resource-watches.enabled`](configuration.md#resource-watch-configuration)

### Security Guardrails
Built-in security features:
- **Log redaction** — automatic removal of sensitive patterns (tokens, passwords, keys)
- **Response size limits** — prevents excessive responses
- **Rate limiting** — per-category request throttling
- **Custom patterns** — add your own redaction rules

### Integration Ready
Seamless integration with observability platforms:
- **Loki** for centralized log collection with LogQL queries
- **Prometheus** for metrics with PromQL queries
- **OpenShift Logging** support with configurable label mapping
- **TLS and authentication** support for secure connections

## Documentation

- **[Installation](installation.md)** — Deploy locally or to Kubernetes
- **[Configuration](configuration.md)** — Environment variables and integration setup
- **[Tools Reference](tools.md)** — Complete tool catalog
- **[Usage Examples](usage-examples.md)** — Practical workflows
- **[Troubleshooting](troubleshooting.md)** — Common issues and solutions

## Quick Links

- [Prerequisites](#prerequisites)
- [5-Minute Quick Start](installation.md#quick-start)
- [Available Tools](tools.md)
- [Prompt Templates](tools.md#prompt-templates)
- [Resource Templates](tools.md#resource-templates)

## Prerequisites

### Required

- **Kubernetes cluster** with kubectl access
- **Strimzi operator** deployed
- **Java 21+** and **Maven 3.8+** (for local development)
- **AI assistant** that supports MCP (Claude Desktop, Claude Code, etc.)

### Optional

- **Loki** for centralized log collection
- **Prometheus** for metrics queries

## Architecture

The Strimzi MCP Server is built on:
- **Quarkus** framework for cloud-native Java
- **Fabric8 Kubernetes Client** for cluster interaction
- **Common module** for shared utilities
- **Pluggable providers** for logs (Loki) and metrics (Prometheus)

For detailed architecture information, see the [Architecture Guide](../../architecture.md).

## License

[Apache License 2.0](../../../LICENSE)