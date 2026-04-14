+++
title = 'Developer Documentation'
weight = 2
+++

Documentation for building, testing, and contributing to StreamsHub MCP.

## Quick Links

- [Architecture](architecture.md) — System design and components
- [Building](building.md) — Build and run locally
- [Testing](testing.md) — Unit and system tests
- [Deployment](deployment.md) — Kubernetes deployment patterns

## Modules

### Shared Modules
- [Common Module](modules/common.md) — Shared utilities and services
- [Loki Log Provider](modules/loki-log-provider.md) — Grafana Loki integration
- [Prometheus Metrics](modules/metrics-prometheus.md) — Prometheus integration

### MCP Server Modules
- [Strimzi MCP](modules/strimzi-mcp.md) — Strimzi MCP server internals

### Testing Modules
- [System Tests](modules/systemtest.md) — End-to-end testing framework

## Technology Stack

- Java 21 — Language and runtime
- Quarkus 3.x — Application framework
- Fabric8 Kubernetes Client — Kubernetes API access
- Quarkus MCP Server — MCP protocol implementation
- Jackson — JSON serialization
- JUnit 6 — Testing framework

## Getting Started

1. [Set up your development environment](building.md)
2. [Build the project](building.md)
3. [Run tests](testing.md)
4. [Understand the architecture](architecture.md)
5. [Make your first contribution](contributing.md)

## Contributing

We welcome contributions! See the [Contributing Guide](../../CONTRIBUTING.md) for:
- Code style and standards
- Development workflow
- Pull request guidelines
- Testing requirements