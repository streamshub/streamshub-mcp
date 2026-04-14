+++
title = 'Modules'
weight = 6
+++

Documentation for StreamsHub MCP modules.

## Shared Modules

These modules provide reusable functionality across MCP servers:

- [Common Module](common.md) — Shared utilities, Kubernetes helpers, MCP framework utilities
- [Loki Log Provider](loki-log-provider.md) — Grafana Loki integration for centralized logging
- [Prometheus Metrics](metrics-prometheus.md) — Prometheus integration for metrics queries

## MCP Server Modules

These modules implement MCP servers:

- [Strimzi MCP](strimzi-mcp.md) — Strimzi Kafka management MCP server

## Testing Modules

- [System Tests](systemtest.md) — End-to-end testing framework

## Module Dependencies

```
strimzi-mcp (MCP Server)
├── common (shared utilities)
├── loki-log-provider (optional)
└── metrics-prometheus (optional)

systemtest (testing)
└── strimzi-mcp (test target)
```

## Adding New Modules

See [Contributing Guide](../contributing.md#adding-a-new-module) for instructions on adding new modules to the mono-repo.