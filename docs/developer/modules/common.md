+++
title = 'Common Module'
weight = 1
+++

The `common` module provides shared libraries and utilities used by all StreamsHub MCP servers. It encapsulates core functionality for Kubernetes integration, log collection, metrics querying, security, and data transfer objects.

## Overview

**Artifact**: `io.streamshub:streamshub-mcp-common`

**Purpose**: Provide reusable components that eliminate code duplication across MCP servers and establish consistent patterns for Kubernetes operations, observability, and security.

## Key Components

### Kubernetes Integration

#### KubernetesResourceService

Generic service for managing Kubernetes resources with type-safe operations.

**Key Methods**:
- `getResource(namespace, name, resourceClass)` — Retrieve a specific resource
- `listResources(namespace, resourceClass)` — List resources in a namespace
- `listResourcesAllNamespaces(resourceClass)` — List resources across all namespaces
- `watchResource(namespace, name, resourceClass, watcher)` — Watch for resource changes

**Usage**:
```java
@Inject
KubernetesResourceService resourceService;

// Get a specific Kafka cluster
Kafka kafka = resourceService.getResource("kafka-ns", "my-cluster", Kafka.class);

// List all Kafka clusters
List<Kafka> clusters = resourceService.listResourcesAllNamespaces(Kafka.class);
```

#### PodsService

Specialized service for pod operations and status checking.

**Key Methods**:
- `getPodsByLabels(namespace, labels)` — Find pods matching label selectors
- `getPodStatus(namespace, podName)` — Get detailed pod status
- `getPodSummaries(namespace, labels)` — Get summarized pod information
- `isPodReady(pod)` — Check if a pod is ready

**Usage**:
```java
@Inject
PodsService podsService;

// Get pods for a Kafka cluster
Map<String, String> labels = Map.of(
    "strimzi.io/cluster", "my-cluster",
    "strimzi.io/kind", "Kafka"
);
List<Pod> pods = podsService.getPodsByLabels("kafka-ns", labels);

// Get pod summaries
List<PodSummaryResponse> summaries = podsService.getPodSummaries("kafka-ns", labels);
```

#### DeploymentService

Service for managing and monitoring Kubernetes Deployments.

**Key Methods**:
- `getDeployment(namespace, name)` — Get a specific deployment
- `listDeployments(namespace, labels)` — List deployments by labels
- `isDeploymentReady(deployment)` — Check deployment readiness
- `getDeploymentStatus(deployment)` — Get detailed status information

#### KubernetesEventsService

Service for collecting and filtering Kubernetes events.

**Key Methods**:
- `getEventsForResource(namespace, resourceName, resourceKind)` — Get events for a specific resource
- `getRecentEvents(namespace, sinceMinutes)` — Get recent events in a namespace
- `filterEventsByType(events, type)` — Filter events by type (Normal, Warning, Error)

### Log Collection

#### LogCollectionService

Unified service for collecting logs from multiple sources with pluggable providers.

**Key Methods**:
- `collectLogs(params)` — Collect logs based on parameters
- `collectLogsFromPods(namespace, podNames, params)` — Collect logs from specific pods
- `collectLogsFromLabels(namespace, labels, params)` — Collect logs from pods matching labels

**Parameters** ([`LogCollectionParams`](../../common/src/main/java/io/streamshub/mcp/common/dto/LogCollectionParams.java)):
- `sinceMinutes` — Time window for log collection
- `tailLines` — Number of recent lines to retrieve
- `containerName` — Specific container to collect logs from
- `logQuery` — Provider-specific query (e.g., LogQL for Loki)

**Usage**:
```java
@Inject
LogCollectionService logService;

// Collect logs from pods
LogCollectionParams params = new LogCollectionParams();
params.setSinceMinutes(30);
params.setTailLines(500);

PodLogsResult result = logService.collectLogsFromLabels(
    "kafka-ns",
    Map.of("strimzi.io/cluster", "my-cluster"),
    params
);
```

#### LogCollectorProvider

Interface for implementing log collection backends.

**Implementations**:
- [`KubernetesLogProvider`](../../common/src/main/java/io/streamshub/mcp/common/service/log/KubernetesLogProvider.java) — Direct pod log collection via Kubernetes API
- [`LokiLogProvider`](loki-log-provider.md) — Centralized log collection via Grafana Loki

**Custom Implementation**:
```java
@ApplicationScoped
@Named("custom")
public class CustomLogProvider implements LogCollectorProvider {
    @Override
    public PodLogsResult collectLogs(String namespace, List<String> podNames, 
                                     LogCollectionParams params) {
        // Custom log collection logic
    }
}
```

### Metrics Collection

#### MetricsQueryService

Unified service for querying metrics from multiple sources.

**Key Methods**:
- `queryMetrics(params)` — Execute a metrics query
- `queryMetricsForPods(namespace, podNames, metricName, params)` — Query metrics for specific pods
- `getAvailableMetrics(namespace, labels)` — List available metrics

**Parameters** ([`MetricsQueryParams`](../../common/src/main/java/io/streamshub/mcp/common/dto/metrics/MetricsQueryParams.java)):
- `query` — Provider-specific query (e.g., PromQL)
- `startTime` — Query start time
- `endTime` — Query end time
- `step` — Time step for range queries

**Usage**:
```java
@Inject
MetricsQueryService metricsService;

// Query Kafka broker metrics
MetricsQueryParams params = new MetricsQueryParams();
params.setQuery("kafka_server_replicamanager_leadercount");
params.setStartTime(Instant.now().minus(1, ChronoUnit.HOURS));
params.setEndTime(Instant.now());

List<MetricTimeSeries> metrics = metricsService.queryMetrics(params);
```

#### MetricsProvider

Interface for implementing metrics collection backends.

**Implementations**:
- [`PodScrapingMetricsProvider`](../../common/src/main/java/io/streamshub/mcp/common/service/metrics/PodScrapingMetricsProvider.java) — Direct pod metrics scraping
- [`PrometheusMetricsProvider`](metrics-prometheus.md) — Centralized metrics via Prometheus

### Security & Guardrails

#### Authentication

[`AbstractAuthFilter`](../../common/src/main/java/io/streamshub/mcp/common/auth/AbstractAuthFilter.java) — Base class for implementing authentication filters for external services.

**Features**:
- Bearer token authentication
- Configurable enable/disable
- Request header injection

**Usage**:
```java
@ApplicationScoped
public class MyServiceAuthFilter extends AbstractAuthFilter {
    @Inject
    MyServiceAuthConfig config;

    @Override
    protected AuthConfig getAuthConfig() {
        return config;
    }
}
```

#### Input Validation

[`InputValidationFilter`](../../common/src/main/java/io/streamshub/mcp/common/guardrail/InputValidationFilter.java) — Validates and sanitizes user inputs.

**Validations**:
- Kubernetes resource name format
- Namespace name format
- Time range bounds
- Query string length limits

#### Response Size Limits

[`ResponseSizeLimitFilter`](../../common/src/main/java/io/streamshub/mcp/common/guardrail/ResponseSizeLimitFilter.java) — Prevents excessive response sizes.

**Features**:
- Configurable size limits
- Automatic truncation with warnings
- Memory protection

#### Log Redaction

[`LogRedactionFilter`](../../common/src/main/java/io/streamshub/mcp/common/guardrail/LogRedactionFilter.java) — Removes sensitive data from logs.

**Redacted Patterns**:
- Passwords and secrets
- API keys and tokens
- Private keys and certificates
- Email addresses
- IP addresses (optional)

**Configuration**:
```properties
# Enable/disable log redaction (enabled by default)
mcp.guardrail.log-redaction.enabled=true

# Configure which patterns to redact
mcp.guardrail.log-redaction.redact-passwords=true
mcp.guardrail.log-redaction.redact-tokens=true
mcp.guardrail.log-redaction.redact-keys=true
mcp.guardrail.log-redaction.redact-emails=true
mcp.guardrail.log-redaction.redact-ip-addresses=false
```

**Usage**:
Log redaction is applied automatically to all log collection operations. Sensitive patterns are replaced with `[REDACTED]` in the output.

#### Query Sanitization

Utilities for preventing injection attacks in query languages:
- [`JsonNodeSanitizer`](../../common/src/main/java/io/streamshub/mcp/common/guardrail/JsonNodeSanitizer.java) — Sanitizes JSON inputs
- Provider-specific sanitizers in integration modules

### Data Transfer Objects

#### Resource Information

- [`ResourceInfo`](../../common/src/main/java/io/streamshub/mcp/common/dto/ResourceInfo.java) — Generic resource status
- [`ConditionInfo`](../../common/src/main/java/io/streamshub/mcp/common/dto/ConditionInfo.java) — Kubernetes condition representation
- [`ReplicasInfo`](../../common/src/main/java/io/streamshub/mcp/common/dto/ReplicasInfo.java) — Replica status information

#### Pod Information

- [`PodSummaryResponse`](../../common/src/main/java/io/streamshub/mcp/common/dto/PodSummaryResponse.java) — Summarized pod information
- [`PodLogsResult`](../../common/src/main/java/io/streamshub/mcp/common/dto/PodLogsResult.java) — Log collection results

#### Metrics

- [`MetricTimeSeries`](../../common/src/main/java/io/streamshub/mcp/common/dto/metrics/MetricTimeSeries.java) — Time series data
- [`MetricSample`](../../common/src/main/java/io/streamshub/mcp/common/dto/metrics/MetricSample.java) — Individual metric sample
- [`TimeSeriesSummary`](../../common/src/main/java/io/streamshub/mcp/common/dto/metrics/TimeSeriesSummary.java) — Statistical summary

#### Events

- [`ResourceEventsResult`](../../common/src/main/java/io/streamshub/mcp/common/dto/ResourceEventsResult.java) — Event collection results

### Utilities

#### Metrics Utilities

- [`PrometheusTextParser`](../../common/src/main/java/io/streamshub/mcp/common/util/metrics/PrometheusTextParser.java) — Parse Prometheus exposition format
- [`MetricLabelFilter`](../../common/src/main/java/io/streamshub/mcp/common/util/metrics/MetricLabelFilter.java) — Filter metrics by labels
- [`TimeSeriesCompressor`](../../common/src/main/java/io/streamshub/mcp/common/util/metrics/TimeSeriesCompressor.java) — Compress time series data

#### Input Utilities

- [`InputUtils`](../../common/src/main/java/io/streamshub/mcp/common/util/InputUtils.java) — Input validation and sanitization helpers

#### MCP Helpers

- [`CompletionHelper`](../../common/src/main/java/io/streamshub/mcp/common/service/CompletionHelper.java) — Dynamic parameter completion
- [`DiagnosticHelper`](../../common/src/main/java/io/streamshub/mcp/common/service/DiagnosticHelper.java) — Diagnostic workflow utilities

## Configuration

### Kubernetes Client

```properties
# Trust self-signed certificates (not recommended for production)
quarkus.kubernetes-client.trust-certs=false

# Custom API server URL (optional)
quarkus.kubernetes-client.master-url=https://kubernetes.default.svc

# Namespace (defaults to current namespace)
quarkus.kubernetes-client.namespace=default
```

### Log Collection

```properties
# Default tail lines for log collection
mcp.log.tail-lines=200
```

## Health Checks

The common module provides a Kubernetes connection health check:

[`KubernetesConnectionReadinessCheck`](../../common/src/main/java/io/streamshub/mcp/common/readiness/KubernetesConnectionReadinessCheck.java) — Verifies Kubernetes API connectivity.

**Endpoint**: `/q/health/ready`

## Testing

The common module includes comprehensive test coverage:

```bash
cd common
mvn test
```

**Test Categories**:
- Unit tests for services and utilities
- Integration tests with Kubernetes mock server
- Security and guardrail tests
- DTO serialization tests

## Dependencies

### Required

- `io.quarkus:quarkus-kubernetes-client` — Kubernetes API access
- `io.quarkiverse.mcp:quarkus-mcp-server-core` — MCP protocol support
- `io.quarkus:quarkus-arc` — CDI container
- `io.quarkus:quarkus-smallrye-health` — Health checks

### Test

- `io.quarkus:quarkus-junit` — Testing framework
- `io.quarkus:quarkus-junit-mockito` — Mocking support

## Usage in MCP Servers

Add the common module as a dependency:

```xml
<dependency>
    <groupId>io.streamshub</groupId>
    <artifactId>streamshub-mcp-common</artifactId>
</dependency>
```

Inject services via CDI:

```java
@ApplicationScoped
public class MyMcpTool {
    @Inject
    KubernetesResourceService resourceService;
    
    @Inject
    LogCollectionService logService;
    
    @Inject
    MetricsQueryService metricsService;
    
    // Use services in tool implementations
}
```

## Best Practices

1. **Use type-safe resource operations** — Leverage `KubernetesResourceService` for consistent resource handling
2. **Apply guardrails** — Use validation and sanitization utilities for all user inputs
3. **Handle errors gracefully** — Catch and log exceptions, return meaningful error messages
4. **Respect resource limits** — Use response size limits and pagination where appropriate
5. **Test thoroughly** — Write unit tests for all service methods
6. **Document DTOs** — Add JavaDoc to all data transfer objects

## Related Documentation

- [Architecture](../architecture.md) — Overall system architecture
- [Loki Log Provider](loki-log-provider.md) — Loki integration module
- [Prometheus Metrics](metrics-prometheus.md) — Prometheus integration module