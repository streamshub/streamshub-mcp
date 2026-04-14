+++
title = 'Strimzi MCP Module'
weight = 4
+++

The `strimzi-mcp` module provides MCP server implementation for Strimzi-managed Kafka clusters on Kubernetes.

## Overview

**Artifact**: `io.streamshub:strimzi-mcp`

**Purpose**: Expose Strimzi Kafka resources and operations through MCP protocol, enabling AI assistants to manage and troubleshoot Kafka clusters.

## Module Structure

```
io.streamshub.mcp.strimzi.
├── tool/              → MCP tool definitions (thin wrappers, no logic)
│                        KafkaTools, KafkaTopicTools, KafkaNodePoolTools,
│                        StrimziOperatorTools, StrimziEventsTools,
│                        DiagnosticTools (composite diagnostic tools)
├── service/           → Business logic (KafkaService, KafkaTopicService, 
│                        KafkaNodePoolService, StrimziOperatorService,
│                        StrimziEventsService, CompletionService)
│                        Diagnostic orchestrators: KafkaClusterDiagnosticService,
│                        KafkaConnectivityDiagnosticService, 
│                        KafkaMetricsDiagnosticService,
│                        OperatorMetricsDiagnosticService
├── dto/               → Strimzi response records and diagnostic reports
├── prompt/            → MCP prompt templates (DiagnoseClusterIssuePrompt,
│                        TroubleshootConnectivityPrompt,
│                        AnalyzeKafkaMetricsPrompt,
│                        AnalyzeStrimziOperatorMetricsPrompt)
├── resource/          → ResourceSubscriptionManager (Kubernetes watches)
├── resource/template/ → MCP resource templates and completions
├── config/            → StrimziConstants (labels, resource URIs),
│                        StrimziToolsPrompts
│   └── metrics/       → KafkaMetricCategories, 
│                        KafkaExporterMetricCategories,
│                        StrimziOperatorMetricCategories
└── util/              → NamespaceElicitationHelper, MetricNameResolver,
                         TimeRangeValidator
```

## Key Components

### Tools Layer

MCP tool definitions that expose functionality to AI assistants. Tools are thin wrappers that delegate to domain services.

**Pattern**:
```java
@Singleton
@WrapBusinessError(Exception.class)
public class KafkaTools {
    @Inject
    KafkaService kafkaService;
    
    @Tool(name = "list_kafka_clusters", description = "...")
    public List<KafkaClusterResponse> listClusters(...) {
        return kafkaService.listClusters(...);
    }
}
```

**Tool Categories**:
- `KafkaTools` — Cluster management
- `KafkaTopicTools` — Topic operations
- `KafkaNodePoolTools` — Node pool management
- `StrimziOperatorTools` — Operator monitoring
- `StrimziEventsTools` — Event collection
- `DiagnosticTools` — Composite diagnostic workflows

### Service Layer

Domain services containing all business logic. Services throw `ToolCallException` for errors.

**Pattern**:
```java
@ApplicationScoped
public class KafkaService {
    @Inject
    KubernetesResourceService k8sService;
    
    public List<KafkaClusterResponse> listClusters(String namespace) {
        // Business logic
    }
}
```

**Service Categories**:
- Domain services: `KafkaService`, `KafkaTopicService`, etc.
- Diagnostic services: `KafkaClusterDiagnosticService`, etc.
- Completion service: `CompletionService`

### Prompt Templates

Structured workflows that guide AI assistants through diagnostic processes.

**Available Prompts**:
- `diagnose-cluster-issue` — Comprehensive cluster diagnosis
- `troubleshoot-connectivity` — Connectivity troubleshooting
- `analyze-kafka-metrics` — Metrics analysis
- `analyze-strimzi-operator-metrics` — Operator metrics analysis

### Resource Templates

Expose live Kubernetes state as structured JSON for immediate context.

**Available Resources**:
- `kafka-cluster-status` — Cluster status and conditions
- `kafka-cluster-topology` — Node pools and roles
- `kafka-nodepool-status` — Node pool details
- `kafka-topic-status` — Topic configuration
- `strimzi-operator-status` — Operator health

### Resource Subscriptions

`ResourceSubscriptionManager` watches Kubernetes resources and sends MCP notifications when state changes.

**Watched Resources**:
- Kafka CRs
- KafkaNodePool CRs
- KafkaTopic CRs
- Strimzi operator Deployments

## Configuration

```properties
# Log collection
mcp.log.tail-lines=200

# Resource watches (can be disabled for testing)
mcp.resource-watches.enabled=true

# Sampling (LLM-guided triage)
mcp.sampling.triage-max-tokens=4000
mcp.sampling.analysis-max-tokens=8000

# Metrics provider selection
mcp.metrics.provider=prometheus
```

## Dependencies

### Required
- `streamshub-mcp-common` — Shared utilities
- `io.strimzi:api` — Strimzi API models
- `io.quarkus:quarkus-kubernetes-client` — Kubernetes access
- `io.quarkiverse.mcp:quarkus-mcp-server-core` — MCP protocol

### Optional
- `streamshub-loki-log-provider` — Centralized logging
- `streamshub-metrics-prometheus` — Metrics queries

## Testing

Tests are located in `strimzi-mcp/src/test/java/`:
- Service tests: `KafkaServiceTest`, `KafkaTopicServiceTest`, etc.
- Diagnostic tests: `KafkaClusterDiagnosticServiceTest`, etc.
- Tool tests: `KafkaToolsTest`, `McpDiscoveryTest`, etc.

Run tests:
```bash
cd strimzi-mcp
mvn test
```

## Adding New Tools

See [Contributing Guide](../contributing.md#adding-new-features) for step-by-step instructions.

## Related Documentation

- [Architecture](../architecture.md) — Overall system design
- [Building](../building.md) — Build and run locally
- [Testing](../testing.md) — Testing guide
- [User Guide](../../user/mcp-servers/strimzi-mcp/) — User-facing documentation