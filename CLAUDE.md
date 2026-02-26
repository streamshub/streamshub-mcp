# Project: Strimzi MCP Server

Quarkus application providing Strimzi Kafka management tools via MCP (Model Context Protocol).
Java 21, Quarkus 3.x, Strimzi API 0.50.x, Fabric8 Kubernetes client.

## Build & Test

```bash
mvn compile          # compile + checkstyle
mvn test             # unit tests (no live cluster needed)
mvn quarkus:dev      # dev mode on http://localhost:8080/mcp
```

Checkstyle runs during compile phase. Fix all violations before committing.

## Architecture

```
tool/           → MCP tool definitions (thin wrappers, no logic)
service/domain/ → Business logic (Kafka, Topic, NodePool, Operator)
service/common/ → Generic Kubernetes operations (resource queries, pods, deployments)
dto/            → Response records (JSON output)
config/         → Constants, shared tool descriptions
util/           → Input normalization utilities
```

### Layer rules

- **Tools** call domain services and return typed responses. No try/catch, no business logic.
- **Domain services** contain all business logic. Throw `ToolCallException` for errors.
- **Common services** are generic Kubernetes helpers shared across domain services.
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

`Constants.java` is organized into `Kubernetes` and `Strimzi` inner classes.

### Use Strimzi API constants where available

- Label keys: use `ResourceLabels.STRIMZI_CLUSTER_LABEL`, `ResourceLabels.STRIMZI_KIND_LABEL`,
  `ResourceLabels.STRIMZI_COMPONENT_TYPE_LABEL` from `io.strimzi.api.ResourceLabels`
- Node pool roles: use `ProcessRoles.BROKER.toValue()` from `io.strimzi.api.kafka.model.nodepool.ProcessRoles`
- Listener types: use `KafkaListenerType.INTERNAL.toValue()` etc. from
  `io.strimzi.api.kafka.model.kafka.listener.KafkaListenerType`

### Custom constants (no API equivalent)

- `Constants.Strimzi.Labels.POOL_NAME` - `"strimzi.io/pool-name"` (not in ResourceLabels)
- `Constants.Strimzi.KindValues.CLUSTER_OPERATOR` - `"cluster-operator"` (label value, no API constant)
- `Constants.Strimzi.ComponentTypes.KAFKA` - `"kafka"` (label value, no API constant)
- `Constants.Strimzi.Operator.APP_LABEL_VALUE` - `"strimzi"` (label value, no API constant)
- `Constants.Kubernetes.*` - standard Kubernetes strings (labels, conditions, phases, container states,
  resource status, health status) not provided by Fabric8 as constants

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

### Javadoc

- Required on all public classes, methods, fields, and record components
- Class-level: brief description of purpose
- Method-level: describe what it does, `@param` for each parameter, `@return` for return value
- Record-level: describe the record, `@param` for each component
- Keep descriptions concise, start with a verb or noun (not "This method...")
- Use `{@code ...}` for inline code references
- Non-empty `@param`/`@return`/`@throws` descriptions (checkstyle enforced)

## Adding a New Tool

1. Create or update the DTO record in `dto/`
2. Add the service method to the appropriate domain service in `service/domain/`
3. Add the `@Tool` method to the corresponding tools class in `tool/`
4. Run `mvn compile` to verify checkstyle + compilation
5. Run `mvn test` to verify tests pass

## Testing

Tests use Quarkus test framework with Mockito for Kubernetes client mocking.
Test file: `src/test/java/io/streamshub/mcp/service/StrimziServiceTest.java`.
Tests verify service behavior without a live Kubernetes cluster.
