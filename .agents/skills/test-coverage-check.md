Analyse the codebase or changed files for test coverage gaps and test quality issues.

If specific files or a module are provided, scope the analysis to those. Otherwise, analyse
recent changes (`git diff` or `git log`) to identify what needs coverage.

$ARGUMENTS

---

## Step 1 — Identify What Needs Coverage

Map source classes to their expected test counterparts:

| Source pattern | Expected test |
|---|---|
| `service/*Service.java` | `service/*ServiceTest.java` |
| `tool/*Tools.java` | `tool/*ToolsTest.java` |
| `service/*DiagnosticService.java` | `service/*DiagnosticServiceTest.java` |
| `prompt/*Prompt.java` | Covered by shared `prompt/PromptTemplateValidationTest.java` (not 1:1) |
| `resource/template/*Resource.java` | Covered by `tool/McpDiscoveryTest.testResourceTemplateDiscovery()` (not 1:1) |
| `dto/*Response.java` or `dto/*Report.java` | Serialization test if non-trivial logic |
| `config/*Constants.java` | Not required unless computed values |

Source locations:
- **strimzi-mcp**: `strimzi-mcp/src/main/java/io/streamshub/mcp/strimzi/`
- **common**: `common/src/main/java/io/streamshub/mcp/common/`
- **metrics-prometheus**: `metrics-prometheus/src/main/java/io/streamshub/mcp/metrics/prometheus/`
- **loki-log-provider**: `loki-log-provider/src/main/java/io/streamshub/mcp/loki/`

Test locations mirror the source structure under `src/test/java/`.

---

## Step 2 — Check McpDiscoveryTest Registration

Every MCP tool must be registered in `strimzi-mcp/src/test/java/.../tool/McpDiscoveryTest.java`:

1. **`testToolDiscovery()`** — tool name in the expected set
2. **`testToolMetadata()`** — tool name with expected `type` and `resource` metadata values

Search for tool names defined in `@Tool(name = "...")` annotations and verify each appears in both test methods.

---

## Step 3 — Verify Test Quality

For each existing test file, check:

### Naming
- [ ] Test methods follow `testDescriptiveName` pattern in camelCase
- [ ] Example: `testListClustersReturnsEmptyWhenNoneExist`, `testThrowsWhenClusterNameMissing`

### Assertions
- [ ] No bare `assertFalse(response.isError())` — must validate response content
- [ ] System tests use `AbstractST` assertion helpers: `assertToolSuccess()`, `assertToolError()`, `assertDiagnosticReport()`, `assertPodSummaryResponse()`, `assertLogsResponse()`, `assertEventsResponse()`, `assertMetricsResponse()`, `assertNoStackTrace()`
- [ ] Response content verified (resource name, namespace, key fields), not just success/error
- [ ] JSON arrays iterated properly, not checked via `.toString().contains(...)`
- [ ] Error test cases verify the error message content, not just `isError()` flag

### Logging
- [ ] Every system test tool call logs its response: `LOGGER.info("... response:\n{}", ...)`
- [ ] Large responses (logs, metrics) log length only

### Test Data
- [ ] Kafka version >= 4.2.0 in all test data and mock resources
- [ ] Test resource names, namespaces, and counts match the test setup

### Mocking (unit tests)
- [ ] Kubernetes client interactions properly mocked
- [ ] `@QuarkusTest` annotation present on test classes
- [ ] Mock setup covers both namespace-scoped and all-namespace queries

---

## Step 4 — Report Gaps

For each gap found, report:
- **File**: source class missing test coverage
- **Gap type**: missing test class / missing test method / missing assertion / wrong pattern
- **Suggested fix**: test method signature or assertion to add

Group by module and prioritise:
1. New tools/services with no test class at all
2. Existing tests with inadequate assertions
3. Missing McpDiscoveryTest registration
4. Test naming or quality issues

---

## Step 5 — Coverage Report (if available)

If JaCoCo reports are available (`target/site/jacoco/index.html`), check:
- Overall line/branch coverage per module
- Uncovered methods in service classes
- Uncovered branches in conditional logic

Generate reports with: `./mvnw verify -Pcoverage`
