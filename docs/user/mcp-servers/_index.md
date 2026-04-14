---
title: "MCP Servers"
weight: 2
---

StreamsHub MCP provides multiple Model Context Protocol (MCP) servers for different aspects of Kubernetes-based streaming infrastructure management.

## Available MCP Servers

### Strimzi MCP Server

The Strimzi MCP Server provides AI assistants with tools to manage and troubleshoot Apache Kafka clusters running on Kubernetes via the Strimzi operator.

**Key Features:**
- Kafka cluster management and monitoring
- Real-time diagnostics and health checks
- Log collection and analysis
- Metrics querying and visualization
- Event tracking and troubleshooting

[Learn more about Strimzi MCP →](strimzi-mcp/)

## Future MCP Servers

Additional MCP servers for other streaming technologies will be added to this mono-repository in the future.

## Common Features

All MCP servers in this repository share:

- **Common Module**: Shared utilities for Kubernetes operations, authentication, and data processing
- **Consistent Architecture**: Similar patterns for tool implementation and resource management
- **Unified Deployment**: Can be deployed together or separately based on your needs
- **Extensible Design**: Easy to add new tools and capabilities

## Getting Started

1. Choose the MCP server(s) you need
2. Follow the installation guide for each server
3. Configure authentication and access
4. Start using the tools with your AI assistant

For general setup instructions, see the [Getting Started Guide](../getting-started.md).