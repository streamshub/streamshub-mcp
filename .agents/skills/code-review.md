Review code changes for quality, architecture, style, and security.
Works with a GitHub PR, a local diff (`git diff`), or the current working-tree changes.

If no diff, PR, or changed files are apparent, run `git diff HEAD` to review uncommitted changes.
If the working tree is clean, ask the user what to review. Work through every applicable check below,
skipping sections whose category is not present. For each finding, record:
- **Severity**: `critical` / `major` / `minor` / `nit`
- **File + line**
- **What** the issue is and **why** it matters
- **Suggestion** (concrete fix, not vague advice)

$ARGUMENTS

---

## Step 1 — Categorise the Changes

Scan the changed files and classify into categories. This guides which checks apply.

| Category | Indicators |
|---|---|
| **Tool** | Changes in `tool/` sub-packages (kafka, kafkatopic, kafkauser, kafkaconnect, kafkabridge, kafkamirrormaker2, kafkanodepool, kafkarebalance, draincleaner, operator, metrics, diagnostic) |
| **Service** | Changes in `service/` sub-packages |
| **DTO** | Changes in `dto/` sub-packages |
| **Config / Constants** | Changes to `*Constants`, `*Config`, `*Categories`, `*Resources` classes |
| **Prompt / Resource** | Changes in `prompt/` or `resource/` packages |
| **Common module** | Changes under `common/` |
| **Metrics** | Changes to metrics services, DTOs, or category constants |
| **Diagnostic** | Changes to diagnostic services or composite tool methods |
| **Tests** | Changes under `src/test/` or `systemtest/` |
| **Docs / Config** | Changes to `*.md`, `*.yaml`, `*.properties`, `pom.xml` |

---

## Step 2 — Apply the Review Checklist

### 2.1 — Architecture & Layer Rules (all PRs)

- [ ] Tools are thin wrappers — no business logic, no try/catch inside tool methods.
- [ ] Services contain all logic and throw `ToolCallException` for user-facing errors.
- [ ] DTOs are immutable `record` types with `@JsonProperty` + `@JsonInclude(NON_NULL)`.
- [ ] DTO construction uses static factory methods (`of()`, `empty()`) — not `new` directly in callers.
- [ ] Metrics queries go through `MetricsQueryService`, never via direct `MetricsProvider` injection in domain services.
- [ ] Diagnostic services extend `BaseDiagnosticService`; all tool classes use `@Guarded`.
- [ ] Resource templates use `StrimziConstants.ResourceUris.*` — no hardcoded URI strings.
- [ ] Completions delegate to `CompletionService` or `CompletionHelper` — no logic inline.

### 2.2 — Tool Pattern (category: Tool)

- [ ] Class annotated `@Singleton`, `@WrapBusinessError(value = Exception.class, unless = ToolCallException.class)`.
- [ ] Package-private no-arg constructor present for CDI.
- [ ] Every tool method has `@WithSpan("tool.<tool_name>")` matching the `@Tool(name = ...)` value.
- [ ] Every tool method has `@MetaField` annotations for `type` (from `ToolMetaFields`) and `resource` (from `StrimziToolResources`).
- [ ] Composite tools have `@MetaField(name = ToolMetaFields.COMPOSITE, value = "true", type = MetaField.Type.BOOLEAN)`.
- [ ] Tool annotations nested: `@Tool(... annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))`.
- [ ] Tool description: 1-2 sentences, no filler phrases ("Perfect for...", "Use this to..."), states what it returns.
- [ ] Tool name is `snake_case` following the `verb_resource` pattern.
- [ ] Namespace parameter is `@ToolArg(required = false)`.
- [ ] Shared parameter descriptions use `StrimziToolsPrompts` constants.
- [ ] New tool name added to `McpDiscoveryTest.testToolDiscovery()` (in `strimzi-mcp/src/test/.../tool/`).
- [ ] New tool metadata added to `McpDiscoveryTest.testToolMetadata()` and composite tools to `compositeToolNames()`.

### 2.3 — Service Pattern (category: Service)

- [ ] Class annotated `@ApplicationScoped`; package-private no-arg constructor present for CDI.
- [ ] Dependencies injected via `@Inject` (field injection).
- [ ] All user-supplied strings pass through `InputUtils.normalizeInput()` before use.
- [ ] Null namespace -> query all namespaces; non-null -> query that namespace.
- [ ] Required parameters validated early; throw `ToolCallException` with a clear message on failure.
- [ ] Empty list results returned as empty list (not an error).
- [ ] Never return error objects — always throw or return typed responses.
- [ ] No exceptions swallowed — no empty catch blocks or `catch (Exception e) { LOG.warn(...) }`. Let infrastructure exceptions propagate to `@WrapBusinessError`.
- [ ] Logging uses JBoss format: `LOG.infof()` / `LOG.debugf()` — no string concatenation.
- [ ] Log messages use specific resource names: "Kafka cluster", "KafkaTopic", "Strimzi operator" (not generic "cluster", "topic").

### 2.4 — Diagnostic Service (category: Diagnostic)

- [ ] Extends `BaseDiagnosticService` (provides `objectMapper`, `triageMaxTokens`, `analysisMaxTokens`, `defaultTailLines`).
- [ ] Three-phase workflow: initial gather -> deep investigation (Sampling) -> analysis (Sampling).
- [ ] Namespace ambiguity resolved via `NamespaceElicitationHelper.elicitNamespace()`.
- [ ] Individual step failures recorded in `stepsFailed`; workflow continues.
- [ ] Progress reported via `DiagnosticHelper.sendProgress()`.
- [ ] Cancellation checked via `DiagnosticHelper.checkCancellation()` between steps.
- [ ] `@WithSpan` on gather/triage/analysis methods with pattern `diagnose.{domain}.{step}`.
- [ ] Gather/triage/analysis methods are package-private (not private) for ArC interception.
- [ ] No duplication — calls existing domain services rather than re-implementing Kubernetes queries.

### 2.5 — DTO Correctness (category: DTO)

- [ ] Every field annotated with `@JsonProperty("snake_case_name")`.
- [ ] Class annotated `@JsonInclude(JsonInclude.Include.NON_NULL)`.
- [ ] Static factory `of(...)` and/or `empty()` methods present.
- [ ] Javadoc on the record type with `@param` for every component.

### 2.6 — Constants & Configuration (category: Config / Constants)

- [ ] No hardcoded Strimzi label strings — use `ResourceLabels.*`, `ProcessRoles.*`, `KafkaListenerType.*` from Strimzi API where available.
- [ ] Custom constants live in `StrimziConstants` (Strimzi-specific) or `KubernetesConstants` (generic).
- [ ] Metric category names are `public static final String` fields in the appropriate `*MetricCategories` class; no inline string literals.
- [ ] URI templates use `StrimziConstants.ResourceUris.*`.
- [ ] New `mcp.*` config properties documented in the relevant user doc.

### 2.7 — Prompt Templates (category: Prompt / Resource)

- [ ] Prompt names use `kebab-case`: `diagnose-cluster-issue`, `troubleshoot-connectivity`.
- [ ] Returns `PromptResponse.withMessages(List.of(...))` with two messages: assistant-role persona + user-role task.
- [ ] Uses `StrimziToolsPrompts.systemMessage("domain")` for consistent SRE expert persona.
- [ ] Instructions reference specific MCP tool names so the LLM knows what to call.

### 2.8 — Code Style & Checkstyle Compliance (all PRs)

- [ ] License header present on every new Java file.
- [ ] No star imports.
- [ ] Import order: third-party -> javax -> java -> static (alphabetical within groups, blank line between).
- [ ] All public types, methods, fields, and record components have Javadoc.
- [ ] Javadoc `@param`, `@return`, `@throws` tags are non-empty.
- [ ] Locale-sensitive calls use explicit locale: `.toLowerCase(Locale.ROOT)`, not `.toLowerCase()`.
- [ ] `final` on method parameters.
- [ ] `if/else` used over multi-line ternary operators.
- [ ] `List.of()` for empty immutable lists, not `Collections.emptyList()` or `new ArrayList<>()`.
- [ ] No `assert` statements.
- [ ] Every new file ends with a trailing newline.
- [ ] Line length <= 120 characters.
- [ ] 4-space indentation (no tabs).
- [ ] No `var` keyword — always use explicit types.

### 2.9 — Security (all PRs)

- [ ] No sensitive data (tokens, passwords, certificates, PII) logged or returned in tool responses.
- [ ] User-supplied input sanitized via `InputUtils.normalizeInput()` before use in Kubernetes API calls.
- [ ] No new `@PermitAll` or equivalent weakening of endpoint security without justification.
- [ ] External URLs or dynamically constructed query strings (PromQL, LogQL) use the appropriate sanitizer (`PromQLSanitizer`, `LogQLSanitizer`).

### 2.10 — Tests (category: Tests or any new tool/service)

- [ ] New tools have their name added to `McpDiscoveryTest.testToolDiscovery()` (in `strimzi-mcp/src/test/.../tool/`).
- [ ] New services have a corresponding unit test class.
- [ ] Test names follow pattern `testDescriptiveName` in camelCase (e.g., `testListClustersReturnsEmptyWhenNoneExist`).
- [ ] System tests call `assertToolSuccess()` / `assertToolError()` from `AbstractST` — not bare `assertFalse(isError())`.
- [ ] Tool response content validated (resource name, namespace, key fields) — not just success/error status.
- [ ] System tests log tool responses for debugging: `LOGGER.info("... response:\n{}", ...)`.
- [ ] JSON arrays iterated (not `.toString().contains(...)`) for membership checks.

### 2.11 — Documentation (all PRs that add/change user-facing features)

- [ ] New MCP tools documented in `docs/strimzi-mcp/` (tool name, description, parameters).
- [ ] New config properties documented.
- [ ] `CHANGELOG.md` updated under `## [Unreleased]` with appropriate Keep a Changelog category.
- [ ] `AGENTS.md` updated if architecture, patterns, or module structure changed.
- [ ] Test plan (`dev/test-plans/strimzi-mcp-tool-test-plan.md`) updated for new/modified tools.

---

## Step 3 — Compile Findings

Collect all findings into a structured list with severity, file, line, category, issue, and suggestion.

Compute a summary:
- **Critical** count (must-fix before merge)
- **Major** count (should-fix)
- **Minor / Nit** count (optional)
- **Overall verdict**: `APPROVE` (0 critical, 0 major) / `REQUEST CHANGES` (any critical or major) / `COMMENT` (minor/nit only)

Present the top critical/major findings first, then group remaining by category.

---

## Notes

- If the diff is very large (>500 changed lines), focus on architecturally significant files first, then scan the rest for style violations.
- If the PR only changes docs or config files, skip sections 2.1-2.8 and focus on 2.11.
- Base every finding on actual diff content, not speculation about intent.
