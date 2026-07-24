Add a new composite diagnostic tool to the StreamsHub MCP project following the 3-phase workflow pattern.

$ARGUMENTS

---

## Step 1 — Create the Report DTO

Create the report record in the appropriate domain sub-package under `strimzi-mcp/src/main/java/io/streamshub/mcp/strimzi/dto/`
(e.g., `dto/kafka/`, `dto/kafkaconnect/`, `dto/kafkatopic/`, `dto/operator/`).

Follow the naming pattern `*DiagnosticReport` — use the resource domain as prefix
(e.g., `ClusterDiagnosticReport`, `OperatorMetricsDiagnosticReport`). Compose existing DTOs into a single report:

```java
/**
 * Diagnostic report for the <resource>.
 *
 * @param resourceName the resource name
 * @param namespace the namespace
 * @param stepsCompleted list of completed diagnostic steps
 * @param stepsFailed list of steps that failed (workflow continues on failure)
 * @param analysis LLM analysis summary (null if Sampling not supported)
 * @param timestamp report generation timestamp
 * @param message human-readable summary
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record XxxDiagnosticReport(
    @JsonProperty("resource_name") String resourceName,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("steps_completed") List<String> stepsCompleted,
    @JsonProperty("steps_failed") List<String> stepsFailed,
    @JsonProperty("analysis") String analysis,
    @JsonProperty("timestamp") String timestamp,
    @JsonProperty("message") String message
    // ... add domain-specific fields (status, pods, logs, events, metrics)
) {
    // static factory method
}
```

Reference existing report DTOs for structure:
- `KafkaClusterDiagnosticReport` (`dto/kafka/`) — cluster, node pools, pods, operator, logs, events, metrics
- `KafkaConnectivityDiagnosticReport` (`dto/kafka/`) — cluster, bootstrap, certificates, pods, logs
- `KafkaMetricsDiagnosticReport` (`dto/kafka/`) — cluster, pods, replication/performance/resource/throughput metrics
- `KafkaTopicDiagnosticReport` (`dto/kafkatopic/`) — topic status, cluster, events
- `KafkaConnectDiagnosticReport` (`dto/kafkaconnect/`) — Connect cluster, pods, logs, events
- `KafkaConnectorDiagnosticReport` (`dto/kafkaconnect/`) — connector, parent Connect cluster, pods, logs
- `KafkaMirrorMaker2DiagnosticReport` (`dto/kafkamirrormaker2/`) — MM2 cluster, pods, logs, events
- `OperatorMetricsDiagnosticReport` (`dto/operator/`) — operator, reconciliation/resource/JVM metrics, logs

---

## Step 2 — Create the Diagnostic Service

Create in the appropriate domain sub-package under `strimzi-mcp/src/main/java/io/streamshub/mcp/strimzi/service/`
(e.g., `service/kafka/`, `service/kafkaconnect/`, `service/kafkatopic/`, `service/operator/`) extending `BaseDiagnosticService`:

```java
@ApplicationScoped
public class XxxDiagnosticService extends BaseDiagnosticService {

    private static final Logger LOG = Logger.getLogger(XxxDiagnosticService.class);

    @Inject
    KafkaService kafkaService;
    // inject other domain services as needed

    XxxDiagnosticService() {
        // package-private no-arg constructor for CDI
    }

    /**
     * Runs the full diagnostic workflow.
     */
    public XxxDiagnosticReport diagnose(
            final String namespace,
            final String name,
            final Sampling sampling,
            final Elicitation elicitation,
            final McpLog mcpLog,
            final Progress progress,
            final Cancellation cancellation) {
        // Orchestrate 3 phases
    }
}
```

### BaseDiagnosticService provides:
- `objectMapper` — shared Jackson mapper
- `triageMaxTokens` — from `mcp.sampling.triage-max-tokens`
- `analysisMaxTokens` — from `mcp.sampling.analysis-max-tokens`
- `defaultTailLines` — from `mcp.log.tail-lines`
- `performSampling(sampling, systemPrompt, data, maxTokens)` — send Sampling request
- `performTriage(sampling, systemPrompt, phase1Summary)` — send triage, parse JSON response
- `performAnalysis(sampling, systemPrompt, fullData)` — send analysis request

---

## Step 3 — Implement the 3-Phase Workflow

### Phase 1 — Initial Data Gathering (always runs)

```java
@WithSpan("diagnose.xxx.status")
Map<String, Object> gatherStatus(final String namespace, final String name, ...) {
    DiagnosticHelper.sendProgress(progress, 1, totalSteps, "Checking status");
    DiagnosticHelper.checkCancellation(cancellation);
    // Call existing domain services
    // Record step in stepsCompleted/stepsFailed
}
```

Key behaviors:
- If namespace is ambiguous, use `NamespaceElicitationHelper.elicitNamespace()`
- Call existing domain services (never re-implement Kubernetes queries)
- Record completed steps in `stepsCompleted` list
- Record failures in `stepsFailed` list (do not abort)
- Report progress via `DiagnosticHelper.sendProgress()`
- Check cancellation via `DiagnosticHelper.checkCancellation()` between steps

### Phase 2 — Deep Investigation (Sampling-guided)

```java
@WithSpan("diagnose.xxx.triage")
Map<String, Object> performTriagePhase(final Sampling sampling, final Map<String, Object> phase1Data, ...) {
    if (sampling == null || !sampling.isSupported()) {
        // Investigate all areas (no LLM guidance available)
        return investigateAll();
    }
    // Send Phase 1 data to LLM for triage
    Map<String, Object> triageResult = performTriage(sampling, TRIAGE_PROMPT, phase1Data);
    // Follow LLM's recommendations for deeper investigation
}
```

### Phase 3 — Analysis (Sampling-guided)

```java
@WithSpan("diagnose.xxx.analysis")
String performAnalysisPhase(final Sampling sampling, final Map<String, Object> allData) {
    if (sampling == null || !sampling.isSupported()) {
        return null; // Return raw data without analysis
    }
    return performAnalysis(sampling, ANALYSIS_PROMPT, allData);
}
```

### Method visibility

Gather/triage/analysis methods must be **package-private** (not private) for Quarkus ArC
subclass-based interception to work with `@WithSpan`.

### Span naming

Follow the pattern `diagnose.{domain}.{step}`:
- `diagnose.cluster.status`, `diagnose.cluster.triage`, `diagnose.cluster.analysis`
- `diagnose.connectivity.bootstrap`, `diagnose.connectivity.triage`

---

## Step 4 — Wire Up in DiagnosticTools

Add the tool method to `strimzi-mcp/src/main/java/io/streamshub/mcp/strimzi/tool/diagnostic/DiagnosticTools.java`:

```java
@WithSpan("tool.diagnose_xxx")
@MetaField(name = ToolMetaFields.TYPE, value = ToolMetaFields.Types.DIAGNOSE)
@MetaField(name = ToolMetaFields.RESOURCE, value = StrimziToolResources.XXX)
@MetaField(name = ToolMetaFields.COMPOSITE, value = "true", type = MetaField.Type.BOOLEAN)
@Tool(
    name = "diagnose_xxx",
    description = "Runs a multi-step diagnostic on a <resource>."
        + " Gathers status, investigates issues, and provides analysis.",
    annotations = @Tool.Annotations(
        readOnlyHint = true,
        destructiveHint = false,
        idempotentHint = true,
        openWorldHint = false
    )
)
public XxxDiagnosticReport diagnoseXxx(
    @ToolArg(description = StrimziToolsPrompts.CLUSTER_DESC) final String clusterName,
    @ToolArg(description = StrimziToolsPrompts.NS_DESC, required = false) final String namespace,
    final Sampling sampling,
    final Elicitation elicitation,
    final McpLog mcpLog,
    final Progress progress,
    final Cancellation cancellation
) {
    return xxxDiagnosticService.diagnose(namespace, clusterName, sampling, elicitation, mcpLog, progress, cancellation);
}
```

Note: `DiagnosticTools` class has `@Guarded` at class level.

---

## Step 5 — Register in McpDiscoveryTest

Edit `strimzi-mcp/src/test/java/io/streamshub/mcp/strimzi/tool/McpDiscoveryTest.java`:

1. Add `"diagnose_xxx"` to the `expectedTools` list in `testToolDiscovery()`

2. Add metadata to `expectedToolMetadata()`:
   ```java
   map.put("diagnose_xxx", new String[]{ToolMetaFields.Types.DIAGNOSE, StrimziToolResources.XXX});
   ```

3. Add to `compositeToolNames()`:
   ```java
   return Set.of(
       // ... existing composite tools ...
       "diagnose_xxx"
   );
   ```

---

## Step 6 — Write Tests

Create `strimzi-mcp/src/test/java/.../service/XxxDiagnosticServiceTest.java`:

- Test full workflow with Sampling supported (all 3 phases)
- Test graceful degradation without Sampling (raw data returned)
- Test individual step failure resilience (workflow continues)
- Test namespace elicitation when ambiguous
- Test cancellation handling
- Mock domain services and Sampling responses
- Name tests: `testDescriptiveName` in camelCase (e.g., `testFullWorkflowWithHealthyCluster`, `testStepFailureDoesNotAbortWorkflow`)

---

## Step 7 — Build and Verify

```bash
./mvnw compile    # Checkstyle + compilation
./mvnw test       # All tests including McpDiscoveryTest
```

---

## Step 8 — Update Documentation

1. **`docs/strimzi-mcp/tools/diagnostics.md`** — add a new section for the tool (name, description, parameters)
2. **`CHANGELOG.md`** — entry under `## [Unreleased]` > `### Added`
3. **`AGENTS.md`** — add to the diagnostic report DTOs list and DiagnosticTools description
4. **`dev/test-plans/strimzi-mcp-tool-test-plan.md`** — add diagnostic test case
