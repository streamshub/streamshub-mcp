+++
title = 'Prometheus Metrics Provider'
weight = 3
+++

The `metrics-prometheus` module integrates with Prometheus for centralized metrics collection and querying. It implements the [`MetricsProvider`](../../common/src/main/java/io/streamshub/mcp/common/service/metrics/MetricsProvider.java) interface from the common module, enabling MCP servers to query metrics from Prometheus instead of scraping pods directly.

## Overview

**Artifact**: `io.streamshub:streamshub-metrics-prometheus`

**Purpose**: Provide centralized metrics collection capabilities through Prometheus, enabling:
- Historical metrics queries with long-term retention
- Aggregated metrics across multiple pods and clusters
- Advanced queries with PromQL
- Reduced load on individual pods
- Pre-aggregated metrics for better performance

## Key Components

### PrometheusMetricsProvider

Main implementation of the metrics provider interface.

**Location**: [`io.streamshub.mcp.metrics.prometheus.service.PrometheusMetricsProvider`](../../metrics-prometheus/src/main/java/io/streamshub/mcp/metrics/prometheus/service/PrometheusMetricsProvider.java)

**Key Methods**:
- `queryMetrics(params)` — Execute PromQL queries
- `queryInstantMetrics(query, time)` — Query metrics at a specific time
- `queryRangeMetrics(query, start, end, step)` — Query metrics over a time range
- `isAvailable()` — Check if Prometheus is configured and accessible

**Features**:
- Automatic PromQL query construction
- Label-based filtering
- Time range queries with configurable step
- Result aggregation and formatting
- Error handling and fallback

**Usage**:
```java
@Inject
@Named("prometheus")
MetricsProvider prometheusProvider;

// Query instant metrics
MetricsQueryParams params = new MetricsQueryParams();
params.setQuery("kafka_server_replicamanager_leadercount");

List<MetricTimeSeries> metrics = prometheusProvider.queryMetrics(params);

// Query range metrics
params.setStartTime(Instant.now().minus(1, ChronoUnit.HOURS));
params.setEndTime(Instant.now());
params.setStep(Duration.ofMinutes(1));

List<MetricTimeSeries> rangeMetrics = prometheusProvider.queryMetrics(params);

// Query with label filters
params.setQuery("kafka_server_replicamanager_leadercount{namespace=\"kafka-ns\"}");
List<MetricTimeSeries> filtered = prometheusProvider.queryMetrics(params);
```

### PrometheusClient

REST client for Prometheus query API.

**Location**: [`io.streamshub.mcp.metrics.prometheus.service.PrometheusClient`](../../metrics-prometheus/src/main/java/io/streamshub/mcp/metrics/prometheus/service/PrometheusClient.java)

**Endpoints**:
- `query(query, time)` — Execute instant queries
- `queryRange(query, start, end, step)` — Execute range queries
- `series(match, start, end)` — Query available time series
- `labels()` — List available label names
- `labelValues(label)` — List values for a specific label

**Features**:
- Automatic authentication header injection
- JSON response parsing
- Error handling with detailed messages
- Connection pooling
- Timeout configuration

### PromQL Sanitization

Query validation and injection prevention.

**Location**: [`io.streamshub.mcp.metrics.prometheus.util.PromQLSanitizer`](../../metrics-prometheus/src/main/java/io/streamshub/mcp/metrics/prometheus/util/PromQLSanitizer.java)

**Validations**:
- Query syntax validation
- Label injection prevention
- Dangerous function detection
- Query complexity limits
- Time range validation

**Usage**:
```java
@Inject
PromQLSanitizer sanitizer;

// Validate and sanitize user-provided query
String userQuery = "kafka_server_replicamanager_leadercount{namespace=\"kafka-ns\"}";
String sanitized = sanitizer.sanitize(userQuery);

// Throws exception if query is invalid or dangerous
```

### Authentication

Bearer token authentication for secured Prometheus instances.

**Location**: [`io.streamshub.mcp.metrics.prometheus.service.PrometheusAuthFilter`](../../metrics-prometheus/src/main/java/io/streamshub/mcp/metrics/prometheus/service/PrometheusAuthFilter.java)

**Features**:
- Automatic token injection
- Configurable enable/disable
- Extends common module's `AbstractAuthFilter`

## Configuration

### Basic Configuration

```properties
# Prometheus endpoint URL (required)
prometheus.url=http://prometheus.monitoring:9090

# Enable/disable Prometheus integration
prometheus.enabled=true

# Default query step (for range queries)
prometheus.query.step=1m
```

### Authentication

```properties
# Enable authentication
prometheus.auth.enabled=true

# Bearer token (use environment variable for security)
prometheus.auth.token=${PROMETHEUS_TOKEN}
```

### Query Limits

```properties
# Maximum query time range (in hours)
prometheus.query.max-range-hours=24

# Maximum number of time series in response
prometheus.query.max-series=1000

# Query timeout (in seconds)
prometheus.query.timeout=30
```

### Environment Variables

```bash
# Set via environment
export PROMETHEUS_URL=http://prometheus.monitoring:9090
export PROMETHEUS_AUTH_ENABLED=true
export PROMETHEUS_AUTH_TOKEN=your-token-here
export PROMETHEUS_QUERY_STEP=1m
```

### Kubernetes Deployment

Using ConfigMap:
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: mcp-config
data:
  PROMETHEUS_URL: "http://prometheus.monitoring:9090"
  PROMETHEUS_AUTH_ENABLED: "false"
  PROMETHEUS_QUERY_STEP: "1m"
```

Using Secret for token:
```yaml
apiVersion: v1
kind: Secret
metadata:
  name: mcp-secrets
type: Opaque
stringData:
  PROMETHEUS_AUTH_TOKEN: "your-token-here"
```

Reference in Deployment:
```yaml
spec:
  containers:
  - name: mcp-server
    envFrom:
    - configMapRef:
        name: mcp-config
    env:
    - name: PROMETHEUS_AUTH_TOKEN
      valueFrom:
        secretKeyRef:
          name: mcp-secrets
          key: PROMETHEUS_AUTH_TOKEN
```

## PromQL Query Examples

### Basic Queries

```promql
# Current value of a metric
kafka_server_replicamanager_leadercount

# Metric with label filter
kafka_server_replicamanager_leadercount{namespace="kafka-ns"}

# Multiple label filters
kafka_server_replicamanager_leadercount{namespace="kafka-ns", pod=~"my-cluster-kafka-.*"}

# Regex label matching
kafka_server_replicamanager_leadercount{pod=~"kafka-[0-9]+"}
```

### Aggregation

```promql
# Sum across all pods
sum(kafka_server_replicamanager_leadercount)

# Average by namespace
avg by (namespace) (kafka_server_replicamanager_leadercount)

# Maximum value
max(kafka_server_replicamanager_leadercount)

# Count of time series
count(kafka_server_replicamanager_leadercount)
```

### Rate Calculations

```promql
# Rate over 5 minutes
rate(kafka_server_brokertopicmetrics_messagesinpersec_count[5m])

# Increase over 1 hour
increase(kafka_server_brokertopicmetrics_messagesinpersec_count[1h])

# Rate with aggregation
sum(rate(kafka_server_brokertopicmetrics_messagesinpersec_count[5m])) by (topic)
```

### Advanced Queries

```promql
# Percentage calculation
(kafka_server_replicamanager_underreplicatedpartitions / 
 kafka_server_replicamanager_partitioncount) * 100

# Comparison with threshold
kafka_server_replicamanager_underreplicatedpartitions > 0

# Multiple metrics combined
kafka_server_brokertopicmetrics_messagesinpersec_count + 
kafka_server_brokertopicmetrics_byteinpersec_count

# Histogram quantile
histogram_quantile(0.95, 
  rate(kafka_network_requestmetrics_requestqueuetimems_bucket[5m]))
```

## Integration with Common Module

The Prometheus metrics provider integrates seamlessly with the common module's [`MetricsQueryService`](../../common/src/main/java/io/streamshub/mcp/common/service/metrics/MetricsQueryService.java):

```java
@ApplicationScoped
public class MyMcpTool {
    @Inject
    MetricsQueryService metricsService;
    
    public List<MetricTimeSeries> queryMetrics() {
        // MetricsQueryService automatically uses Prometheus if configured
        MetricsQueryParams params = new MetricsQueryParams();
        params.setQuery("kafka_server_replicamanager_leadercount");
        params.setStartTime(Instant.now().minus(1, ChronoUnit.HOURS));
        params.setEndTime(Instant.now());
        params.setStep(Duration.ofMinutes(5));
        
        return metricsService.queryMetrics(params);
    }
}
```

## Provider Selection

The common module's `MetricsQueryService` automatically selects the appropriate provider:

1. **Prometheus** — Used if `prometheus.url` is configured and Prometheus is available
2. **Pod Scraping** — Fallback to direct pod metrics scraping

Check provider availability:
```java
@Inject
@Named("prometheus")
MetricsProvider prometheusProvider;

if (prometheusProvider.isAvailable()) {
    // Prometheus is configured and accessible
}
```

## Response Format

### MetricTimeSeries

```java
public class MetricTimeSeries {
    private String metricName;
    private Map<String, String> labels;
    private List<MetricSample> samples;
    private TimeSeriesSummary summary;
}

public class MetricSample {
    private Instant timestamp;
    private double value;
}

public class TimeSeriesSummary {
    private double min;
    private double max;
    private double avg;
    private double sum;
    private long count;
}
```

### Example Response

```json
{
  "metricName": "kafka_server_replicamanager_leadercount",
  "labels": {
    "namespace": "kafka-ns",
    "pod": "my-cluster-kafka-0",
    "job": "kafka"
  },
  "samples": [
    {"timestamp": "2024-01-15T10:00:00Z", "value": 10.0},
    {"timestamp": "2024-01-15T10:05:00Z", "value": 10.0},
    {"timestamp": "2024-01-15T10:10:00Z", "value": 11.0}
  ],
  "summary": {
    "min": 10.0,
    "max": 11.0,
    "avg": 10.33,
    "sum": 31.0,
    "count": 3
  }
}
```

## Error Handling

The provider handles various error scenarios:

### Prometheus Unavailable

If Prometheus is unavailable, the service falls back to pod scraping:

```java
try {
    return prometheusProvider.queryMetrics(params);
} catch (Exception e) {
    log.warn("Prometheus unavailable, falling back to pod scraping", e);
    return podScrapingProvider.queryMetrics(params);
}
```

### Invalid Queries

Invalid PromQL queries throw descriptive exceptions:

```java
try {
    String query = userInput;  // Potentially malicious
    sanitizer.sanitize(query);
} catch (IllegalArgumentException e) {
    // Query validation failed
    log.error("Invalid PromQL query: {}", e.getMessage());
}
```

### Query Timeouts

Long-running queries are automatically timed out:

```java
if (response.status() == 504) {
    throw new TimeoutException("Prometheus query timed out. Try reducing time range or complexity.");
}
```

### Authentication Failures

Authentication failures are logged and reported:

```java
if (response.status() == 401) {
    throw new SecurityException("Prometheus authentication failed. Check PROMETHEUS_AUTH_TOKEN.");
}
```

## Testing

### Unit Tests

```bash
cd metrics-prometheus
mvn test
```

**Test Coverage**:
- PromQL query construction
- Query sanitization
- Authentication filter
- Response parsing
- Error handling
- Time series aggregation

### Integration Testing

Test with a real Prometheus instance:

```bash
# Deploy Prometheus to your cluster
kubectl apply -k ../dev/manifests/prometheus/

# Configure the provider
export PROMETHEUS_URL=http://prometheus.monitoring:9090

# Run integration tests
mvn verify -Pintegration
```

### Manual Testing

```bash
# Start MCP server with Prometheus configured
export PROMETHEUS_URL=http://localhost:9090
cd strimzi-mcp
mvn quarkus:dev

# Test metrics query via MCP
npx @modelcontextprotocol/inspector http://localhost:8080/mcp
```

## Performance Considerations

### Query Optimization

- **Use specific time ranges** — Narrow time windows improve query performance
- **Add label filters** — Filter by namespace, pod, job to reduce data scanned
- **Choose appropriate step** — Larger steps reduce data points returned
- **Avoid high-cardinality labels** — Don't group by labels with many unique values
- **Use recording rules** — Pre-compute complex queries in Prometheus

### Caching

Consider implementing caching for frequently accessed metrics:

```java
@ApplicationScoped
public class CachedPrometheusProvider {
    @Inject
    @Named("prometheus")
    MetricsProvider prometheusProvider;
    
    private final Cache<String, List<MetricTimeSeries>> cache = 
        Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .maximumSize(100)
            .build();
    
    public List<MetricTimeSeries> queryMetrics(MetricsQueryParams params) {
        String cacheKey = buildCacheKey(params);
        return cache.get(cacheKey, k -> prometheusProvider.queryMetrics(params));
    }
}
```

### Recording Rules

Define recording rules in Prometheus for complex queries:

```yaml
groups:
- name: kafka_aggregations
  interval: 30s
  rules:
  - record: kafka:cluster:messages_in_rate
    expr: sum(rate(kafka_server_brokertopicmetrics_messagesinpersec_count[5m])) by (namespace, cluster)
  
  - record: kafka:cluster:bytes_in_rate
    expr: sum(rate(kafka_server_brokertopicmetrics_bytesinpersec_count[5m])) by (namespace, cluster)
```

Then query the pre-computed metrics:

```promql
kafka:cluster:messages_in_rate{namespace="kafka-ns"}
```

## Security

### Authentication

Always use authentication for production Prometheus instances:

```properties
prometheus.auth.enabled=true
prometheus.auth.token=${PROMETHEUS_TOKEN}
```

Store tokens in Kubernetes Secrets, not ConfigMaps.

### Query Sanitization

All user-provided PromQL queries are sanitized to prevent:
- Label injection attacks
- Resource exhaustion via complex queries
- Access to unauthorized metrics
- Dangerous functions (e.g., `predict_linear` with large ranges)

### Network Security

Restrict network access to Prometheus:

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: mcp-to-prometheus
spec:
  podSelector:
    matchLabels:
      app: mcp-server
  policyTypes:
  - Egress
  egress:
  - to:
    - namespaceSelector:
        matchLabels:
          name: monitoring
    - podSelector:
        matchLabels:
          app: prometheus
    ports:
    - protocol: TCP
      port: 9090
```

## Troubleshooting

### Connection Issues

```bash
# Test Prometheus connectivity
curl http://prometheus.monitoring:9090/-/ready

# Check DNS resolution
nslookup prometheus.monitoring

# Test from MCP server pod
kubectl exec -it <mcp-pod> -- curl http://prometheus.monitoring:9090/-/ready
```

### Authentication Issues

```bash
# Test with token
curl -H "Authorization: Bearer $PROMETHEUS_TOKEN" \
  "http://prometheus.monitoring:9090/api/v1/query?query=up"

# Check token in pod
kubectl exec -it <mcp-pod> -- env | grep PROMETHEUS_AUTH_TOKEN
```

### Query Issues

Enable debug logging:

```properties
quarkus.log.category."io.streamshub.mcp.metrics.prometheus".level=DEBUG
```

Check logs for query details:

```bash
kubectl logs <mcp-pod> | grep PromQL
```

### No Data Returned

Common causes:
- Metric name misspelled
- Label filters too restrictive
- Time range outside data retention
- Prometheus not scraping targets

Check Prometheus targets:
```bash
curl http://prometheus.monitoring:9090/api/v1/targets
```

## Dependencies

### Required

- `io.streamshub:streamshub-mcp-common` — Common module
- `io.quarkus:quarkus-rest-client-jackson` — REST client
- `io.quarkus:quarkus-arc` — CDI container

### Test

- `io.quarkus:quarkus-junit` — Testing framework
- `io.quarkus:quarkus-junit-mockito` — Mocking support

## Related Documentation

- [Common Module](common.md) — Shared libraries and interfaces
- [Architecture](../architecture.md) — System architecture
- [Deployment Guide](../deployment.md) — Kubernetes deployment
- [Prometheus Documentation](https://prometheus.io/docs/)
- [PromQL Documentation](https://prometheus.io/docs/prometheus/latest/querying/basics/)