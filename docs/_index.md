+++
title = 'StreamsHub MCP Documentation'
+++

Welcome to the StreamsHub MCP documentation. This mono-repository provides Model Context Protocol (MCP) servers that give AI assistants direct access to Kubernetes-based streaming infrastructure.

## I want to...

### Use StreamsHub MCP

→ [User Documentation](user/) — Installation, configuration, and usage

Start here if you want to:
- Install and configure MCP servers
- Use MCP servers with AI assistants (Claude, etc.)
- Troubleshoot issues
- Learn practical workflows

Quick links:
- [Getting Started](user/getting-started.md) — 5-minute setup
- [Usage Guide](user/usage-guide.md) — How to use with AI assistants
- [MCP Servers](user/mcp-servers/) — Browse available servers

### Develop or Contribute

→ [Developer Documentation](developer/) — Architecture, building, and testing

Start here if you want to:
- Understand the architecture
- Build from source
- Add new features or fix bugs
- Contribute code

Quick links:
- [Architecture](developer/architecture.md) — System design
- [Building](developer/building.md) — Build and run locally
- [Testing](developer/testing.md) — Unit and system tests
- [Contributing](developer/contributing.md) — Contribution guidelines

## Available MCP Servers

### Strimzi MCP Server

Manage and troubleshoot Apache Kafka clusters running on Kubernetes via the Strimzi operator.

Features:
- Cluster status and diagnostics
- Log collection and analysis
- Metrics queries and monitoring
- Connectivity troubleshooting
- Operator health monitoring

Documentation:
- [User Guide](user/mcp-servers/strimzi-mcp/) — Installation and usage
- [Developer Guide](developer/modules/strimzi-mcp/) — Architecture and internals

### Future MCP Servers

Additional MCP servers for other streaming technologies will be added to this repository.

## What is MCP?

The Model Context Protocol (MCP) is an open protocol that enables AI assistants to securely interact with external systems. StreamsHub MCP implements this protocol to give AI assistants access to Kubernetes-based streaming infrastructure.

Key Benefits:
- Natural Language Interface — Ask questions in plain English
- Intelligent Diagnostics — AI-guided troubleshooting workflows
- Real-time Monitoring — Subscribe to resource changes
- Secure Access — Kubernetes RBAC integration

## Project Structure

This is a Maven mono-repository with the following structure:

```
streamshub-mcp/
├── common/                    # Shared utilities and services
├── loki-log-provider/        # Loki integration module
├── metrics-prometheus/       # Prometheus integration module
├── strimzi-mcp/             # Strimzi MCP Server
└── docs/                     # Documentation
    ├── user/                # User documentation
    │   └── mcp-servers/    # Per-server user guides
    └── developer/           # Developer documentation
        └── modules/         # Module documentation
```

## Getting Help

- [GitHub Issues](https://github.com/streamshub/streamshub-mcp/issues) — Report bugs or request features
- [GitHub Discussions](https://github.com/streamshub/streamshub-mcp/discussions) — Ask questions and share ideas
- [Strimzi MCP Troubleshooting](user/mcp-servers/strimzi-mcp/troubleshooting.md) — Common issues and solutions

## License

This project is licensed under the [Apache License 2.0](../LICENSE).