+++
title = 'Loki Log Provider'
weight = 2
+++

The `loki-log-provider` module integrates with Grafana Loki for centralized log collection and querying. It implements the [`LogCollectorProvider`](../../common/src/main/java/io/streamshub/mcp/common/service/log/LogCollectorProvider.java) interface from the common module, enabling MCP servers to collect logs from Loki instead of directly from Kubernetes pods.

## Overview

**Artifact**: `io.streamshub:streamshub-loki-log-provider`

**Purpose**: Provide centralized log collection capabilities through Grafana Loki, enabling:
- Historical log queries beyond pod retention
- Aggregated log search across multiple pods
- Advanced filtering with LogQL
- Reduced load on Kubernetes API server

## Key Components

### LokiLogProvider

Main implementation of the log collection interface.

**Location**: [`io.streamshub.mcp.loki.LokiLogProvider`](../../loki-log-provider/src/main/java/io/streamshub/mcp/loki/LokiLogProvider.java)

**Key Methods**:
- `collectLogs(namespace, podNames, params)` — Collect logs for specific pods
- `collectLogsWithQuery(namespace, labels, logQuery, params)` — Execute custom LogQL queries
- `isAvailable()` — Check if Loki is configured and accessible

**Features**:
- Automatic LogQL query construction from pod names
- Label-based log filtering
- Time range queries
- Line limit support
- Error handling and fallback

**Usage**:
```java
@Inject
@Named("loki")
LogCollectorProvider lokiProvider;

// Collect logs from specific pods
LogCollectionParams params = new LogCollectionParams();
params.setSinceMinutes(60);
params.setTailLines(1000);

PodLogsResult result = lokiProvider.collectLogs(
    "kafka-ns",
    List.of("my-cluster-kafka-0", "my-cluster-kafka-1"),
    params
);

// Execute custom LogQL query
String logQuery = "{namespace=\"kafka-ns\", pod=~\"my-cluster-kafka-.*\"} |= \"ERROR\"";
PodLogsResult errorLogs = lokiProvider.collectLogsWithQuery(
    "kafka-ns",
    Map.of("strimzi.io/cluster", "my-cluster"),
    logQuery,
    params
);
```

### LokiClient

REST client for Loki query API.

**Location**: [`io.streamshub.mcp.loki.service.LokiClient`](../../loki-log-provider/src/main/java/io/streamshub/mcp/loki/service/LokiClient.java)

**Endpoints**:
- `queryRange(query, start, end, limit)` — Execute range queries
- `query(query, time, limit)` — Execute instant queries

**Features**:
- Automatic authentication header injection
- JSON response parsing
- Error handling
- Connection pooling

### LogQL Sanitization

Query validation and injection prevention.

**Location**: [`io.streamshub.mcp.loki.util.LogQLSanitizer`](../../loki-log-provider/src/main/java/io/streamshub/mcp/loki/util/LogQLSanitizer.java)

**Validations**:
- Query syntax validation
- Label injection prevention
- Dangerous operator detection
- Query complexity limits

**Usage**:
```java
@Inject
LogQLSanitizer sanitizer;

// Validate and sanitize user-provided query
String userQuery = "{namespace=\"kafka-ns\"} |= \"search term\"";
String sanitized = sanitizer.sanitize(userQuery);

// Throws exception if query is invalid or dangerous
```

### Authentication

Bearer token authentication for secured Loki instances.

**Location**: [`io.streamshub.mcp.loki.service.LokiAuthFilter`](../../loki-log-provider/src/main/java/io/streamshub/mcp/loki/service/LokiAuthFilter.java)

**Features**:
- Automatic token injection
- Configurable enable/disable
- Extends common module's `AbstractAuthFilter`

## Configuration

### Basic Configuration

```properties
# Loki endpoint URL (required)
loki.url=http://loki.monitoring:3100

# Enable/disable Loki integration
loki.enabled=true
```

### Authentication

```properties
# Enable authentication
loki.auth.enabled=true

# Bearer token (use environment variable for security)
loki.auth.token=${LOKI_TOKEN}
```

### Environment Variables

```bash
# Set via environment
export LOKI_URL=http://loki.monitoring:3100
export LOKI_AUTH_ENABLED=true
export LOKI_AUTH_TOKEN=your-token-here
```

### Kubernetes Deployment

Using ConfigMap:
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: mcp-config
data:
  LOKI_URL: "http://loki.monitoring:3100"
  LOKI_AUTH_ENABLED: "false"
```

Using Secret for token:
```yaml
apiVersion: v1
kind: Secret
metadata:
  name: mcp-secrets
type: Opaque
stringData:
  LOKI_AUTH_TOKEN: "your-token-here"
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
    - name: LOKI_AUTH_TOKEN
      valueFrom:
        secretKeyRef:
          name: mcp-secrets
          key: LOKI_AUTH_TOKEN
```

## LogQL Query Examples

### Basic Queries

```logql
# All logs from a namespace
{namespace="kafka-ns"}

# Logs from specific pods
{namespace="kafka-ns", pod=~"my-cluster-kafka-.*"}

# Logs from a specific container
{namespace="kafka-ns", container="kafka"}
```

### Filtering

```logql
# Filter by text
{namespace="kafka-ns"} |= "ERROR"

# Exclude text
{namespace="kafka-ns"} != "DEBUG"

# Regular expression filter
{namespace="kafka-ns"} |~ "ERROR|WARN"

# Case-insensitive filter
{namespace="kafka-ns"} |~ "(?i)error"
```

### Advanced Queries

```logql
# Multiple filters
{namespace="kafka-ns", pod=~"my-cluster-kafka-.*"} 
  |= "ERROR" 
  != "connection reset"

# JSON parsing
{namespace="kafka-ns"} 
  | json 
  | level="error"

# Rate calculation
rate({namespace="kafka-ns"} |= "ERROR" [5m])

# Count by label
count_over_time({namespace="kafka-ns"} [1h])
```

## Integration with Common Module

The Loki log provider integrates seamlessly with the common module's [`LogCollectionService`](../../common/src/main/java/io/streamshub/mcp/common/service/log/LogCollectionService.java):

```java
@ApplicationScoped
public class MyMcpTool {
    @Inject
    LogCollectionService logService;
    
    public PodLogsResult collectLogs() {
        // LogCollectionService automatically uses Loki if configured
        LogCollectionParams params = new LogCollectionParams();
        params.setSinceMinutes(30);
        params.setLogQuery("{namespace=\"kafka-ns\"} |= \"ERROR\"");
        
        return logService.collectLogsFromLabels(
            "kafka-ns",
            Map.of("strimzi.io/cluster", "my-cluster"),
            params
        );
    }
}
```

## Provider Selection

The common module's `LogCollectionService` automatically selects the appropriate provider:

1. **Loki** — Used if `loki.url` is configured and Loki is available
2. **Kubernetes** — Fallback to direct pod log collection

Check provider availability:
```java
@Inject
@Named("loki")
LogCollectorProvider lokiProvider;

if (lokiProvider.isAvailable()) {
    // Loki is configured and accessible
}
```

## Response Format

### PodLogsResult

```java
public class PodLogsResult {
    private Map<String, String> podLogs;  // Pod name -> log content
    private String source;                 // "loki" or "kubernetes"
    private boolean truncated;             // Whether logs were truncated
    private String query;                  // LogQL query used (if applicable)
}
```

### Example Response

```json
{
  "podLogs": {
    "my-cluster-kafka-0": "2024-01-15 10:30:00 ERROR ...\n2024-01-15 10:30:05 ERROR ...",
    "my-cluster-kafka-1": "2024-01-15 10:30:02 ERROR ...\n2024-01-15 10:30:07 ERROR ..."
  },
  "source": "loki",
  "truncated": false,
  "query": "{namespace=\"kafka-ns\", pod=~\"my-cluster-kafka-.*\"}"
}
```

## Error Handling

The provider handles various error scenarios:

### Loki Unavailable

If Loki is unavailable, the service falls back to Kubernetes log collection:

```java
try {
    return lokiProvider.collectLogs(namespace, podNames, params);
} catch (Exception e) {
    log.warn("Loki unavailable, falling back to Kubernetes", e);
    return kubernetesProvider.collectLogs(namespace, podNames, params);
}
```

### Invalid Queries

Invalid LogQL queries throw descriptive exceptions:

```java
try {
    String query = userInput;  // Potentially malicious
    sanitizer.sanitize(query);
} catch (IllegalArgumentException e) {
    // Query validation failed
    log.error("Invalid LogQL query: {}", e.getMessage());
}
```

### Authentication Failures

Authentication failures are logged and reported:

```java
if (response.status() == 401) {
    throw new SecurityException("Loki authentication failed. Check LOKI_AUTH_TOKEN.");
}
```

## Testing

### Unit Tests

```bash
cd loki-log-provider
mvn test
```

**Test Coverage**:
- LogQL query construction
- Query sanitization
- Authentication filter
- Response parsing
- Error handling

### Integration Testing

Test with a real Loki instance:

```bash
# Deploy Loki to your cluster
kubectl apply -k ../dev/manifests/loki/

# Configure the provider
export LOKI_URL=http://loki.monitoring:3100

# Run integration tests
mvn verify -Pintegration
```

### Manual Testing

```bash
# Start MCP server with Loki configured
export LOKI_URL=http://localhost:3100
cd strimzi-mcp
mvn quarkus:dev

# Test log collection via MCP
npx @modelcontextprotocol/inspector http://localhost:8080/mcp
```

## Performance Considerations

### Query Optimization

- **Use specific time ranges** — Narrow time windows improve query performance
- **Add label filters** — Filter by namespace, pod, container to reduce data scanned
- **Limit results** — Use `tailLines` parameter to limit response size
- **Avoid regex when possible** — Exact matches are faster than regex patterns

### Caching

Consider implementing caching for frequently accessed logs:

```java
@ApplicationScoped
public class CachedLokiProvider {
    @Inject
    @Named("loki")
    LogCollectorProvider lokiProvider;
    
    private final Cache<String, PodLogsResult> cache = 
        Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(100)
            .build();
    
    public PodLogsResult collectLogs(String namespace, List<String> podNames, 
                                     LogCollectionParams params) {
        String cacheKey = buildCacheKey(namespace, podNames, params);
        return cache.get(cacheKey, k -> lokiProvider.collectLogs(namespace, podNames, params));
    }
}
```

## Security

### Authentication

Always use authentication for production Loki instances:

```properties
loki.auth.enabled=true
loki.auth.token=${LOKI_TOKEN}
```

Store tokens in Kubernetes Secrets, not ConfigMaps.

### Query Sanitization

All user-provided LogQL queries are sanitized to prevent:
- Label injection attacks
- Resource exhaustion via complex queries
- Access to unauthorized namespaces

### Network Security

Restrict network access to Loki:

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: mcp-to-loki
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
          app: loki
    ports:
    - protocol: TCP
      port: 3100
```

## Troubleshooting

### Connection Issues

```bash
# Test Loki connectivity
curl http://loki.monitoring:3100/ready

# Check DNS resolution
nslookup loki.monitoring

# Test from MCP server pod
kubectl exec -it <mcp-pod> -- curl http://loki.monitoring:3100/ready
```

### Authentication Issues

```bash
# Test with token
curl -H "Authorization: Bearer $LOKI_TOKEN" \
  http://loki.monitoring:3100/loki/api/v1/query?query={namespace="default"}

# Check token in pod
kubectl exec -it <mcp-pod> -- env | grep LOKI_AUTH_TOKEN
```

### Query Issues

Enable debug logging:

```properties
quarkus.log.category."io.streamshub.mcp.loki".level=DEBUG
```

Check logs for query details:

```bash
kubectl logs <mcp-pod> | grep LogQL
```

## Dependencies

### Required

- `io.streamshub:streamshub-mcp-common` — Common module
- `io.quarkus:quarkus-rest-client-jackson` — REST client

### Test

- `io.quarkus:quarkus-junit` — Testing framework
- `io.quarkus:quarkus-junit-mockito` — Mocking support

## Related Documentation

- [Common Module](common.md) — Shared libraries and interfaces
- [Architecture](../architecture.md) — System architecture
- [Deployment Guide](../deployment.md) — Kubernetes deployment
- [Grafana Loki Documentation](https://grafana.com/docs/loki/latest/)