+++
title = 'Testing'
weight = 3
+++

This guide covers testing StreamsHub MCP servers.

## Test Types

### Unit Tests

Unit tests run during the standard Maven test phase and use Mockito to mock Kubernetes API interactions.

```bash
# Run all unit tests
mvn test

# Run tests for a specific module
cd strimzi-mcp
mvn test

# Run a specific test class
mvn test -Dtest=KafkaServiceTest

# Run tests with coverage
mvn test jacoco:report
```

Unit tests are located in `src/test/java` and follow these conventions:
- Test class names end with `Test` (e.g., `KafkaServiceTest`)
- One test class per service or component
- Use `@DisplayName` for readable test descriptions
- Follow the pattern: `methodName_condition_expectedResult`

### System Tests (E2E)

System tests verify complete workflows by deploying real resources to a Kubernetes cluster. They use [kubetest4j](https://github.com/skodjob/kubetest4j) for resource management.

System tests are **skipped by default**. Enable them with the `systemtest` Maven profile:

```bash
# Run all system tests
mvn verify -Psystemtest

# Run with custom configuration
mvn verify -Psystemtest -DMCP_IMAGE=quay.io/myorg/strimzi-mcp:dev

# Skip Strimzi installation (use existing cluster)
mvn verify -Psystemtest -DSKIP_STRIMZI_INSTALL=true
```

**System Test Configuration:**

| Variable | Description | Default |
|----------|-------------|---------|
| `MCP_IMAGE` | MCP server container image | `quay.io/streamshub/strimzi-mcp:latest` |
| `MCP_URL` | Direct MCP URL (skips connectivity setup) | `null` |
| `SKIP_STRIMZI_INSTALL` | Skip Strimzi operator and Kafka deployment | `false` |
| `KAFKA_CLUSTER_NAME` | Kafka cluster name to use in tests | `mcp-cluster` |
| `KAFKA_NAMESPACE` | Kafka namespace | `strimzi-kafka` |

System tests are located in the `systemtest` module and follow the `*ST.java` naming pattern. All tests extend `AbstractST` which registers kubetest4j resource types.

## Writing Tests

### Unit Test Pattern

Every public method should have a test:

```java
@Test
@DisplayName("methodName_condition_expectedResult")
void methodName_condition_expectedResult() {
    // Arrange
    var input = createTestInput();
    
    // Act
    var result = service.methodName(input);
    
    // Assert
    assertThat(result).isNotNull();
}
```

Use `@DisplayName` for readable test descriptions.

### Service Test Structure

Service tests (one per domain service):
- `strimzi-mcp/src/test/java/.../service/KafkaServiceTest.java`
- `strimzi-mcp/src/test/java/.../service/KafkaTopicServiceTest.java`
- `strimzi-mcp/src/test/java/.../service/StrimziOperatorServiceTest.java`
- `strimzi-mcp/src/test/java/.../service/StrimziEventsServiceTest.java`

### Diagnostic Service Test Structure

Diagnostic service tests (one per diagnostic service):
- `strimzi-mcp/src/test/java/.../service/KafkaClusterDiagnosticServiceTest.java`
- `strimzi-mcp/src/test/java/.../service/KafkaConnectivityDiagnosticServiceTest.java`
- `strimzi-mcp/src/test/java/.../service/KafkaMetricsDiagnosticServiceTest.java`
- `strimzi-mcp/src/test/java/.../service/OperatorMetricsDiagnosticServiceTest.java`

### Tool Test Structure

Tool tests (one per tools class):
- `strimzi-mcp/src/test/java/.../tool/KafkaToolsTest.java`
- `strimzi-mcp/src/test/java/.../tool/KafkaTopicToolsTest.java`
- `strimzi-mcp/src/test/java/.../tool/KafkaNodePoolToolsTest.java`
- `strimzi-mcp/src/test/java/.../tool/StrimziOperatorToolsTest.java`
- `strimzi-mcp/src/test/java/.../tool/McpDiscoveryTest.java`

### Common Module Tests

Common module tests:
- `common/src/test/java/.../readiness/KubernetesConnectionReadinessCheckTest.java`
- `common/src/test/java/.../service/CompletionHelperTest.java`
- `common/src/test/java/.../service/DeploymentServiceTest.java`
- `common/src/test/java/.../service/PodsServiceTest.java`
- `common/src/test/java/.../util/InputUtilsTest.java`

## Test Coverage

Generate test coverage reports:

```bash
# Run tests with coverage
mvn test jacoco:report

# View coverage report
open target/site/jacoco/index.html
```

## Mocking

Tests use Mockito for mocking Kubernetes client interactions:

```java
@Mock
private KubernetesClient kubernetesClient;

@Mock
private NonNamespaceOperation<Kafka, KafkaList, Resource<Kafka>> kafkaOperation;

@BeforeEach
void setUp() {
    when(kubernetesClient.resources(Kafka.class)).thenReturn(kafkaOperation);
}
```

## Test Isolation

- Each test should be independent
- Use `@BeforeEach` to set up test state
- Use `@AfterEach` to clean up resources
- Don't rely on test execution order

## Running Specific Tests

```bash
# Run a single test class
mvn test -Dtest=KafkaServiceTest

# Run a single test method
mvn test -Dtest=KafkaServiceTest#listClusters_withNamespace_returnsClusters

# Run tests matching a pattern
mvn test -Dtest=Kafka*Test

# Run tests with verbose output
mvn test -X
```

## Continuous Integration

Tests run automatically on:
- Pull requests
- Commits to main branch
- Release builds

CI configuration: `.github/workflows/build.yml`

## Troubleshooting

### Test Failures

```bash
# Run with verbose output
mvn test -X

# Run a single test for debugging
mvn test -Dtest=MyTest#specificTestMethod

# Skip tests temporarily
mvn package -DskipTests
```

### System Test Issues

```bash
# Check cluster connectivity
kubectl cluster-info

# Verify Strimzi is installed
kubectl get crd | grep strimzi

# Check test logs
cat systemtest/target/surefire-reports/*.txt
```

## Best Practices

1. **Test behavior, not implementation** — Focus on what the code does, not how
2. **Use meaningful test names** — Follow `methodName_condition_expectedResult` pattern
3. **Keep tests simple** — One assertion per test when possible
4. **Mock external dependencies** — Don't rely on real Kubernetes clusters for unit tests
5. **Test edge cases** — Null inputs, empty lists, error conditions
6. **Use test fixtures** — Create reusable test data builders

## Next Steps

- **[Contributing](contributing.md)** — Contribution guidelines
- **[Building](building.md)** — Build from source
- **[Architecture](architecture.md)** — Understand the system design