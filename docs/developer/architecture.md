+++
title = 'Architecture'
weight = 3
+++

## Mono-Repository Structure

StreamsHub MCP is organized as a Maven multi-module project, allowing independent development and deployment of MCP servers while sharing common functionality.

```
streamshub-mcp/
├── common/                    # Shared libraries and utilities
├── loki-log-provider/         # Loki log integration module
├── metrics-prometheus/        # Prometheus metrics integration module
├── strimzi-mcp/              # Strimzi Kafka MCP server
├── dev/                      # Development manifests and scripts
│   ├── manifests/            # Kubernetes manifests for dependencies
│   └── scripts/              # Setup and teardown scripts
├── pom.xml                   # Parent POM
└── docs/                     # Documentation
```

## Module Dependencies

The architecture follows a layered approach where MCP servers depend on shared modules:

```
┌─────────────────────────────────────────┐
│         MCP Servers (Deployable)        │
│  ┌──────────────┐  ┌──────────────┐    │
│  │ Strimzi MCP  │  │  Future MCP  │    │
│  └──────┬───────┘  └──────┬───────┘    │
└─────────┼──────────────────┼────────────┘
          │                  │
          └────────┬─────────┘
                   │
┌──────────────────┴─────────────────────┐
│        Integration Modules              │
│  ┌──────────────┐  ┌──────────────┐   │
│  │ Loki Logs    │  │ Prometheus   │   │
│  └──────┬───────┘  └──────┬───────┘   │
└─────────┼──────────────────┼───────────┘
          │                  │
          └────────┬─────────┘
                   │
┌──────────────────┴─────────────────────┐
│           Common Module                 │
│  • Kubernetes client utilities          │
│  • MCP protocol helpers                 │
│  • Authentication & authorization       │
│  • Guardrails & security                │
│  • Shared DTOs and services             │
└─────────────────────────────────────────┘
```

## Common Module

The [`common`](modules/common.md) module provides shared functionality used by all MCP servers:

### Core Services

- **Kubernetes Integration** — Fabric8 client wrappers for resource management
- **Pod Management** — Pod discovery, status checking, and log retrieval
- **Event Handling** — Kubernetes event collection and filtering
- **Deployment Services** — Operator and workload status monitoring

### Log Collection

- **LogCollectionService** — Unified interface for log retrieval
- **LogCollectorProvider** — Plugin system for different log backends
- **KubernetesLogProvider** — Direct pod log collection via Kubernetes API
- **LogFilterUtils** — Time-based and pattern-based log filtering

### Metrics Collection

- **MetricsQueryService** — Unified interface for metrics queries
- **MetricsProvider** — Plugin system for different metrics backends
- **PodScrapingMetricsProvider** — Direct pod metrics scraping
- **PrometheusTextParser** — Prometheus exposition format parser

### Security & Guardrails

- **Authentication** — Token-based auth for external services (Loki, Prometheus)
- **Input Validation** — Request parameter sanitization and validation
- **Response Size Limits** — Protection against excessive data responses
- **Log Redaction** — Automatic removal of sensitive data from logs
- **Query Sanitization** — Prevention of injection attacks in PromQL/LogQL

### Data Transfer Objects

Standardized DTOs for consistent data representation across modules:
- Resource status and conditions
- Pod summaries and replicas
- Log collection results
- Metrics time series
- Event information

## Integration Modules

### Loki Log Provider

The [`loki-log-provider`](modules/loki-log-provider.md) module integrates with Grafana Loki for centralized log collection:

- **LokiClient** — REST client for Loki query API
- **LokiLogProvider** — Implementation of `LogCollectorProvider` interface
- **LogQL Sanitization** — Query validation and injection prevention
- **Authentication** — Bearer token support for secured Loki instances

### Prometheus Metrics Provider

The [`metrics-prometheus`](modules/metrics-prometheus.md) module integrates with Prometheus for metrics collection:

- **PrometheusClient** — REST client for Prometheus query API
- **PrometheusMetricsProvider** — Implementation of `MetricsProvider` interface
- **PromQL Sanitization** — Query validation and injection prevention
- **Authentication** — Bearer token support for secured Prometheus instances

## MCP Server Architecture
Each MCP server follows a consistent architecture. For a complete implementation example, see the [Strimzi MCP Server documentation](mcp-servers/strimzi-mcp/).


### MCP Protocol Layer

- **Tools** — Exposed as MCP tool definitions with JSON schemas
- **Resources** — Kubernetes resources exposed as MCP resource templates
- **Prompts** — Diagnostic workflows as structured prompt templates
- **Subscriptions** — Kubernetes watches for real-time notifications

### Service Layer

- Domain-specific services that orchestrate common module functionality
- Business logic for specific Kubernetes resources (e.g., Kafka, KafkaTopic)
- Diagnostic workflows combining multiple data sources

### Integration Layer

- Uses common module services for Kubernetes access
- Plugs in log and metrics providers based on configuration
- Handles authentication and authorization

## Configuration

Each module can be configured via environment variables or `application.properties`:

### Common Configuration

```properties
# Kubernetes connection (auto-detected from kubeconfig)
quarkus.kubernetes-client.trust-certs=false

# MCP server settings
mcp.log.tail-lines=200
```

### Loki Configuration

```properties
# Loki endpoint
loki.url=http://loki:3100
loki.auth.enabled=false
loki.auth.token=${LOKI_TOKEN}
```

### Prometheus Configuration

```properties
# Prometheus endpoint
prometheus.url=http://prometheus:9090
prometheus.auth.enabled=false
prometheus.auth.token=${PROMETHEUS_TOKEN}
```

## Deployment Models

### Standalone Development

Run MCP servers locally with `mvn quarkus:dev`, connecting to a remote Kubernetes cluster via kubeconfig.

### Container Deployment

Build and deploy MCP servers as containers in Kubernetes, using ServiceAccounts for cluster access.

### Sidecar Pattern

Deploy MCP servers as sidecars alongside applications for namespace-scoped access.

## Security Model

### RBAC

MCP servers use Kubernetes RBAC with two permission tiers:

**ClusterRole (default, non-sensitive)**:
- Read-only access to CRDs, Deployments, Pods, Services
- Log retrieval from pods
- Event collection

**Role (opt-in per namespace, sensitive)**:
- Secret metadata access (for certificate checking)
- Pod proxy access (for direct metrics scraping)

### Authentication

- **Kubernetes API** — ServiceAccount tokens or kubeconfig
- **Loki/Prometheus** — Bearer token authentication
- **MCP Protocol** — No authentication (relies on network security)

### Input Validation

All user inputs are validated and sanitized:
- Namespace and resource names checked against Kubernetes naming rules
- Time ranges validated for reasonable bounds
- Query strings sanitized to prevent injection attacks
- Response sizes limited to prevent memory exhaustion

## Extensibility

### Adding New MCP Servers

1. Create a new module in the mono-repo
2. Add dependency on `common` module
3. Optionally depend on `loki-log-provider` and `metrics-prometheus`
4. Implement MCP tools, resources, and prompts
5. Add module to parent `pom.xml`

### Adding New Log Providers

1. Implement `LogCollectorProvider` interface in a new module
2. Register via CDI with appropriate qualifiers
3. Configure via `application.properties`

### Adding New Metrics Providers

1. Implement `MetricsProvider` interface in a new module
2. Register via CDI with appropriate qualifiers
3. Configure via `application.properties`

## Technology Stack

- **Java 21** — Language and runtime
- **Quarkus 3.34+** — Application framework
- **Fabric8 Kubernetes Client** — Kubernetes API access
- **Quarkus MCP Server** — MCP protocol implementation
- **Jackson** — JSON serialization
- **RESTEasy Reactive** — HTTP client for external APIs
- **JUnit 6** — Testing framework
- **Mockito** — Mocking framework