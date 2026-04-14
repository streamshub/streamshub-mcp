+++
title = 'System Tests Module'
weight = 5
+++

The `systemtest` module provides end-to-end testing for StreamsHub MCP servers against real Kubernetes clusters.

## Overview

**Location**: `systemtest/`

**Purpose**: Verify complete workflows by deploying real resources to a Kubernetes cluster and testing MCP server functionality end-to-end.

## Test Framework

System tests use:
- **JUnit 6** — Test framework
- **kubetest4j** — Kubernetes resource management and lifecycle
- **quarkus-mcp-server-test** — MCP client for testing MCP endpoints
- **Allure** — Test reporting and documentation
- **Log4j2** — Logging framework

## Running System Tests

System tests are **skipped by default**. Enable with the `systemtest` Maven profile:

```bash
# Run all system tests
mvn verify -Psystemtest

# Run with custom MCP image
mvn verify -Psystemtest -DMCP_IMAGE=quay.io/myorg/strimzi-mcp:dev

# Skip Strimzi installation (use existing cluster)
mvn verify -Psystemtest -DSKIP_STRIMZI_INSTALL=true
```

## Configuration

System tests are configured via environment variables or system properties:

| Variable | Description | Default |
|----------|-------------|---------|
| `MCP_IMAGE` | MCP server container image | `quay.io/streamshub/strimzi-mcp:latest` |
| `MCP_URL` | Direct MCP URL (skips connectivity setup) | `null` |
| `MCP_CONNECTIVITY` | Connectivity strategy: `NODEPORT`, `INGRESS`, or `ROUTE` | Auto-detected |
| `MCP_INGRESS_PORT` | Localhost port for Ingress access | `9090` |
| `MCP_INGRESS_HOST` | Hostname for Ingress rule | `""` (no host constraint) |
| `SKIP_STRIMZI_INSTALL` | Skip Strimzi operator and Kafka deployment | `false` |
| `KAFKA_CLUSTER_NAME` | Kafka cluster name to use in tests | `mcp-cluster` |
| `KAFKA_NAMESPACE` | Kafka namespace | `strimzi-kafka` |

## Test Structure

```
systemtest/
├── src/main/java/
│   └── io/streamshub/mcp/systemtest/
│       ├── Constants.java                    # Test constants
│       ├── Environment.java                  # Environment variable handling
│       ├── clients/
│       │   └── McpClientFactory.java         # MCP client factory
│       ├── enums/
│       │   ├── ConditionStatus.java          # CR condition status enum
│       │   └── CustomResourceStatus.java     # CR status enum
│       ├── resources/
│       │   ├── ResourceConditions.java       # Wait conditions
│       │   └── strimzi/
│       │       ├── KafkaType.java            # Kafka CR type
│       │       ├── KafkaNodePoolType.java    # KafkaNodePool CR type
│       │       └── KafkaTopicType.java       # KafkaTopic CR type
│       ├── setup/
│       │   ├── mcp/
│       │   │   ├── ConnectivitySetup.java    # MCP connectivity setup
│       │   │   └── McpServerSetup.java       # MCP server deployment
│       │   └── strimzi/
│       │       └── StrimziSetup.java         # Strimzi operator setup
│       └── templates/
│           └── strimzi/
│               ├── KafkaTemplates.java       # Kafka CR builders
│               ├── KafkaNodePoolTemplates.java # KafkaNodePool CR builders
│               └── KafkaTopicTemplates.java  # KafkaTopic CR builders
├── src/test/java/
│   └── io/streamshub/mcp/systemtest/
│       ├── AbstractST.java                   # Base test class
│       ├── KafkaClusterToolsST.java          # Kafka cluster tool tests
│       └── StrimziOperatorToolsST.java       # Strimzi operator tool tests
└── src/test/resources/
    └── log4j2.properties                     # Logging configuration
```

## Test Naming Convention

System test classes follow the `*ST.java` naming pattern (e.g., `KafkaClusterST.java`).

## Base Test Class

All system tests extend `AbstractST` which:
- Registers kubetest4j resource types
- Provides common setup/teardown
- Manages test lifecycle

```java
@KubernetesTest(cleanup = CleanupStrategy.AUTOMATIC, collectLogs = true)
@DisplayName("Kafka Cluster MCP Tools")
public class KafkaClusterToolsST extends AbstractST {
    
    @Test
    @DisplayName("List Kafka clusters returns expected cluster")
    void testListClusters() {
        // Test implementation
    }
}
```

## Test Lifecycle

1. **Setup** (once per test class):
   - Deploy Strimzi operator (if not skipped)
   - Deploy Kafka cluster (if not skipped)
   - Deploy MCP server
   - Wait for resources to be ready

2. **Test Execution**:
   - Call MCP endpoints
   - Verify responses
   - Check Kubernetes state

3. **Teardown** (once per test class):
   - Clean up test resources
   - kubetest4j handles resource deletion

## Writing System Tests

### Example Test

```java
@KubernetesTest(cleanup = CleanupStrategy.AUTOMATIC, collectLogs = true)
@DisplayName("Kafka Cluster MCP Tools")
public class KafkaClusterToolsST extends AbstractST {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaClusterToolsST.class);
    
    @InjectResourceManager
    private KubeResourceManager resourceManager;
    
    @BeforeAll
    void setup() {
        // Deploy Strimzi operator (if not skipped)
        if (!Environment.SKIP_STRIMZI_INSTALL) {
            StrimziSetup.deployStrimziOperator(resourceManager);
            
            // Deploy Kafka cluster
            resourceManager.createResourceWithWait(
                KafkaTemplates.kafkaEphemeral(
                    Environment.KAFKA_NAMESPACE,
                    Environment.KAFKA_CLUSTER_NAME,
                    3
                ).build()
            );
        }
        
        // Deploy MCP server
        McpServerSetup.deployMcpServer(resourceManager);
        
        // Setup connectivity
        String mcpUrl = ConnectivitySetup.setupConnectivity(resourceManager);
        McpClientFactory.initialize(mcpUrl);
    }
    
    @Test
    @DisplayName("List Kafka clusters returns expected cluster")
    void testListClusters() throws JsonProcessingException {
        // Act
        JsonNode response = McpAssured.given()
            .toolCall("list_kafka_clusters", Map.of())
            .execute();
        
        // Assert
        JsonNode clusters = response.get("content").get(0).get("text");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode clustersJson = mapper.readTree(clusters.asText());
        
        boolean found = false;
        for (JsonNode cluster : clustersJson.get("clusters")) {
            if (Environment.KAFKA_CLUSTER_NAME.equals(cluster.get("name").asText())) {
                found = true;
                break;
            }
        }
        assertTrue(found, "Expected cluster not found");
    }
}
```

### Best Practices

1. **Use kubetest4j for resource management** — Let the framework handle lifecycle
2. **Wait for resources to be ready** — Use kubetest4j wait conditions
3. **Clean up after tests** — kubetest4j handles this automatically
4. **Use meaningful test names** — Follow `@DisplayName` pattern
5. **Test realistic scenarios** — Use actual Kafka clusters, not mocks

## Prerequisites

To run system tests, you need:
- Access to a Kubernetes cluster
- kubectl configured
- Sufficient RBAC permissions to:
  - Create namespaces
  - Deploy operators
  - Create CRDs
  - Deploy workloads

## CI/CD Integration

System tests can be integrated into CI/CD pipelines:

```yaml
# GitHub Actions example
- name: Run System Tests
  run: mvn verify -Psystemtest
  env:
    KUBECONFIG: ${{ secrets.KUBECONFIG }}
    MCP_IMAGE: ${{ env.IMAGE_TAG }}
```

## Troubleshooting

### Tests Fail to Start

```bash
# Check cluster connectivity
kubectl cluster-info

# Verify Strimzi CRDs
kubectl get crd | grep strimzi

# Check test logs
cat systemtest/target/surefire-reports/*.txt
```

### Resource Cleanup Issues

kubetest4j automatically cleans up resources. If cleanup fails:

```bash
# Manually clean up test namespace
kubectl delete namespace strimzi-kafka --force --grace-period=0
```

### Timeout Issues

Increase timeouts in test configuration:

```properties
# systemtest/src/test/resources/log4j2.properties
rootLogger.level=INFO
```

## Related Documentation

- [Testing Guide](../testing.md) — Unit testing
- [Building](../building.md) — Build and run locally
- [Contributing](../contributing.md) — Contribution guidelines