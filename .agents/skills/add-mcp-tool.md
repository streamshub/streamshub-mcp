Add a new MCP tool to the StreamsHub MCP project following the established patterns.

$ARGUMENTS

---

## Step 1 — Design the DTO

Create or update the response record in the appropriate domain sub-package under
`strimzi-mcp/src/main/java/io/streamshub/mcp/strimzi/dto/` (e.g., `dto/kafka/`, `dto/kafkatopic/`,
`dto/kafkauser/`, `dto/kafkaconnect/`, `dto/kafkamirrormaker2/`, `dto/kafkabridge/`, `dto/operator/`, `dto/metrics/`):

```java
/**
 * Response for the <resource> tool.
 *
 * @param name the resource name
 * @param namespace the Kubernetes namespace
 * @param status the resource status
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record XxxResponse(
    @JsonProperty("name") String name,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("status") String status
) {
    /**
     * Creates a new response.
     *
     * @param name the resource name
     * @param namespace the namespace
     * @param status the status
     * @return new response instance
     */
    public static XxxResponse of(final String name, final String namespace, final String status) {
        return new XxxResponse(name, namespace, status);
    }
}
```

Rules:
- Use `@JsonProperty("snake_case")` on every field
- Use `@JsonInclude(JsonInclude.Include.NON_NULL)`
- Provide `of()` and optionally `empty()` static factory methods
- Add Javadoc with `@param` for each component

---

## Step 2 — Implement the Service Method

Add the business logic to the appropriate domain service in `strimzi-mcp/src/main/java/io/streamshub/mcp/strimzi/service/`
(e.g., `service/kafka/`, `service/kafkatopic/`, `service/kafkauser/`, `service/kafkaconnect/`,
`service/kafkamirrormaker2/`, `service/kafkabridge/`, `service/operator/`, `service/metrics/`):

```java
/**
 * Retrieves the resource by name.
 *
 * @param namespace the namespace (null for all namespaces)
 * @param name the resource name
 * @return the response
 */
public XxxResponse getItem(final String namespace, final String name) {
    String ns = InputUtils.normalizeInput(namespace);
    String normalizedName = InputUtils.normalizeInput(name);

    if (normalizedName == null) {
        throw new ToolCallException("Resource name is required");
    }

    // Query Kubernetes API
    // Build and return typed response
}
```

Rules:
- Normalize all user input with `InputUtils.normalizeInput()`
- Null namespace means query all namespaces
- Throw `ToolCallException` for validation errors and not-found
- Return empty list for zero results (not an error)
- Use JBoss logging: `LOG.infof("Found %d items", count)`

---

## Step 3 — Create the Tool Method

Add to the appropriate tools class in the domain sub-package under `strimzi-mcp/src/main/java/io/streamshub/mcp/strimzi/tool/`
(e.g., `tool/kafka/`, `tool/kafkatopic/`, `tool/kafkaconnect/`, `tool/kafkamirrormaker2/`,
`tool/kafkabridge/`, `tool/operator/`, `tool/metrics/`, `tool/diagnostic/`):

```java
@WithSpan("tool.verb_noun")
@MetaField(name = ToolMetaFields.TYPE, value = ToolMetaFields.Types.GET)
@MetaField(name = ToolMetaFields.RESOURCE, value = StrimziToolResources.XXX)
@Tool(
    name = "verb_noun",
    description = "Returns details of a specific resource."
        + " Includes name, namespace, and status.",
    annotations = @Tool.Annotations(
        readOnlyHint = true,
        destructiveHint = false,
        idempotentHint = true,
        openWorldHint = false
    )
)
public XxxResponse verbNoun(
    @ToolArg(description = StrimziToolsPrompts.XXX_DESC) final String name,
    @ToolArg(description = StrimziToolsPrompts.NS_DESC, required = false) final String namespace
) {
    return xxxService.getItem(namespace, name);
}
```

Rules:
- `@WithSpan` span name must match `@Tool(name = ...)`
- Include `@MetaField` for type and resource
- `@Tool.Annotations` is nested inside `@Tool(... annotations = @Tool.Annotations(...))`
- Tool description: 1-2 sentences, no filler, state what it returns
- Tool name: `snake_case`, `verb_resource` pattern
- Namespace is always `@ToolArg(required = false)`
- Use `StrimziToolsPrompts` constants for parameter descriptions (resource-specific: `CLUSTER_DESC`, `CONNECTOR_NAME_DESC`, `BRIDGE_NAME_DESC`, `MIRROR_MAKER_NAME_DESC`, `USER_NAME_DESC`, `REBALANCE_NAME_DESC`, `DRAIN_CLEANER_NAME_DESC`, `OPERATOR_NAME_DESC`)
- Tool class must have `@Singleton`, `@Guarded`, and `@WrapBusinessError` on the class

Available `StrimziToolResources` constants: `KAFKA`, `KAFKA_TOPIC`, `KAFKA_USER`, `KAFKA_NODE_POOL`,
`KAFKA_REBALANCE`, `KAFKA_CONNECT`, `KAFKA_CONNECTOR`, `KAFKA_BRIDGE`, `KAFKA_MIRROR_MAKER_2`,
`STRIMZI_OPERATOR`, `STRIMZI_EVENT`, `DRAIN_CLEANER`.

Available `ToolMetaFields.Types` constants: `LIST`, `GET`, `OVERVIEW`, `LOGS`, `EVENTS`, `METRICS`,
`DIAGNOSE`, `COMPARE`, `ASSESS`, `CHECK`.

---

## Step 4 — Register in McpDiscoveryTest

Edit `strimzi-mcp/src/test/java/io/streamshub/mcp/strimzi/tool/McpDiscoveryTest.java`:

1. Add tool name to `testToolDiscovery()` expected list:
   ```java
   List<String> expectedTools = List.of(
       // ... existing tools ...
       "verb_noun"
   );
   ```

2. Add metadata to `expectedToolMetadata()` map:
   ```java
   map.put("verb_noun", new String[]{ToolMetaFields.Types.GET, StrimziToolResources.XXX});
   ```

3. If the tool is composite, add to `compositeToolNames()`:
   ```java
   return Set.of(
       // ... existing composite tools ...
       "verb_noun"
   );
   ```

---

## Step 5 — Write Tests

### Unit test for the service
Create `strimzi-mcp/src/test/java/.../service/XxxServiceTest.java`:
- Mock `KubernetesResourceService` interactions
- Test with namespace and without (null namespace)
- Test not-found case throws `ToolCallException`
- Test empty list returns empty (not error)
- Use `@QuarkusTest` annotation
- Name tests: `testDescriptiveName` in camelCase (e.g., `testListItemsReturnsEmptyWhenNoneExist`)

### Unit test for the tool (if logic in tool method)
Usually unnecessary since tools are thin wrappers, but add if there is parameter transformation.

---

## Step 6 — Build and Verify

```bash
./mvnw compile    # Checkstyle + compilation
./mvnw test       # All tests including McpDiscoveryTest
```

---

## Step 7 — Update Documentation

1. **`docs/strimzi-mcp/tools/`** — add tool to the appropriate docs page
2. **`CHANGELOG.md`** — add entry under `## [Unreleased]` > `### Added`
3. **`AGENTS.md`** — update if new patterns or module structure changes
4. **`dev/test-plans/strimzi-mcp-tool-test-plan.md`** — add test case for the new tool
