Write a new system (e2e) test for the StreamsHub MCP project. System tests live in
`systemtest/src/test/java/io/streamshub/mcp/systemtest/` and run against a real Kubernetes cluster.

$ARGUMENTS

---

## Step 1 — Create the Test Class

Create in `systemtest/src/test/java/io/streamshub/mcp/systemtest/tools/` (or `metrics/`, `resources/`):

```java
@Epic("Strimzi MCP E2E")
@Feature("Feature Name")
@Tag(REGRESSION)
@Tag(TOOLS)
class XxxToolsST extends AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(XxxToolsST.class);

    @InjectResourceManager
    KubeResourceManager krm;

    @ClassNamespace(name = Constants.MCP_NAMESPACE)
    static Namespace mcpNamespace;

    @ClassNamespace(name = Constants.STRIMZI_NAMESPACE, labels = {"app=strimzi"})
    static Namespace strimziNamespace;

    @ClassNamespace(name = Constants.KAFKA_NAMESPACE, labels = {"app=strimzi"})
    static Namespace kafkaNamespace;

    private static McpAssured.McpStreamableTestClient mcpClient;
}
```

Rules:
- Class name must end with `ST` suffix (Failsafe plugin pattern: `**/*ST.java`)
- Extend `AbstractST` (provides assertion helpers, JSON parsing, MCP client setup)
- Use Allure annotations: `@Epic`, `@Feature`, `@Story`, `@Tag`
- Tags from `TestTags`: `REGRESSION`, `TOOLS`, `METRICS`, `LOGS`, `SECURITY`, `RESILIENCE`, `SMOKE`, `ACCEPTANCE`
- Use `@ClassNamespace` for namespace declarations
- Use `@InjectResourceManager` for kubetest4j resource management

---

## Step 2 — Setup and Teardown

```java
@BeforeAll
void setup() {
    String kafkaNs = kafkaNamespace.getMetadata().getName();

    if (!Environment.SKIP_STRIMZI_INSTALL) {
        StrimziSetup.deploy(strimziNamespace.getMetadata().getName());

        // Create test resources (node pools first, then Kafka)
        krm.createOrUpdateResourceWithoutWait(
            KafkaNodePoolTemplates.controllerPool(kafkaNs, "controller-np",
                Constants.KAFKA_CLUSTER_NAME, 3).build(),
            KafkaNodePoolTemplates.brokerPool(kafkaNs, "broker-np1",
                Constants.KAFKA_CLUSTER_NAME, 3).build());

        krm.createOrUpdateResourceWithWait(
            KafkaTemplates.kafka(kafkaNs, Constants.KAFKA_CLUSTER_NAME, 3).build());

        // Create topics, users, etc. as needed
        krm.createOrUpdateResourceWithWait(
            KafkaTopicTemplates.topic(kafkaNs, "test-topic",
                Constants.KAFKA_CLUSTER_NAME, 3, 1).build());
    }

    McpServerSetup.deploy(mcpNamespace.getMetadata().getName());
    String mcpUrl = ConnectivitySetup.expose(mcpNamespace.getMetadata().getName());
    mcpClient = McpClientFactory.create(mcpUrl);
}

@AfterAll
static void cleanup() {
    if (mcpClient != null) {
        mcpClient.disconnect();
    }
}
```

Resource creation patterns:
- `createOrUpdateResourceWithoutWait(...)` — async creation (use for node pools, topics)
- `createOrUpdateResourceWithWait(...)` — blocks until resource is ready (use for Kafka clusters)
- kubetest4j handles cleanup automatically via `@KubernetesTest(cleanup = AUTOMATIC)`

Additional setup helpers:
- `McpServerSetup.deploySensitiveRbac(mcpNs, kafkaNs)` — for tools needing extra RBAC
- `KafkaTemplates.deployMetricsConfigMap(kafkaNs)` — Kafka metrics ConfigMap
- `KafkaTemplates.deployPodMonitors(kafkaNs)` — Kafka PodMonitor resources
- `KafkaConnectTemplates.deployMetricsConfigMap(kafkaNs)` — Connect metrics ConfigMap
- `KafkaConnectTemplates.deployPodMonitors(kafkaNs)` — Connect PodMonitor resources
- `KafkaBridgeTemplates.deployMetricsConfigMap(kafkaNs)` — Bridge metrics ConfigMap
- `KafkaBridgeTemplates.deployPodMonitors(kafkaNs)` — Bridge PodMonitor resources
- `DrainCleanerSetup.deploy(ns)` — for drain cleaner tests

---

## Step 3 — Write Test Methods

### Success case

```java
@Test
@Story("tool returns expected data")
void testGetResource() {
    String ns = kafkaNamespace.getMetadata().getName();
    Map<String, Object> args = Map.of(
        "clusterName", Constants.KAFKA_CLUSTER_NAME,
        "namespace", ns);

    mcpClient.when()
        .toolsCall("get_resource", args, response -> {
            JsonNode root = assertToolSuccess(response);
            LOGGER.info("get_resource response:\n{}", response.content().getFirst().asText().text());

            assertEquals(Constants.KAFKA_CLUSTER_NAME, root.path("name").asText(), "Name should match");
            assertEquals(ns, root.path("namespace").asText(), "Namespace should match");
            assertEquals("Ready", root.path("readiness").asText(), "Should be Ready");
        })
        .thenAssertResults();
}
```

### Error case

```java
@Test
@Story("tool returns error for non-existent resource")
void testGetResourceNotFound() {
    Map<String, Object> args = Map.of(
        "clusterName", "non-existent",
        "namespace", kafkaNamespace.getMetadata().getName());

    mcpClient.when()
        .toolsCall("get_resource", args, response -> {
            assertToolError(response, "not found");
        })
        .thenAssertResults();
}
```

### List response

```java
@Test
@Story("tool lists resources")
void testListResources() {
    mcpClient.when()
        .toolsCall("list_resources", Map.of(), response -> {
            JsonNode root = assertToolSuccess(response);
            LOGGER.info("list_resources response:\n{}", response.content().getFirst().asText().text());

            assertTrue(root.isArray(), "Should return array");
            assertTrue(root.size() >= 1, "Should have at least 1 resource");

            JsonNode first = root.get(0);
            assertFalse(first.path("name").isMissingNode(), "Should have name");
            assertFalse(first.path("namespace").isMissingNode(), "Should have namespace");
        })
        .thenAssertResults();
}
```

---

## Step 4 — Use AbstractST Assertion Helpers

Always use the appropriate helper instead of writing assertions from scratch:

| Helper | Use for |
|---|---|
| `assertToolSuccess(response)` | All success cases — asserts `!isError()`, `!content.isEmpty()`, parses JSON, returns `JsonNode` |
| `assertToolError(response, "substring")` | All error cases — asserts `isError()` and message contains substring (case-insensitive) |
| `assertDiagnosticReport(root)` | `diagnose_*` and `assess_*` tools — validates steps_completed is non-empty array, timestamp, message |
| `assertPodSummaryResponse(root)` | `get_*_pods` tools — validates total_pods > 0, ready_pods >= 0, health_status, timestamp |
| `assertLogsResponse(root, field, value)` | Non-cluster/non-operator `get_*_logs` tools (Connect, Bridge, MirrorMaker2) — validates resource field, pods array, log_lines |
| `assertMetricsResponse(root, field, name)` | `get_*_metrics` tools — validates resource field matches name, metrics structure |
| `assertClusterLogsResponse(root, name)` | `get_kafka_cluster_logs` — validates cluster logs structure |
| `assertOperatorLogsResponse(root, ns)` | `get_strimzi_operator_logs` — validates operator logs |
| `assertEventsResponse(root, name, ns)` | `get_strimzi_events` — validates resource_name, namespace, total_events >= 0, timestamp, message |
| `assertNoStackTrace(text)` | Any text response — ensures no Java exception traces leaked |

---

## Step 5 — Follow Test Quality Rules

- [ ] **Log every response** — `LOGGER.info("tool_name response:\n{}", response.content().getFirst().asText().text())`
- [ ] **Log length for large responses** — `LOGGER.info("tool_name response (length={})", text.length())`
- [ ] **Validate content, not just success** — never write only `assertFalse(response.isError())`
- [ ] **Verify resource identity** — check name and namespace match expected values
- [ ] **Verify counts** — when deploying known resources, assert the response count
- [ ] **Iterate JSON arrays** — never use `.toString().contains("value")`
- [ ] **Error messages are user-friendly** — use `assertToolError(response, "not found")` not just `assertTrue(response.isError())`
- [ ] **Kafka version >= 4.2.0** in all test data

---

## Step 6 — Constants and Environment Reference

Use constants from `Constants` class (not hardcoded strings):
- `Constants.KAFKA_CLUSTER_NAME` — `"mcp-cluster"`
- `Constants.KAFKA_CLUSTER_NAME_2` — `"mcp-cluster-2"` (for multi-cluster tests)
- `Constants.KAFKA_NAMESPACE` — `"strimzi-kafka"`
- `Constants.KAFKA_NAMESPACE_2` — `"strimzi-kafka-2"` (for multi-namespace tests)
- `Constants.MCP_NAMESPACE` — `"streamshub-mcp"`
- `Constants.STRIMZI_NAMESPACE` — `"strimzi"`
- `Constants.TOPIC_NAME` — `"mcp-test-topic"`
- `Constants.CONNECT_CLUSTER_NAME` — `"mcp-connect"`
- `Constants.BRIDGE_NAME` — `"mcp-bridge"`
- `Constants.DRAIN_CLEANER_NAME` — `"strimzi-drain-cleaner"`
- `Constants.DRAIN_CLEANER_NAMESPACE` — `"strimzi-drain-cleaner"`
- `Constants.CONNECT_TEST_IMAGE` — custom Connect image with connectors

Template helpers for resource creation:
- `KafkaTemplates.kafka(ns, name, replicas)` — basic Kafka cluster
- `KafkaTemplates.kafkaWithMetrics(ns, name, replicas)` — Kafka with Prometheus metrics
- `KafkaTemplates.kafkaWithCruiseControl(ns, name, replicas)` — Kafka with Cruise Control
- `KafkaNodePoolTemplates.controllerPool(ns, name, cluster, replicas)`
- `KafkaNodePoolTemplates.brokerPool(ns, name, cluster, replicas)`
- `KafkaNodePoolTemplates.mixedPool(ns, name, cluster, replicas)` — broker+controller combined
- `KafkaTopicTemplates.topic(ns, name, cluster, partitions, replicas)`
- `KafkaUserTemplates.scramUserWithAcls(ns, name, cluster)`
- `KafkaUserTemplates.adminUser(ns, name, cluster)`
- `KafkaUserTemplates.tlsUser(ns, name, cluster)`
- `KafkaBridgeTemplates.kafkaBridge(ns, name, cluster, replicas)`
- `KafkaConnectTemplates.kafkaConnect(ns, name, cluster, replicas)`
- `KafkaConnectorTemplates.camelTimerSource(ns, name, connectCluster)`
- `KafkaConnectorTemplates.connector(ns, name, connectCluster)` — generic connector
- `KafkaMirrorMaker2Templates.mirrorMaker2(ns, name, sourceCluster, targetCluster)`
- `KafkaRebalanceTemplates.rebalance(ns, name, cluster)`

---

## Step 7 — Run the Tests

```bash
# Run all system tests (requires Kubernetes cluster)
./mvnw verify -Psystemtest

# Run a specific test class
./mvnw verify -Psystemtest -Dit.test=XxxToolsST

# Run a specific test method
./mvnw verify -Psystemtest -Dit.test=XxxToolsST#testGetResource
```

---

## Step 8 — Update Documentation

1. **`dev/test-plans/strimzi-mcp-tool-test-plan.md`** — add test cases for new tools
2. **`CHANGELOG.md`** — add entry under `## [Unreleased]` if adding significant test coverage
