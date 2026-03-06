# Project: StreamsHub MCP (Multi-Module)

Multi-module Quarkus mono-repo providing MCP (Model Context Protocol) servers for Kubernetes-based
streaming platforms. Java 21, Quarkus 3.x, Strimzi API 0.50.x, Fabric8 Kubernetes client.

## Modules

- **`common`** (`streamshub-mcp-common`) - Generic Kubernetes helpers, DTOs, and utilities shared across modules
- **`strimzi-mcp`** (`strimzi-mcp`) - Strimzi Kafka management MCP tools and services

## Build & Test

```bash
mvn compile                      # compile all modules + checkstyle
mvn test                         # unit tests (no live cluster needed)
mvn quarkus:dev -pl strimzi-mcp  # dev mode on http://localhost:8080/mcp
```

Checkstyle runs during compile phase. Fix all violations before committing.

## Architecture

### Common module (`common/`)

```
io.streamshub.mcp.common.
├── config/    → KubernetesConstants (labels, conditions, phases, health status)
├── dto/       → PodSummaryResponse (generic pod DTO)
├── service/   → KubernetesResourceService, PodsService, DeploymentService
└── util/      → InputUtils
```

### Strimzi module (`strimzi-mcp/`)

```
io.streamshub.mcp.strimzi.
├── tool/      → MCP tool definitions (thin wrappers, no logic)
├── service/   → Business logic (KafkaService, KafkaTopicService, KafkaNodePoolService, StrimziOperatorService)
├── dto/       → Strimzi response records (7 DTOs)
└── config/    → StrimziConstants, StrimziToolsPrompts
```

### Layer rules

- **Tools** call domain services and return typed responses. No try/catch, no business logic.
- **Domain services** (strimzi) contain all business logic. Throw `ToolCallException` for errors.
- **Common services** are generic Kubernetes helpers shared across modules.
- **DTOs** are immutable `record` types. Use static factory methods (`of()`, `empty()`) not constructors directly.

## MCP Tool Pattern

```java
@Singleton
@WrapBusinessError(Exception.class)
public class XxxTools {

    @Inject
    XxxService xxxService;

    XxxTools() {  // package-private no-arg constructor for CDI
    }

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

### Critical: No @Tool.Annotations

Do NOT use `annotations = @Tool.Annotations(...)` on any `@Tool`. This causes Claude Code
to silently drop all MCP tools during discovery. Not even empty `@Tool.Annotations()` works.

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
4. Run `mvn compile` to verify checkstyle + compilation
5. Run `mvn test` to verify tests pass

## Adding a New Module

1. Create a new directory at the repo root (e.g., `new-module/`)
2. Add `pom.xml` with parent reference to `streamshub-mcp`
3. Depend on `streamshub-mcp-common` for shared Kubernetes helpers
4. Add module to parent `pom.xml` `<modules>` list
5. Add `META-INF/beans.xml` to `src/main/resources/` for CDI bean discovery

## Testing

Tests use Quarkus test framework with Mockito for Kubernetes client mocking.
Test file: `strimzi-mcp/src/test/java/io/streamshub/mcp/strimzi/service/StrimziServiceTest.java`.
Tests verify service behavior without a live Kubernetes cluster.
