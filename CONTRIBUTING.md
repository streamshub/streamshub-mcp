# Contributing

We welcome contributions to StreamsHub MCP! This guide covers the contribution process, code style, and best practices.

## Getting Started

1. **Fork the repository** and create a feature branch
2. **Set up your development environment** — See [Building Guide](docs/developer/building.md)
3. **Make your changes** following the code style guidelines
4. **Write tests** for new functionality
5. **Run the build** to verify everything works
6. **Commit your changes** with clear commit messages
7. **Push to your fork** and open a pull request

## Development Workflow

### 1. Create a Feature Branch

```bash
git checkout -b feature/my-new-feature
```

Use descriptive branch names:
- `feature/add-kafka-metrics-tool`
- `fix/namespace-discovery-bug`
- `docs/update-installation-guide`

### 2. Make Your Changes

Follow the [code style guidelines](#code-style) and [architecture patterns](docs/developer/architecture.md).

### 3. Write Tests

Every public method needs a test. See [Testing Guide](docs/developer/testing.md) for details.

```bash
# Run tests
mvn test

# Run tests with coverage
mvn test jacoco:report
```

### 4. Run the Build

```bash
# Compile and run checkstyle
mvn clean compile

# Run all tests
mvn test

# Full build
mvn clean package
```

### 5. Commit Your Changes

Use clear, descriptive commit messages:

```
Add support for KafkaTopic metrics collection

- Implement topic-level metrics queries
- Add tests for metrics aggregation
- Update documentation
```

**Commit message format**:
- First line: Brief summary (50 chars or less)
- Blank line
- Detailed description with bullet points

### 6. Push and Create Pull Request

```bash
git push origin feature/my-new-feature
```

Open a pull request on GitHub with:
- Clear description of changes
- Reference to related issues
- Screenshots/examples if applicable

## Code Style

### Enforced by Checkstyle

The project uses checkstyle to enforce coding standards (runs during compile):

```bash
# Run checkstyle explicitly
mvn checkstyle:check

# Generate checkstyle report
mvn checkstyle:checkstyle
open target/site/checkstyle.html
```

**Key rules**:
- License header on every Java file
- No star imports
- No tabs (use 4 spaces)
- Import order: third-party, javax, java, static (alphabetical, blank lines between)
- All public types, methods, and fields require Javadoc
- Locale-sensitive methods must specify locale: `.toLowerCase(Locale.ROOT)`
- Max method length: 150 lines
- Max parameters: 13
- Max cyclomatic complexity: 19

### Code Conventions

- **Use `final` on method parameters**
- **Use `if/else` over multi-line ternary operators** (single-line ternaries for simple expressions are fine)
- **Package-private no-arg constructors on CDI beans** (not private, not public)
- **Use `InputUtils.normalizeInput()` for all user-supplied input**
- **Prefer `List.of()` for empty immutable lists**
- **Use `LOG.infof()` / `LOG.debugf()`** (JBoss logging with format strings)
- **No `assert` statements** (checkstyle enforced)
- **Every file must end with a newline** (trailing newline)

### Formatting

- **Indentation**: 4 spaces (never tabs)
- **Line length**: Maximum 120 characters
- **Braces**: Always use braces for if/else, even for single statements
- **Naming**: Use meaningful variable names (no single letters except loop counters)

### Javadoc

Required on all public classes, methods, fields, and record components:

```java
/**
 * Brief description of the class.
 * 
 * <p>Additional details if needed.
 */
public class MyService {
    
    /**
     * Brief description of what the method does.
     * 
     * @param namespace the Kubernetes namespace
     * @param name the resource name
     * @return the resource status
     * @throws ToolCallException if the resource is not found
     */
    public ResourceStatus getStatus(final String namespace, final String name) {
        // Implementation
    }
}
```

**Guidelines**:
- Keep descriptions concise
- Start with a verb or noun (not "This method...")
- Use `{@code ...}` for inline code references
- Non-empty `@param`/`@return`/`@throws` descriptions

## Architecture Patterns

### MCP Tool Pattern

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

**Critical**: Do NOT use `annotations = @Tool.Annotations(...)` on any `@Tool`. This causes Claude Code to silently drop all MCP tools during discovery.

### Domain Service Pattern

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

        // Implementation
    }
}
```

### DTO Pattern

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

See [AGENTS.md](AGENTS.md) for complete architecture patterns and rules.

## Pull Request Guidelines

### Before Submitting

- [ ] All tests pass (`mvn test`)
- [ ] Checkstyle passes (`mvn compile`)
- [ ] Code is documented (Javadoc on public APIs)
- [ ] Changes are covered by tests
- [ ] Commit messages are clear and descriptive

### PR Description

Provide a clear description:

```markdown
## Description
Brief summary of changes

## Changes
- Added X feature
- Fixed Y bug
- Updated Z documentation

## Testing
- Unit tests added for new functionality
- Manually tested with Claude Desktop
- System tests pass

## Related Issues
Fixes #123
```

### Review Process

All contributions go through code review. Reviewers will check:
- Code quality and style compliance
- Test coverage
- Documentation updates
- Security considerations
- Architecture alignment

## Adding New Features

### Adding a New MCP Tool

1. Create or update the DTO record in `strimzi-mcp/src/.../strimzi/dto/`
2. Add the service method to the appropriate domain service in `strimzi-mcp/src/.../strimzi/service/`
3. Add the `@Tool` method to the corresponding tools class in `strimzi-mcp/src/.../strimzi/tool/`
4. Add the tool name to `McpDiscoveryTest.testToolDiscovery()` expected list
5. Run `mvn compile` to verify checkstyle + compilation
6. Run `mvn test` to verify tests pass

### Adding a New Module

1. Create a new directory at the repo root (e.g., `new-module/`)
2. Add `pom.xml` with parent reference to `streamshub-mcp`
3. Depend on `streamshub-mcp-common` for shared Kubernetes helpers
4. Add module to parent `pom.xml` `<modules>` list
5. Add `META-INF/beans.xml` to `src/main/resources/` for CDI bean discovery

## Getting Help

- **[GitHub Issues](https://github.com/streamshub/streamshub-mcp/issues)** — Report bugs or request features
- **[GitHub Discussions](https://github.com/streamshub/streamshub-mcp/discussions)** — Ask questions and share ideas
- **Pull Request Comments** — Ask reviewers for clarification

## Code of Conduct

Be respectful and constructive in all interactions. We're building this together.

## License

By contributing, you agree that your contributions will be licensed under the [Apache License 2.0](LICENSE).

## Next Steps

- **[Building](docs/developer/building.md)** — Set up your development environment
- **[Testing](docs/developer/testing.md)** — Write and run tests
- **[Architecture](docs/developer/architecture.md)** — Understand the system design