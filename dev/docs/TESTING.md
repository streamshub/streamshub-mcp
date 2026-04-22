# Testing

## Unit tests

Unit tests use Quarkus test framework with Mockito to mock Kubernetes API interactions.
No live Kubernetes cluster is required.

```bash
./mvnw test                                        # Run all unit tests
./mvnw test -pl strimzi-mcp                        # Run tests for a specific module
./mvnw test -Dtest=KafkaServiceTest                # Run a single test class
./mvnw test -Dtest=KafkaServiceTest#listClusters   # Run a single test method
```

### Writing unit tests

Follow the Arrange-Act-Assert pattern with `@DisplayName` for readable descriptions.
Test naming convention: `methodName_condition_expectedResult`.

Each domain service, diagnostic service, and tools class has a corresponding test class.

## System tests

System tests verify complete workflows against a real Kubernetes cluster.
They use [kubetest4j](https://github.com/skodjob/kubetest4j) for resource lifecycle management.

System tests are **skipped by default**.
Enable them with the `systemtest` Maven profile:

```bash
# Default execution
./mvnw verify -Psystemtest

# Using custom MCP image
./mvnw verify -Psystemtest -DMCP_IMAGE=quay.io/myorg/strimzi-mcp:dev

# Use existing Strimzi installation on the cluster
./mvnw verify -Psystemtest -DSKIP_STRIMZI_INSTALL=true
```

### Configuration

| Variable | Description | Default |
|----------|-------------|---------|
| `MCP_IMAGE` | MCP server container image | `quay.io/streamshub/strimzi-mcp:latest` |
| `MCP_URL` | Direct MCP URL (skips connectivity setup). Not all system tests support this option. | `null` |
| `MCP_CONNECTIVITY` | Connectivity strategy: `NODEPORT`, `INGRESS`, or `ROUTE` | Auto-detected |
| `MCP_INGRESS_PORT` | Localhost port for Ingress access | `9090` |
| `MCP_INGRESS_HOST` | Hostname for Ingress rule | `""` (no host constraint) |
| `SKIP_STRIMZI_INSTALL` | Skip Strimzi operator and Kafka deployment | `false` |
| `KAFKA_CLUSTER_NAME` | Kafka cluster name | `mcp-cluster` |
| `KAFKA_NAMESPACE` | Kafka namespace | `strimzi-kafka` |

### Writing system tests

System test classes use the `*ST.java` suffix and extend `AbstractST`, which registers kubetest4j resource types and provides common setup and teardown.
Resource cleanup is handled automatically by kubetest4j.

### Prerequisites

- Access to a Kubernetes cluster with `kubectl` configured
- RBAC permissions to create namespaces, deploy operators, and deploy workloads
