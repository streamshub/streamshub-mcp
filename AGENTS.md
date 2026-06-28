# Project: StreamsHub MCP (Multi-Module)

Multi-module Quarkus mono-repo providing MCP (Model Context Protocol) servers for Kubernetes-based
streaming platforms. Java 21, Quarkus 3.x, Strimzi API 1.0.x, Fabric8 Kubernetes client.

## Documentation Structure

- **User docs**: `docs/` -- Installation, configuration, usage, and troubleshooting for end users. Each MCP server has its own subdirectory (e.g., `docs/strimzi-mcp/`).
- **Developer docs**: This file (`AGENTS.md`) -- Architecture, patterns, and conventions for developers

### Documentation Update Requirements

**CRITICAL**: When implementing new features or modifying existing functionality, you MUST update the relevant documentation:

1. **User documentation** (`docs/`) -- Update when:
   - Adding new MCP tools, prompts, or resource templates
   - Changing tool parameters or behavior
   - Modifying configuration options
   - Adding new features visible to end users

2. **This file** (`AGENTS.md`) -- Update when:
   - Changing architecture or design patterns
   - Adding new modules or services
   - Changing coding standards
   - Introducing new architectural layers
   - Modifying development workflows

3. **Changelog** (`CHANGELOG.md`) -- Update when:
   - Adding, changing, or removing tools, prompts, or resource templates
   - Fixing bugs
   - Changing configuration options or defaults
   - Making any user-visible change
   - Add entries under the `## [Unreleased]` section using Keep a Changelog categories: Added, Changed, Deprecated, Removed, Fixed, Security

4. **Developer documentation** (`dev/docs/`) -- Update when:
   - Changing build, test, or CI workflows (`TESTING.md`)
   - Changing release process or scripts (`RELEASING.md`)
   - Modifying development environment setup or tooling

5. **MCP tool test plan** (`dev/test-plans/strimzi-mcp-tool-test-plan.md`) -- Update when:
   - Adding, modifying, or removing MCP tools (add/update test cases for the tool)
   - Changing tool parameters or behavior (update expected parameters and results)
   - Modifying dev environment resources (`dev/`) such as Kafka clusters, topics, users, connectors, or other Strimzi resources (update expected values in test cases and the dev deployment reference section)
   - Adding new optional dev components (add a new test phase or test cases)

Documentation updates are mandatory and must be completed before the task is considered done.

## Modules

- **`common`** (`streamshub-mcp-common`) - Generic Kubernetes helpers, DTOs, MCP framework utilities shared across modules
- **`metrics-prometheus`** (`streamshub-metrics-prometheus`) - Prometheus/Thanos metrics provider (pluggable, replaceable JAR)
- **`loki-log-provider`** (`streamshub-loki-log-provider`) - Loki log provider (pluggable, replaceable JAR)
- **`strimzi-mcp`** (`strimzi-mcp`) - MCP Server for Strimzi: Kafka management tools and services

## Build & Test

```bash
./mvnw compile                      # compile all modules + checkstyle
./mvnw test                         # unit tests (no live cluster needed)
./mvnw verify -Pcoverage            # tests with JaCoCo coverage (reports in target/site/jacoco/)
./mvnw quarkus:dev -pl strimzi-mcp  # dev mode on http://localhost:8080/mcp
```

Checkstyle runs during compile phase. Fix all violations before committing.

## Architecture

### Common module (`common/`)

```
io.streamshub.mcp.common.
├── config/         → KubernetesConstants (labels, conditions, phases, health status)
├── dto/            → PodSummaryResponse, PodLogsResult, LogCollectionParams (generic pod DTOs)
│   └── metrics/    → MetricSample, PodTarget, MetricsQueryParams, AggregatedTimeSeries,
│                     AggregationLevel (PARTITION→TOPIC→BROKER→CLUSTER hierarchy)
├── guardrail/      → Guarded, GuardrailFilter, GuardrailInterceptor, InputValidationFilter,
│                     LogRedactionFilter, RateLimitFilter, ResponseSizeLimitFilter,
│                     MetricsFilter (Micrometer tool call metrics), RateCategory
├── readiness/      → KubernetesConnectionReadinessCheck (health check for kube API)
├── service/        → KubernetesResourceService, PodsService, DeploymentService, CompletionHelper,
│   │                 DiagnosticHelper (shared MCP framework utilities for diagnostic tools)
│   ├── log/        → LogCollectionService, LogCollectorProvider, KubernetesLogProvider
│   └── metrics/    → MetricsProvider (interface), PodScrapingMetricsProvider, MetricsQueryService
└── util/           → InputUtils
    └── metrics/    → PrometheusTextParser, MetricLabelFilter
```

### Prometheus metrics module (`metrics-prometheus/`)

```
io.streamshub.mcp.metrics.prometheus.
├── dto/       → PrometheusResponse
├── service/   → PrometheusClient, PrometheusAuthFilter, PrometheusMetricsProvider
└── util/      → PromQLSanitizer
```

### Loki log provider module (`loki-log-provider/`)

```
io.streamshub.mcp.loki.
├── config/    → LokiConfig (label mapping, auth mode)
├── service/   → LokiClient, LokiAuthFilter
├── util/      → LogQLSanitizer
└── LokiLogProvider (implements LogCollectorProvider)
```

### MCP Server for Strimzi module (`strimzi-mcp/`)

```
io.streamshub.mcp.strimzi.
├── tool/              → MCP tool definitions (thin wrappers, no logic)
│   ├── kafka/         → KafkaTools, KafkaConfigurationTools
│   ├── kafkatopic/    → KafkaTopicTools
│   ├── kafkauser/     → KafkaUserTools
│   ├── kafkanodepool/ → KafkaNodePoolTools
│   ├── kafkarebalance/ → KafkaRebalanceTools
│   ├── kafkabridge/   → KafkaBridgeTools
│   ├── kafkaconnect/  → KafkaConnectTools, KafkaConnectorTools
│   ├── kafkamirrormaker2/ → KafkaMirrorMaker2Tools
│   ├── draincleaner/  → DrainCleanerTools
│   ├── operator/      → StrimziOperatorTools, StrimziEventsTools
│   ├── diagnostic/    → DiagnosticTools (composite diagnostic tools)
│   └── metrics/       → MetricsTools
├── service/           → Business logic (KafkaService, KafkaTopicService, KafkaNodePoolService,
│                        KafkaCertificateService, KafkaConfigService, KafkaConfigComparisonService,
│                        KafkaRebalanceService, KafkaClusterOverviewService, KafkaFleetOverviewService,
│                        BootstrapMatcher, StrimziOperatorService, StrimziEventsService,
│                        DrainCleanerService, CompletionService)
│                        Diagnostic orchestrators: KafkaClusterDiagnosticService,
│                        KafkaConnectivityDiagnosticService, KafkaMetricsDiagnosticService,
│                        OperatorMetricsDiagnosticService
│   ├── kafkabridge/   → KafkaBridgeService
│   ├── kafkaconnect/  → KafkaConnectService, KafkaConnectorService,
│   │                    KafkaConnectDiagnosticService, KafkaConnectorDiagnosticService
│   ├── kafkamirrormaker2/ → KafkaMirrorMaker2Service,
│   │                    KafkaMirrorMaker2DiagnosticService
│   └── metrics/       → KafkaMetricsService, KafkaExporterMetricsService,
│                        KafkaBridgeMetricsService, KafkaConnectMetricsService,
│                        StrimziOperatorMetricsService
├── dto/               → Strimzi response records and diagnostic reports
│   ├── kafkabridge/   → KafkaBridgeResponse, etc.
│   ├── kafkaconnect/  → KafkaConnectResponse, KafkaConnectorResponse, etc.
│   ├── kafkamirrormaker2/ → KafkaMirrorMaker2Response, KafkaMirrorMaker2DiagnosticReport, etc.
│   └── metrics/       → KafkaMetricsResponse, KafkaExporterMetricsResponse,
│                        KafkaBridgeMetricsResponse, KafkaConnectMetricsResponse,
│                        StrimziOperatorMetricsResponse
├── prompt/            → MCP prompt templates (DiagnoseClusterIssuePrompt, TroubleshootConnectivityPrompt,
│                        AnalyzeKafkaMetricsPrompt, AnalyzeStrimziOperatorMetricsPrompt,
│                        TroubleshootConnectorPrompt, CompareClusterConfigsPrompt, PromptCompletions)
├── resource/          → ResourceSubscriptionManager (Kubernetes watches → MCP notifications)
├── resource/template/ → MCP resource templates and completions (5 templates)
├── config/            → StrimziConstants (labels, resource URIs), StrimziToolsPrompts
│   └── metrics/       → KafkaMetricCategories, KafkaExporterMetricCategories,
│                        KafkaBridgeMetricCategories, KafkaConnectMetricCategories,
│                        StrimziOperatorMetricCategories (category constants + metric name mappings)
└── util/              → NamespaceElicitationHelper (Strimzi-specific namespace disambiguation),
                         MetricNameResolver, TimeRangeValidator
```

### Layer rules

- **Tools** call domain services and return typed responses. No try/catch, no business logic.
- **Prompts** return structured step-by-step instructions referencing tool names. No logic.
- **Resource templates** fetch and serialize live Kubernetes state. Thin wrappers over services.
- **Completions** delegate to `CompletionService` / `CompletionHelper`. No logic.
- **Domain services** (strimzi) contain all business logic. Throw `ToolCallException` for errors.
- **Common services** are generic Kubernetes helpers shared across modules.
- **DTOs** are immutable `record` types. Use static factory methods (`of()`, `empty()`) not constructors directly.
- **Metrics providers** implement `MetricsProvider` interface. Selected via `@LookupIfProperty` on `mcp.metrics.provider`.
  Domain services use `MetricsQueryService` (in common/) to query metrics — it handles provider lookup,
  query param construction, and delegation. Do not inject `Instance<MetricsProvider>` directly in domain services.
- **Metrics aggregation** uses `AggregatedTimeSeries.fromSamples(samples, level)` to group and average
  metric samples by `AggregationLevel` (PARTITION → TOPIC → BROKER → CLUSTER). `MetricLabelFilter.labelsForAggregation()`
  strips labels at each level. Each `*MetricCategories` class defines `finestLevel()` per category so the
  requested level is clamped to what the category supports. Results include summary statistics (min, max, avg, latest)
  and time-series compression for constant-value runs.
- **Diagnostic services** orchestrate multi-step workflows by calling existing domain services.
  They use `DiagnosticHelper` (common/) for MCP framework interactions and `NamespaceElicitationHelper`
  (strimzi-mcp) for Strimzi-specific namespace disambiguation. Individual step failures do not abort the workflow.
- **Metric category constants** are defined as public `static final String` fields in each `*MetricCategories` class
  (e.g., `KafkaMetricCategories.REPLICATION`). Use these constants instead of string literals when referencing
  metric categories in services, diagnostic tools, or prompts.

## MCP Tool Pattern

```java
@Singleton
@WrapBusinessError(Exception.class)
public class XxxTools {

    @Inject
    XxxService xxxService;

    XxxTools() {  // package-private no-arg constructor for CDI
    }

    @WithSpan("tool.verb_noun")
    @Tool(
        name = "verb_noun",
        description = "Short description of what the tool does."
            + " Additional sentence if needed."
    )
    public TypedResponse toolMethod(
        @ToolArg(description = "...") final String requiredParam,
        @ToolArg(description = "...", required = false) final String optionalParam
    ) {
        return xxxService.operation(optionalParam, requiredParam);
    }
}
```

### OpenTelemetry tracing on tools

Every tool method is annotated with `@WithSpan("tool.<tool_name>")` where the span name
matches the `@Tool(name = ...)` value. This creates a named parent span for each MCP tool call.

### Tool Annotations

All tools declare `@Tool.Annotations` with `readOnlyHint = true`, `destructiveHint = false`,
`idempotentHint = true`, `openWorldHint = false` since all tools are read-only Kubernetes queries.
When adding new tools, always include these annotations — MCP clients like ChatGPT default
tools to "write" mode without them.

### Tool metadata

Every tool method is annotated with `@MetaField` annotations from `io.quarkiverse.mcp.server.MetaField`
to provide structured metadata in the `_meta` object of `tools/list` responses.

Three metadata fields are defined:

- **`type`** — the tool action type: `list`, `get`, `overview`, `logs`, `events`, `metrics`, `diagnose`, `compare`, `assess`, `check`
- **`resource`** — the Strimzi/Kubernetes resource: `kafka`, `kafkatopic`, `kafkauser`, `kafkanodepool`,
  `kafkarebalance`, `strimzi-operator`, `strimzi-event`, `drain-cleaner`, `kafkaconnect`, `kafkaconnector`,
  `kafkabridge`, `kafkamirrormaker2`
- **`composite`** (boolean, only when `true`) — tool aggregates multiple internal API calls

Type constants are in `io.streamshub.mcp.common.config.ToolMetaFields` (common module).
Resource constants are in `io.streamshub.mcp.strimzi.config.StrimziToolResources` (strimzi module).

```java
@MetaField(name = ToolMetaFields.TYPE, value = ToolMetaFields.Types.LIST)
@MetaField(name = ToolMetaFields.RESOURCE, value = StrimziToolResources.KAFKA)
@Tool(name = "list_kafka_clusters", description = "...")
```

### Tool naming

- Use `snake_case`: `list_kafka_clusters`, `get_kafka_topic`, `get_kafka_bootstrap_servers`
- Pattern: `verb_resource` (list, get)
- Namespace is always optional via `@ToolArg(required = false)`
- Use `StrimziToolsPrompts` constants for shared parameter descriptions (NS_DESC, CLUSTER_DESC, etc.)

### Tool descriptions

- 1-2 sentences max
- No filler phrases ("Perfect for...", "Essential for...", "Use this to...")
- State what it returns, not what it's useful for
- Use string concatenation for multi-line: `"First part." + " Second part."`

### Tools with progress and cancellation

Long-running tools (e.g., log collection across many pods) accept MCP framework parameters
for client feedback. These are injected by the framework — not user-supplied:

```java
@Tool(name = "get_kafka_cluster_logs", description = "...")
public KafkaClusterLogsResponse getKafkaClusterLogs(
    @ToolArg(description = "...") final String clusterName,
    @ToolArg(description = "...", required = false) final String namespace,
    final McpLog mcpLog,           // send log-level notifications to client
    final Progress progress,        // report completion percentage
    final Cancellation cancellation // check if client cancelled mid-operation
) {
    // Build options with callbacks
    LogCollectionParams options = LogCollectionParams.builder(...)
        .notifier(mcpLog::info)
        .cancelCheck(cancellation::skipProcessingIfCancelled)
        .progressCallback(...)
        .build();
    return kafkaService.getClusterLogs(namespace, clusterName, options);
}
```

## Composite Diagnostic Tool Pattern

Composite diagnostic tools orchestrate multi-step workflows in a single tool call.
They complement prompt templates: prompts are client-driven (LLM calls tools one by one),
composite tools are server-driven (server gathers all data internally).

```java
@Singleton
@Guarded
@WrapBusinessError(Exception.class)
public class DiagnosticTools {

    @Inject
    KafkaClusterDiagnosticService clusterDiagnosticService;

    @Tool(name = "diagnose_kafka_cluster", description = "...")
    public KafkaClusterDiagnosticReport diagnoseKafkaCluster(
        @ToolArg(description = StrimziToolsPrompts.CLUSTER_DESC) final String clusterName,
        @ToolArg(description = StrimziToolsPrompts.NS_DESC, required = false) final String namespace,
        final Sampling sampling,       // LLM-guided selective investigation
        final Elicitation elicitation,  // namespace disambiguation
        final McpLog mcpLog,
        final Progress progress,
        final Cancellation cancellation
    ) {
        return clusterDiagnosticService.diagnose(...);
    }
}
```

### Diagnostic service structure (3-phase workflow)

1. **Phase 1 — Initial data gathering**: Always runs. Gathers cluster status, node pools, pods.
   If namespace is ambiguous and Elicitation is supported, asks the user to choose.
2. **Phase 2 — Deep investigation**: If Sampling is supported, sends Phase 1 results to LLM
   and asks which areas need deeper investigation. If not supported, investigates all areas.
   - **Pod filtering**: When collecting cluster logs, the service identifies problematic pods
     (not Running, not ready, or restart count > `mcp.diagnostic.restart-threshold`, default 3).
     If problematic pods exist, logs are collected only from those pods. If all pods are healthy,
     logs are collected from all pods.
   - **Time window**: The triage LLM recommends a time window for log/event collection —
     either relative (last N minutes) for active issues, or absolute (start/end ISO 8601) for
     past incidents. Defaults to 30 minutes. If no errors are found, the window auto-escalates
     once, then uses Elicitation to ask the user whether to expand further.
3. **Phase 3 — Analysis**: If Sampling is supported, sends all gathered data to LLM for
   root cause analysis. If not supported, returns raw data without analysis.

### Key behaviors

- **Graceful degradation**: Works without Sampling/Elicitation support (gathers everything, returns raw data)
- **Step failure resilience**: Individual step failures are recorded in `stepsFailed`, workflow continues
- **Progress tracking**: Reports progress via `DiagnosticHelper.sendProgress()`
- **Cancellation**: Checks `DiagnosticHelper.checkCancellation()` between steps
- **No duplication**: Calls existing domain services (KafkaService, StrimziOperatorService, etc.)
- **Configurable token limits**: `mcp.sampling.triage-max-tokens` and `mcp.sampling.analysis-max-tokens`
- **OpenTelemetry tracing**: Gather/triage/analysis methods are annotated with `@WithSpan` for
  distributed tracing. Methods are package-private (not private) so Quarkus ArC subclass-based
  interception can intercept self-invocations from `diagnose()`. Span names follow the pattern
  `diagnose.{domain}.{step}` (e.g., `diagnose.cluster.status`, `diagnose.connectivity.triage`).

### DiagnosticHelper (common module)

Shared MCP framework utilities in `common/src/.../service/DiagnosticHelper.java`:
- `sendClientNotification(McpLog, String)` — log-level notification to MCP client
- `sendProgress(Progress, step, totalSteps, label)` — progress update to MCP client
- `checkCancellation(Cancellation)` — abort if client cancelled
- `putIfNotNull(Map, String, Object)` — conditional map insertion
- `elicitSelection(Elicitation, message, propertyName, description, options)` — generic single-select Elicitation
- `extractSamplingText(SamplingResponse)` — safe text extraction from Sampling response
- `MAP_TYPE_REF` — reusable `TypeReference<Map<String, Object>>` for JSON parsing

### NamespaceElicitationHelper (strimzi module)

Strimzi-specific namespace disambiguation in `strimzi-mcp/src/.../util/NamespaceElicitationHelper.java`:
- `isMultipleNamespacesError(ToolCallException)` — checks for Strimzi multi-namespace error
- `parseNamespacesFromError(String)` — extracts namespace list from Strimzi error message
- `elicitNamespace(ToolCallException, Elicitation, context)` — combines parsing + `DiagnosticHelper.elicitSelection`

### Diagnostic report DTOs

Compose existing DTOs into a single report. Follow the naming pattern `Kafka*DiagnosticReport`:
- `KafkaClusterDiagnosticReport` — cluster, node pools, pods, operator, logs, events, metrics
- `KafkaConnectivityDiagnosticReport` — cluster, bootstrap servers, certificates, pods, logs
- `KafkaMetricsDiagnosticReport` — cluster, pods, replication/performance/resource/throughput metrics
- `OperatorMetricsDiagnosticReport` — operator, reconciliation/resource/JVM metrics, logs
- `KafkaConnectorDiagnosticReport` — connector, parent Connect cluster, pods, logs

### Adding a new composite diagnostic tool

1. Create the report DTO in `strimzi-mcp/src/.../strimzi/dto/`
2. Create the diagnostic service in `strimzi-mcp/src/.../strimzi/service/` following the 3-phase pattern
3. Add the `@Tool` method to `DiagnosticTools`
4. Add the tool name to `McpDiscoveryTest.testToolDiscovery()` expected list
5. Create a service test in `strimzi-mcp/src/test/.../strimzi/service/`
6. Run `./mvnw compile && ./mvnw test`

### Logging in diagnostic services

Use specific resource names in log messages and client notifications:
- "Checked Kafka cluster status" (not "Checked cluster status")
- "Found 3 KafkaNodePools" (not "Found 3 node pools")
- "Checked Strimzi operator 'name' status: HEALTHY" (not "Checked operator status")
- "Collected Kafka cluster logs" (not "Collected cluster logs")
- "Failed to gather Kafka related events" (not "Failed to gather events")

## MCP Prompt Template Pattern

Prompt templates encode domain knowledge and guide LLMs through structured workflows.
They tell the LLM exactly which tools to call and in what order.

```java
@Singleton
public class XxxPrompt {

    @Prompt(name = "verb-noun", description = "What this workflow does.")
    PromptMessage execute(
        @PromptArg(description = "...") final String requiredParam,
        @PromptArg(description = "...", required = false) final String optionalParam
    ) {
        String instructions = "Step-by-step instructions referencing MCP tools...";
        return PromptMessage.withUserRole(new TextContent(instructions));
    }
}
```

- Prompt names use `kebab-case`: `diagnose-cluster-issue`, `troubleshoot-connectivity`
- Return `PromptMessage.withUserRole(new TextContent(...))` with step-by-step instructions
- Reference specific tool names in the instructions so the LLM knows what to call

## MCP Resource Template Pattern

Resource templates expose live Kubernetes state as structured JSON that clients can attach
to conversations for immediate context — without requiring tool calls.

```java
@Singleton
public class XxxResource {

    @Inject
    XxxService xxxService;

    @ResourceTemplate(
        name = "resource-name",
        uriTemplate = StrimziConstants.ResourceUris.RESOURCE_PATTERN,
        description = "What this resource exposes.",
        mimeType = "application/json"
    )
    String getResource(final String namespace, final String name) {
        // Fetch and serialize resource data
    }
}
```

- URI templates use `StrimziConstants.ResourceUris.*` constants — never hardcode URI strings
- Return serialized JSON strings
- Resource names use `kebab-case`: `kafka-cluster-status`, `kafka-cluster-topology`

## MCP Completion Pattern

Completions provide IDE-like autocomplete for prompt and resource template parameters
by querying Kubernetes in real-time.

```java
@Singleton
public class XxxCompletions {

    @Inject
    CompletionService completionService;

    @CompletePrompt("prompt-name")
    CompletionResponse completePrompt(final CompleteContext context) {
        return completionService.completeByArgName(context);
    }

    @CompleteResourceTemplate("resource-name")
    CompletionResponse completeResource(final CompleteContext context) {
        return completionService.completeByArgName(context);
    }
}
```

- Use `CompletionService` (strimzi) for domain-specific completions
- Use `CompletionHelper` (common) for generic Kubernetes completions (namespaces, etc.)

## Resource Subscription Pattern

`ResourceSubscriptionManager` establishes Kubernetes watches on startup and sends
`notifications/resources/updated` to subscribed MCP clients when resource state changes.

- Watches Kafka CRs, KafkaNodePool CRs, KafkaTopic CRs, KafkaUser CRs, and operator Deployments
- De-duplicates by comparing serialized JSON against last known state
- Started/stopped via `@Observes StartupEvent` / `@Observes ShutdownEvent`
- Auto-reconnects with exponential backoff when watches close unexpectedly
- Periodic reconciliation removes orphaned state for deleted resources
- Can be disabled via `mcp.resource-watches.enabled=false` (used in tests)
- Configuration:
  - `mcp.watch.reconnect-initial-delay-ms` (default: 1000) — initial reconnect delay
  - `mcp.watch.reconnect-max-delay-ms` (default: 60000) — maximum reconnect delay
  - `mcp.watch.reconnect-max-attempts` (default: 10) — maximum reconnect attempts before giving up
  - `mcp.watch.reconcile-interval` (default: 5m) — interval for orphaned state cleanup

## Domain Service Pattern

```java
@ApplicationScoped
public class XxxService {

    @Inject
    KubernetesResourceService k8sService;

    XxxService() {  // package-private no-arg constructor for CDI
    }

    public List<XxxResponse> listItems(final String namespace, final String clusterName) {
        String ns = InputUtils.normalizeInput(namespace);
        String name = InputUtils.normalizeInput(clusterName);

        if (name == null) {
            throw new ToolCallException("Cluster name is required");
        }

        List<Resource> resources;
        if (ns != null) {
            resources = k8sService.queryResourcesByLabel(Resource.class, ns, LABEL_KEY, name);
        } else {
            resources = k8sService.queryResourcesByLabelInAnyNamespace(Resource.class, LABEL_KEY, name);
        }

        return resources.stream()
            .map(this::createResponse)
            .toList();
    }
}
```

### Namespace handling

Every tool accepts an optional namespace. The service method receives it first, normalizes it,
then branches: if non-null query that namespace, if null query all namespaces (auto-discovery).
Use `InputUtils.normalizeInput()` for all user-supplied strings (namespace, cluster name, etc.).

### Error handling

- Validation errors (missing required params): `throw new ToolCallException("message")`
- Resource not found: `throw new ToolCallException("X 'name' not found")`
- Empty list results: return empty list (not an error)
- Infrastructure/API errors: let propagate, `@WrapBusinessError` converts to MCP error
- Never return error objects; always throw or return typed responses

## DTO Pattern

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public record XxxResponse(
    @JsonProperty("name") String name,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("status") String status
) {
    public static XxxResponse of(String name, String namespace, String status) {
        return new XxxResponse(name, namespace, status);
    }
}
```

- Use `@JsonProperty` with snake_case names on every field
- Use `@JsonInclude(JsonInclude.Include.NON_NULL)` to omit null fields
- Provide static factory methods (`of()`, `empty()`) for construction
- Record javadoc uses `@param` tags for each component

## Constants

Constants are split across two modules:

- **`KubernetesConstants`** (common) - standard Kubernetes labels, conditions, phases, container states,
  resource status, health status, and the generic `UNKNOWN` fallback value
- **`StrimziConstants`** (strimzi-mcp) - Strimzi-specific label keys, kind values, component types,
  and operator discovery values

### Use Strimzi API constants where available

- Label keys: use `ResourceLabels.STRIMZI_CLUSTER_LABEL`, `ResourceLabels.STRIMZI_KIND_LABEL`,
  `ResourceLabels.STRIMZI_COMPONENT_TYPE_LABEL` from `io.strimzi.api.ResourceLabels`
- Node pool roles: use `ProcessRoles.BROKER.toValue()` from `io.strimzi.api.kafka.model.nodepool.ProcessRoles`
- Listener types: use `KafkaListenerType.INTERNAL.toValue()` etc. from
  `io.strimzi.api.kafka.model.kafka.listener.KafkaListenerType`

### Custom constants (no API equivalent)

- `StrimziConstants.Labels.POOL_NAME` - `"strimzi.io/pool-name"` (not in ResourceLabels)
- `StrimziConstants.KindValues.CLUSTER_OPERATOR` - `"cluster-operator"` (label value, no API constant)
- `StrimziConstants.ComponentTypes.KAFKA` - `"kafka"` (label value, no API constant)
- `StrimziConstants.Operator.APP_LABEL_VALUE` - `"strimzi"` (label value, no API constant)
- `StrimziConstants.ResourceUris.*` - MCP resource URI templates and builders (e.g., `KAFKA_STATUS`,
  `kafkaStatus(namespace, name)`). Used in `@ResourceTemplate` annotations and `ResourceSubscriptionManager`.
  All URI patterns are defined once here — never hardcode URI strings elsewhere.
- `KubernetesConstants.*` - standard Kubernetes strings not provided by Fabric8 as constants

Before adding a new constant, check if it already exists in `ResourceLabels`, `ProcessRoles`,
`KafkaListenerType`, or other Strimzi API classes.

## Code Style

### Enforced by checkstyle (runs at compile)

- License header on every Java file (see `.checkstyle/checkstyle.xml`)
- No star imports
- No tabs
- Import order: third-party, javax, java, static (alphabetical within groups, blank line between)
- All public types, methods, and fields require Javadoc
- Locale-sensitive methods must specify locale: `.toLowerCase(Locale.ROOT)` not `.toLowerCase()`
- Max method length: 150 lines, max parameters: 13, max cyclomatic complexity: 19

### Conventions

- Use `final` on method parameters
- Use `if/else` over multi-line ternary operators. Single-line ternaries for simple expressions are fine.
- Package-private no-arg constructors on CDI beans (not private, not public)
- Use `InputUtils.normalizeInput()` for all user-supplied input
- Prefer `List.of()` for empty immutable lists
- Use `LOG.infof()` / `LOG.debugf()` (JBoss logging with format strings)
- No `assert` statements (checkstyle enforced)
- Every file must end with a newline (trailing newline). Verify before committing.

### Javadoc

- Required on all public classes, methods, fields, and record components
- Class-level: brief description of purpose
- Method-level: describe what it does, `@param` for each parameter, `@return` for return value
- Record-level: describe the record, `@param` for each component
- Keep descriptions concise, start with a verb or noun (not "This method...")
- Use `{@code ...}` for inline code references
- Non-empty `@param`/`@return`/`@throws` descriptions (checkstyle enforced)

## Adding a New Strimzi Tool

1. Create or update the DTO record in `strimzi-mcp/src/.../strimzi/dto/`
2. Add the service method to the appropriate domain service in `strimzi-mcp/src/.../strimzi/service/`
3. Add the `@Tool` method to the corresponding tools class in `strimzi-mcp/src/.../strimzi/tool/`
   - Include `@MetaField` annotations for `type` (from `ToolMetaFields`) and `resource` (from `StrimziToolResources`)
   - Add `@MetaField(name = ToolMetaFields.COMPOSITE, value = "true", type = MetaField.Type.BOOLEAN)` for composite tools
4. Add the tool name to `McpDiscoveryTest.testToolDiscovery()` expected list
5. Add expected metadata to `McpDiscoveryTest.testToolMetadata()` maps
6. Run `./mvnw compile` to verify checkstyle + compilation
7. Run `./mvnw test` to verify tests pass

## Adding a New Module

1. Create a new directory at the repo root (e.g., `new-module/`)
2. Add `pom.xml` with parent reference to `streamshub-mcp`
3. Depend on `streamshub-mcp-common` for shared Kubernetes helpers
4. Add module to parent `pom.xml` `<modules>` list
5. Add `META-INF/beans.xml` to `src/main/resources/` for CDI bean discovery

## Testing

Unit tests use Quarkus test framework with Mockito for Kubernetes client mocking.
System tests use kubetest4j against a real Kubernetes cluster and are skipped by default.

For detailed testing documentation including test locations, patterns, system test configuration, and the systemtest module structure, see [dev/docs/TESTING.md](dev/docs/TESTING.md).

### E2E (system test) best practices

System tests live in `systemtest/src/test/java/io/streamshub/mcp/systemtest/` and extend `AbstractST`.
Every test calls MCP tools via `McpAssured.McpStreamableTestClient` and validates the JSON responses.

#### Always validate response content, not just success/error status

Never write a tool call assertion that only checks `!isError()`. Every tool call must parse the
JSON response and verify at least the key fields. A test that only checks `!isError()` will pass
even if the tool returns garbage data.

```java
// BAD — passes even if response is empty or contains wrong data
mcpClient.when()
    .toolsCall("get_kafka_cluster", args, response -> {
        assertFalse(response.isError(), "should not error");
    })
    .thenAssertResults();

// GOOD — verifies the response contains the expected resource
mcpClient.when()
    .toolsCall("get_kafka_cluster", args, response -> {
        JsonNode root = assertToolSuccess(response);
        LOGGER.info("get_kafka_cluster response:\n{}", response.content().getFirst().asText().text());
        assertEquals(CLUSTER_NAME, root.path("name").asText(), "Cluster name should match");
        assertEquals("Ready", root.path("readiness").asText(), "Cluster should be Ready");
        assertEquals(ns, root.path("namespace").asText(), "Namespace should match");
    })
    .thenAssertResults();
```

#### Use AbstractST assertion helpers

`AbstractST` provides shared helpers — use them instead of repeating the same checks:

- **`assertToolSuccess(response)`** — asserts `!isError()`, `!content.isEmpty()`, parses JSON. Use instead of manual `assertFalse(response.isError())` + `parseJson()`.
- **`assertToolError(response, "not found")`** — asserts `isError()` and error message contains substrings (case-insensitive). Use for all error test cases.
- **`assertDiagnosticReport(root)`** — validates `steps_completed` is non-empty array, `timestamp` and `message` present. Use for all `diagnose_*` and `assess_*` tool responses.
- **`assertPodSummaryResponse(root)`** — validates `total_pods > 0`, `ready_pods >= 0`, `health_status` and `timestamp` present. Use for all `get_*_pods` tool responses.
- **`assertLogsResponse(root, "cluster_name", expectedName)`** — validates resource name field, `pods` array, `log_lines`, `timestamp`, `message`. Use for all `get_*_logs` tool responses.
- **`assertEventsResponse(root, resourceName, namespace)`** — validates `resource_name`, `namespace`, `total_events >= 0`, `timestamp`, `message`. Use for all `get_strimzi_events` tool responses.

#### Always verify resource identity in responses

Check that the response contains the correct resource name and namespace — not just that fields exist:

```java
// BAD — only checks field existence
assertFalse(root.path("name").isMissingNode(), "Should have name");

// GOOD — verifies the actual value
assertEquals(EXPECTED_NAME, root.path("name").asText(), "Name should match");
assertEquals(expectedNamespace, root.path("namespace").asText(), "Namespace should match");
```

#### Verify counts in list responses

When the test deploys a known number of resources, verify the response count:

```java
assertEquals(3, nodePools.size(), "Should have 3 node pools (controller-np, broker-np1, broker-np2)");
assertTrue(items.size() >= 3, "Should return at least 3 topics");
```

#### Always log tool responses

Every tool call must log its response for debugging CI failures. Use shortened logging for
large responses (logs, metrics):

```java
// Normal responses — log full content
LOGGER.info("get_kafka_cluster response:\n{}", response.content().getFirst().asText().text());

// Large responses (logs, metrics) — log length only
LOGGER.info("get_kafka_cluster_logs response (length={})",
    response.content().getFirst().asText().text().length());
```

#### Validate error responses properly

Error test cases must verify the error message content, not just that `isError()` is true:

```java
// BAD — passes even if the error message is "NullPointerException"
assertTrue(response.isError(), "Should error");

// GOOD — verifies the error message is user-friendly
assertToolError(response, "not found");
```

#### Parse JSON arrays properly

Don't use `.toString().contains("value")` to check array contents. Iterate the array:

```java
// BAD
assertTrue(pool.path("roles").toString().contains("controller"));

// GOOD
boolean found = false;
for (JsonNode role : pool.path("roles")) {
    if ("controller".equals(role.asText())) { found = true; break; }
}
assertTrue(found, "roles should contain 'controller'");
```
