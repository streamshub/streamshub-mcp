+++
title = 'MCP Server for Strimzi'
weight = 1
+++

The MCP Server for Strimzi provides AI assistants with tools to manage and troubleshoot Apache Kafka clusters deployed with the Strimzi operator on Kubernetes.

> **Warning:**
> This project is in early alpha version and under active development.
> APIs, tool definitions, and configuration may change without notice.

## Overview

The MCP Server for Strimzi exposes Strimzi-managed Kafka resources through the Model Context Protocol.
AI assistants use these tools to check cluster health, collect logs, query metrics, and troubleshoot issues.

With the MCP Server for Strimzi, AI assistants can:

- Monitor Kafka cluster health and status
- Run multi-step diagnostic workflows with intelligent triage
- Collect and analyze logs with filtering by level, keywords, and time range
- Query metrics from Kafka brokers and the Strimzi operator
- Troubleshoot connectivity and performance issues
- Monitor Strimzi operator health and reconciliation activity
- Manage and troubleshoot KafkaConnect clusters and KafkaConnectors
- Manage and troubleshoot KafkaBridge HTTP endpoints
- Monitor KafkaMirrorMaker2 cross-cluster replication
- Check Strimzi Drain Cleaner readiness and webhook configuration
- Track Kubernetes events for cluster resources
- Compare configurations across Kafka clusters
- Assess upgrade readiness with GO/NO-GO verdicts

## Key features

### Automatic namespace discovery

All tools support automatic namespace discovery.
The namespace parameter is optional on every tool.
When no namespace is specified, tools search across the entire cluster to find matching resources.
Note that access to listing namespaces may be limited by RBAC configuration.

### Composite diagnostic tools

Composite diagnostic tools run multi-step workflows in a single tool call.
These tools use LLM-guided triage to focus investigation on problem areas:

- **Sampling** -- Sends intermediate results to the LLM to decide which areas need deeper investigation
- **Elicitation** -- Prompts the user for disambiguation when multiple namespaces contain matching resources
- **Graceful degradation** -- Gathers all data without triage when Sampling or Elicitation is not supported
- **Step failure resilience** -- Individual step failures are recorded but do not abort the workflow

Available diagnostic tools:

- [`diagnose_kafka_cluster`](tools/diagnostics.md#diagnose_kafka_cluster) -- Cluster health check
- [`diagnose_kafka_connectivity`](tools/diagnostics.md#diagnose_kafka_connectivity) -- Connectivity troubleshooting
- [`diagnose_kafka_metrics`](tools/diagnostics.md#diagnose_kafka_metrics) -- Metrics analysis and anomaly detection
- [`diagnose_operator_metrics`](tools/diagnostics.md#diagnose_operator_metrics) -- Operator performance analysis
- [`diagnose_kafka_connect`](tools/diagnostics.md#diagnose_kafka_connect) -- KafkaConnect cluster diagnosis
- [`diagnose_kafka_connector`](tools/diagnostics.md#diagnose_kafka_connector) -- Connector troubleshooting
- [`diagnose_kafka_topic`](tools/diagnostics.md#diagnose_kafka_topic) -- Topic diagnosis with scope detection
- [`assess_upgrade_readiness`](tools/diagnostics.md#assess_upgrade_readiness) -- Pre-upgrade readiness check
- [`diagnose_kafka_mirror_maker`](tools/diagnostics.md#diagnose_kafka_mirror_maker) -- MirrorMaker2 replication diagnosis
- [`compare_kafka_clusters`](tools/diagnostics.md#compare_kafka_clusters) -- Cross-cluster configuration comparison

### Log collection

The server collects logs from Kafka and Strimzi operator pods with the following capabilities:

- Automatic error detection and categorization
- Filtering by log level, keywords, and time range
- Multiple log providers: Kubernetes API (default) or Grafana Loki
- Real-time progress updates for long-running collections

### Metrics analysis

The server supports metrics queries with the following capabilities:

- Category-based queries for Kafka cluster, Kafka Exporter, KafkaConnect, KafkaBridge, and Strimzi operator metrics
- Built-in interpretation guides with thresholds and diagnostic recommendations
- Multiple metrics providers: pod scraping (default) or Prometheus
- Flexible time ranges with ISO 8601 or relative formats

### Resource monitoring

The server watches Kafka, KafkaNodePool, and KafkaTopic custom resources and Strimzi operator Deployments.
When resource state changes, the server sends `notifications/resources/updated` to subscribed MCP clients.

To disable resource watches, see the [`mcp.resource-watches.enabled`](configuration.md#resource-watch-configuration) configuration option.

### Security guardrails

The server includes built-in security features:

- **Log redaction** -- Automatic removal of sensitive patterns such as tokens, passwords, and keys
- **Response size limits** -- Truncation of responses that exceed a configurable size threshold
- **Rate limiting** -- Per-category request throttling to prevent resource exhaustion
- **Custom patterns** -- Support for organization-specific redaction rules

### Observability integration

The server integrates with the following observability platforms:

- **Grafana Loki** -- Centralized log collection with LogQL queries
- **Prometheus** -- Centralized metrics with PromQL queries
- **OpenShift Logging** -- Configurable label mapping for ClusterLogForwarder
- **TLS and authentication** -- Secure connections using certificates or tokens

## Documentation

- **[Installation](installation.md)** -- Deploy locally or to Kubernetes
- **[Configuration](configuration.md)** -- Detailed configuration and integration setup
- **[Tools reference](tools/)** -- Complete tool catalog
- **[Usage examples](usage-examples.md)** -- Practical workflows and patterns
- **[Troubleshooting](troubleshooting.md)** -- Common issues and solutions

## Prerequisites

### Required

- A Kubernetes cluster with `kubectl` access
- The Strimzi operator deployed to your cluster
- Java 21 or later and Maven 3.8 or later (for local development)
- An AI assistant that supports MCP (Claude Desktop, Claude Code, or similar)

### Optional

For enhanced functionality:

- **Grafana Loki** -- For centralized log collection and historical log queries
- **Prometheus** -- For centralized metrics and long-term retention

## Architecture

The MCP Server for Strimzi is built on:

- **Quarkus** -- Cloud-native Java framework
- **Fabric8 Kubernetes Client** -- Kubernetes API access
- **Common module** -- Shared utilities for Kubernetes operations
- **Pluggable providers** -- Swappable implementations for logs and metrics
