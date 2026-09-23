# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.3.0] - Unreleased

### Added

- **Enriched `KafkaClusterResponse`** -- `get_kafka_cluster`, `list_kafka_clusters`, and `get_kafka_fleet_overview` now return additional Kafka CR status fields: `running_kafka_version` (actual running version from status), `kafka_metadata_version` (KRaft metadata version), `operator_last_successful_version` (operator version of last successful reconciliation), `cluster_id` (Kafka cluster UUID), `auto_rebalance` (Cruise Control auto-rebalance state, modes, and last transition time), `cluster_security` (encryption and authentication status strings), and `reconciliation` (generation, observed_generation, up_to_date).
- **Enriched `KafkaNodePoolResponse`** -- `get_kafka_node_pool` and `list_kafka_node_pools` now return status-side fields alongside the existing spec fields: `status_replicas` (actual replica count), `status_roles` (assigned roles from status), `node_ids` (KRaft node IDs used by the pool), `ready` (whether the Ready condition is true), `conditions` (full conditions list), and `reconciliation` (generation tracking).
- **Enriched `KafkaTopicResponse`** -- `get_kafka_topic` and `list_kafka_topics` now return `namespace`, `topic_id` (internal Kafka UUID from status), `topic_name` (actual Kafka topic name, may differ from the resource name), `replicas_change` (Cruise Control replication-factor change in progress: `target_replicas`, `state`, `session_id`, `message`), `conditions` (full conditions list), and `reconciliation` (generation tracking).
- **Enriched `KafkaCertificateResponse`** -- `get_kafka_cluster_certificates` now returns `cluster_ca_policy` and `clients_ca_policy` objects for both CAs, each containing `renewal_days`, `validity_days`, `generate_certificate_authority`, `certificate_expiration_policy`, and a pre-calculated `calculated_renewal_date` so agents can determine upcoming rotation without parsing certificate dates.
- **Enriched `KafkaEffectiveConfigResponse`** -- `get_kafka_cluster_config` now returns `tiered_storage` (type and `remote_storage_manager` with class details and safe config -- sensitive credentials redacted) and `quotas` (quotas plugin type and safe config) when configured on the Kafka CR.
- **Push-based cancellation for diagnostics** — Added `DiagnosticHelper.registerCancellationCallback()` for async diagnostic operations (sampling, elicitation). The callback approach catches cancellation immediately via `Cancellation#onCancelled()` instead of polling with `skipProcessingIfCancelled()`, preventing wasted work during long-running LLM calls. Existing `checkCancellation()` remains for synchronous code paths.
- **MRTR support for stateless MCP clients** (#251) — composite diagnostic tools (`diagnose_*`, `assess_upgrade_readiness`) and `compare_kafka_clusters` now perform LLM analysis for stateless clients (streamable HTTP, protocol 2026-07-28) via the Multi Round-Trip Request pattern, in addition to the existing stateful (SSE) path. Stateless clients receive an `input_required` result and retry with the gathered input; LLM triage is skipped for stateless clients (analysis runs over all gathered data). Namespace disambiguation uses MRTR elicitation for single-namespace diagnostics and the structured-error fallback for cluster comparison.
- **Elasticsearch/OpenSearch log provider** (`mcp.log.provider=streamshub-elasticsearch`) for querying logs from Elasticsearch or OpenSearch
- **Dev environment manifests and scripts** for deploying Elasticsearch with Fluent Bit on Kind clusters and ECK Operator on OpenShift with automated API key provisioning, ClusterLogForwarder integration, and passthrough TLS routes
- **Dev environment metrics configuration** — configured `strimziMetricsReporter` on the primary Kafka cluster (`mcp-cluster`) and `jmxPrometheusExporter` with ConfigMap and PodMonitor on the mirror cluster (`mcp-cluster-mirror`) for live coverage of both metric backend pathways in dev/test environments.
- **System tests** for Elasticsearch log provider covering log collection, field mapping, time window queries, and error handling
- **Thanos and VictoriaMetrics compatibility documentation** — the existing Prometheus metrics provider works with Thanos Querier and VictoriaMetrics without code changes
- **Resource template cache control** — All 6 resource templates now include cache control hints with a 30-second TTL and PUBLIC scope, allowing MCP clients to cache Kubernetes resource state and reduce redundant API calls. TTL is configurable via `mcp.resource-template.cache-ttl-seconds` (default: 30).
- **MCP protocol traffic logger** — Added `McpTrafficLogger` implementing `McpTrafficListener` (new in MCP 2.0) for DEBUG-level logging of all inbound/outbound MCP messages. Enable with `quarkus.log.category."io.streamshub.mcp.common.observability".level=DEBUG` in `application.properties`.
- **Resource subscription regression tests** — Added `ResourceSubscriptionNotificationTest` verifying that `sendUpdateAndForget()` delivers `notifications/resources/updated` to subscribers over both transports after the quarkus-mcp-server 2.0 upgrade: legacy SSE (`resources/subscribe`) and stateless Streamable HTTP (`subscriptions/listen`, protocol `2026-07-28`). Confirms 2.0 compatibility (issue #228); no production code changes were required.
- **Structured error data** (#229) — Resource-not-found, invalid-parameter, and resource-ambiguity tool errors now carry a machine-readable JSON-RPC `error.data` payload (`McpErrorData`: `category`, `resource_kind`, `resource_name`, `namespace`, `candidates`, `remediation`) in addition to the human-readable message, so LLM clients can handle errors programmatically. Adopts the `data` field added to `McpException` in quarkus-mcp-server 2.0. New `McpErrors` factory and `McpErrorData`/`McpErrorCategory` types in the `common` module. (RBAC/403 failures remain failed tool responses — they are wrapped with contextual messages at the Kubernetes-service layer, which several tools rely on for graceful degradation.)
- **Metric catalog regression test** (`MetricCatalogTest`) — Layer-1 unit test enforcing five invariants across all six `*MetricCategories` classes: catalog↔description coherence, backend shape rules (no Micrometer names in JMX exporter classes or vice-versa, no `jvm_*` in the Go-based exporter), PromQL well-formedness, no silent duplicates, and golden-file catalog completeness. Accompanied by the `known-series-jmx-exporter.txt` golden file.
- **SMR classification completeness test** (`MetricNameResolverTest.everyKafkaCatalogNameIsClassifiedForSmr`) — every name in `KafkaMetricCategories` must be either in `aliasMap()` or in the test's explicit `IDENTICAL_ON_SMR` list. Adding a Kafka metric without deciding how it is spelled under the Strimzi Metrics Reporter now fails the build.
- **Strimzi operator reconciliation and certificate metrics** — `StrimziOperatorMetricCategories.RECONCILIATION` now includes `strimzi_reconciliations_locked_total` (lock contention count) and `strimzi_reconciliations_periodical_total` (timer-triggered reconciliations); `RESOURCES` now includes `strimzi_certificate_expiration_timestamp_ms` for CA renewal planning.
- **Backend-aware metric resolution** — Added `MetricsBackend` enum (`JMX_EXPORTER`, `STRIMZI_REPORTER`) resolved automatically per cluster from `spec.kafka.metricsConfig`, along with an alias map in `MetricNameResolver` translating JMX Exporter metric names to Strimzi Metrics Reporter names.
- **KRaft quorum metric category** — Added `kraft` metric category to `KafkaMetricCategories` exposing 13 KRaft quorum health metrics (`current_state`, `current_leader`, `current_epoch`, `commit_latency_avg`, `high_watermark`, channel I/O, etc.) with `BROKER` aggregation level.
- **Per-partition metric category and replication error metrics** — Added `partitions` category with `PARTITION` granularity (`kafka_cluster_partition_underminisr`, `atminisr`, `replicascount`) and added `uncleanleaderelections_total` and `activecontrollercount` to `replication`.
- **Throughput error and connection metrics** — Added `failedproducerequests_total`, `failedfetchrequests_total`, and `connection_count` to `KafkaMetricCategories.THROUGHPUT`. Added `process_open_fds` to `KafkaMetricCategories.RESOURCES` and `KafkaConnectMetricCategories.RESOURCES`.
- **Cruise Control metrics tool** — Added `get_cruise_control_metrics` MCP tool (`CruiseControlMetricsService`, `CruiseControlMetricCategories`, `CruiseControlMetricsResponse`) supporting `sampling` and `anomaly` categories for Cruise Control partition monitoring and anomaly detection.

### Changed

- **`partitions` categories now default to per-partition detail** — `AggregationLevel.resolve()` replaces the previous `fromString(...).clampTo(...)` pattern in all six metrics services. `clampTo` is a ceiling on *fineness*, so it could never raise the CLUSTER default: `get_kafka_metrics(category="partitions")` without an explicit `aggregation` averaged per-partition 0/1 gauges across the cluster, turning 3 partitions under min ISR into `0.003`. When `aggregation` is omitted the default is now the category's finest level for `partitions` (Kafka and Kafka Exporter) and remains `cluster` everywhere else. An explicitly requested level is still clamped as before.
- **`get_kafka_metrics(category="partitions")` reports only unhealthy partitions** — per-partition detail is correct but unbounded: a live 382-partition cluster produced a 390 KB response that exceeded the limit, virtually all of it zeros. Healthy partitions are now dropped, and `replicascount` is kept only for partitions already flagged by `underminisr`/`atminisr`. The interpretation gained a `**[PARTITION SCAN]**` section reporting how many partitions were scanned and how many were flagged, so an empty series list reads as "everything is healthy" rather than "metrics are missing". Requesting the metric names explicitly instead of the category still returns every partition.
- **Single-page `tools/list`** (#265) — set `quarkus.mcp.server.tools.page-size=100` so the full tool list ships in one page instead of paginating at the framework default of 50.
- **Native guardrail migration** — Migrated from custom `@Guarded`/`GuardrailInterceptor`/`GuardrailFilter`/`@RateCategory` stack to native `@ToolGuardrails` per `@Tool` method. Each tool now declares its guardrail palette via `@ToolGuardrails(input = {...}, output = {...})` on the method. Rate category is chosen by which rate-limit guardrail class the tool lists (`GeneralRateLimitGuardrail`, `LogRateLimitGuardrail`, or `MetricsRateLimitGuardrail`). Configuration keys (`mcp.guardrail.*`) are unchanged. Behavior changes: (1) error responses are now also sanitized (redacted + size-limited), whereas before only successful responses were; (2) log redaction now fails closed (a redaction failure returns a generic error instead of the raw payload) rather than fail-open.
- **Tool-call metrics moved to a dedicated interceptor** — Tool-call metrics (`mcp.tool.calls`, `mcp.tool.call.duration`) are now recorded by a `@MeasuredTool` CDI interceptor (`ToolMetricsInterceptor` + `ToolCallMetricsRecorder`) instead of an output guardrail. Output guardrails cannot observe thrown `McpException` protocol errors (the framework skips `ToolOutputGuardrail` processing for non-`ToolCallException` failures), so the interceptor — which wraps the full invocation like `@WithSpan` tracing — now records **every** outcome consistently. The existing `server`/`tool`/`status` tags are unchanged; a new **`error_type`** tag (`none`, `protocol_error`, `tool_error`, `rate_limited`) distinguishes the two MCP error channels (JSON-RPC protocol errors vs tool-execution errors), aligning with the OpenTelemetry `error.type` convention. Fixes an inconsistency where not-found/invalid-params (protocol) errors were dropped from metrics while RBAC/infra errors were counted, and rate-limited rejections (previously uncounted by both the old and interim implementations) are now recorded with `error_type=rate_limited` (counter only; no duration, since the tool never ran). `input_required` (MRTR) remains uncounted.
- **Guardrail execution-model contract** — All guardrail beans now declare `@SupportedExecutionModels({WORKER_THREAD, VIRTUAL_THREAD})`, matching the invariant that every tool runs on a worker or virtual thread (never the event loop). A new `GuardrailExecutionModelTest` enforces this so future guardrails cannot silently default to a different contract.
- **Virtual-thread offloading for blocking tools** — Log-collection (`get_*_logs`) and composite diagnostic/comparison/assessment (`diagnose_*`, `compare_*`, `assess_*`) tool methods now run on virtual threads (`@RunOnVirtualThread`), preventing worker-thread-pool exhaustion under concurrent MCP client load during long-running Kubernetes calls and LLM sampling round-trips.
- **Error semantics for structured errors** (#229) — Tool errors in the not-found (`-32002`) and invalid-params (`-32602`) categories are now returned as JSON-RPC protocol errors (carrying `error.data`) rather than failed tool responses (`isError: true`). Rate-limit, cancellation, RBAC/403, and generic infrastructure errors are unchanged and remain failed tool responses. `NamespaceElicitationHelper` now reads the candidate namespaces from the structured error data instead of regex-parsing the error message.

### Fixed

- **Unclean-leader-election metric was silently empty under the Strimzi Metrics Reporter** — `kafka_controller_controllerstats_uncleanleaderelections_total` had no SMR alias, so on an SMR cluster the `replication` category queried a name the broker never exposes and returned nothing. A metric documented as `**CRITICAL ALERT**: unrecoverable message loss` read as healthy. Now aliased to `kafka_controller_controllerstats_uncleanleaderelectionspersec_total`.
- **Wrong label name in the KRaft interpretation guide** — the `kafka_server_raftmetrics_current_state` guide told clients to read a `currentState` label. The JMX exporter rule in `010-Kafka.yaml` captures `current-state`, which the exporter emits as `current_state`. Clients following the guide would find no label at all.
- **`kraft` and `partitions` were undiscoverable** — `StrimziToolsPrompts.METRICS_CATEGORY_DESC`, the published `@ToolArg` schema for `get_kafka_metrics`, still advertised only the original four categories. MCP clients had no way to learn the two new ones existed.
- **An empty resolved metric list returned every series on the pod** — `PrometheusTextParser` treats an empty filter set as "no filter". A query whose names were all dropped as unmappable for the cluster's backend therefore scraped the broker's entire exposition, bounded only by `mcp.metrics.max-samples`. `MetricsQueryService` now short-circuits an empty (non-null) name list to no samples.
- **Metrics responses reported `categories: []` when the category was defaulted** — all six `*MetricsService` classes computed an `effectiveCategories` list (adding the default when the caller omitted `category`), used it to select metrics and build the interpretation, then returned the raw `categories` list in the response. Callers that omitted `category` received replication/sampling data labelled with an empty category list, unable to tell which metrics the numbers belonged to. Found by running the tools against a live cluster.
- **Cruise Control metrics were unreachable on the dev cluster** — `dev/manifests/strimzi/kafka/010-Kafka.yaml` declared `cruiseControl: {}` with no `metricsConfig`, so `get_cruise_control_metrics` returned zero samples. Added a `cruise-control-metrics-config.yml` key to the `kafka-metrics` ConfigMap and wired it into the `cruiseControl` block.
- **`replicafetchermanager_maxlag` was silently missing under the Strimzi Metrics Reporter** — the name was assumed identical across both backends, but SMR exposes no `ReplicaFetcherManager` MBeans at all, so the `replication` category came back one metric short on an SMR cluster while its interpretation still described the metric. Now `UNMAPPED`, alongside `requesthandleravgidle`. Found by running the tool against a live SMR cluster and confirmed by scraping a broker directly; the remaining 20 pass-through names were verified present in the same scrape.
- **KRaft channel counters returned a running total instead of a rate under SMR** — the four `kafka_server_raftchannelmetrics_*_total` names are counters that the Prometheus provider rate-converts to `*_rate_per_second`, but their SMR aliases pointed at the cumulative series rather than the `*_rate` sibling SMR also publishes. The same call therefore returned bytes/sec on a JMX cluster and a raw running total on an SMR one — an idle cluster reported "1,076,757 bytes/sec" of Raft traffic. Aliased to the `*_rate` names.
- **Interpretation guides named metrics that were not in the response** — the guide text is written once in JMX Exporter spelling, but a name reaches the client only after two possible renames: the SMR alias translation, and the `_total` → `_rate_per_second` rename the Prometheus provider applies to counters. On an SMR cluster the `replication` guide explained `kafka_controller_controllerstats_uncleanleaderelections_total` while the data carried `kafka_controller_controllerstats_uncleanleaderelectionspersec_rate_per_second`; the rate rename made the guide wrong on JMX clusters too. `MetricNameResolver.alignInterpretation` now rewrites the guide to the names actually returned, annotating `UNMAPPED` names as not exposed by the Strimzi Metrics Reporter rather than leaving them pointing at a series that will never appear. Applied in all six `*MetricsService` classes.
- **Cluster-wide counts and maxima were averaged across pods** — aggregation hard-coded the mean for every metric, so `kafka_controller_kafkacontroller_activecontrollercount` read `0.33` on a healthy three-controller cluster while its own interpretation text says "should be exactly 1", and `kafka_server_replicafetchermanager_maxlag` reported the mean of per-broker maxima, hiding the worst broker the documented thresholds are about. New `MetricAggregation` selects `SUM` or `MAX` for the seven metrics where the mean contradicts the shipped guidance; everything else keeps averaging, including the deliberately per-broker `leadercount` and `partitioncount`. Non-default functions are surfaced as `aggregation_fn` on the series so a sum cannot be misread as a mean.

- **Clean tool error messages (no leaked exception class names)** — `ToolMetricsInterceptor` now normalizes uncaught business/infrastructure exceptions into a `ToolCallException` carrying only the contextual message. Without it, `@WrapBusinessError` wraps such exceptions via `new ToolCallException(cause)` — whose message is `cause.toString()` — leaking the fully-qualified exception class name (e.g. `io.streamshub.mcp.common.service.log.LogQueryException: ...`) into tool responses. This restores the pre-migration behavior the removed `GuardrailInterceptor` provided. Regression guard: `ConfigValidationST.testInvalidLokiUrl` / `testInvalidPrometheusUrl`.
- **Response size truncation for text nested in arrays** — `ResponseSizeLimitGuardrail` now truncates oversized text values that live inside JSON arrays (e.g. `steps_failed[2]`, `items[0].msg` in diagnostic reports), which the previous string-path navigation could not address — it silently skipped nested-array values or wrote a junk `"field[i]"` key onto the parent object, leaving the response oversized. Truncation now also never enlarges a response: fields too short to shorten below their original length (once the truncation notice is appended) are left untouched instead of hitting a swallowed `StringIndexOutOfBoundsException`.
- **Redaction coverage for secrets, tokens and private keys** — `LogRedactionGuardrail` now redacts `secret=`/`secret_key=`/`token=` key/value pairs and whole PEM `PRIVATE KEY` blocks, closing a gap where the configuration docs and system test already implied these were redacted but no rule matched them. Dotted config keys such as `delegation.token.max.lifetime.ms=` are left untouched.
- **Broken `requesthandleravgidle_percent` metric name** — `KafkaMetricCategories` queried `kafka_server_kafkarequesthandlerpool_brokerrequesthandleravgidle_percent`, but the Strimzi JMX exporter rule produces `kafka_server_kafkarequesthandlerpool_requesthandleravgidle_percent` (no `Broker` prefix in the MBean). The query returned no data despite shipping operational thresholds.
- **JVM metric names corrected per component backend** — `StrimziOperatorMetricCategories.JVM` and `KafkaBridgeMetricCategories.RESOURCES` now use Micrometer names (`jvm_gc_pause_seconds_count/sum`, `jvm_threads_live_threads`, `process_cpu_usage`) instead of JMX-exporter names (`jvm_gc_collection_seconds_count/sum`, `jvm_threads_current`, `process_cpu_seconds_total`); `KafkaExporterMetricCategories.RESOURCES` now uses Go process names (`process_cpu_seconds_total`, `process_resident_memory_bytes`, `process_open_fds`, `go_goroutines`) and dropped all `jvm_*` names since `kafka_exporter` is a Go binary. Fixes 4 of 6 metrics returning data for the operator and bridge, and 5 of 6 for the exporter.
- **Controller-role pod metrics no longer dropped** — `KafkaMetricsService` now retains samples from pods where `strimzi_io_controller_role="true"` (previously only `strimzi_io_broker_role="true"` was kept). Fixes `kafka_controller_kafkacontroller_offlinepartitionscount` and all KRaft quorum metrics returning empty on clusters with dedicated controller node pools. Method renamed `filterByBrokerPods` → `filterByKafkaNodePods`.

## [0.2.1] - 2026-09-01

### Changed

- Bumped Strimzi API dependency from 1.1.0 to 1.2.0; the MCP server remains compatible with clusters running Strimzi 1.0.0 and above (CRD `v1`). `AclOperation` was renamed to `StrimziAclOperation` upstream — updated `KafkaUserService` and system test templates accordingly; no CRD schema change, so existing `KafkaUser` resources are unaffected.

## [0.2.0] - 2026-08-03

### Added

- **Topic configuration** -- Added topic configuration (e.g., retention.ms, cleanup.policy) to `KafkaTopicResponse`
- **Shared coding agent skills** -- Added `.agents/skills/` directory with 5 reusable skills (code review, test coverage, add MCP tool, add diagnostic tool, add system test) consumed by Claude Code, Bob, Cursor, GitHub Copilot, and Windsurf via symlinks or agent-specific references
- **E2E Coverage** -- Added e2e coverage that now covers most of the user's scenarios
- **Tool metadata** -- tools now include `_meta` fields (`type`, `resource`, `composite`) in `tools/list` responses, enabling AI agents and clients to discover and filter tools by purpose and target resource (#151)
- **Fleet overview tool** -- `get_kafka_fleet_overview` returns aggregated health across all Kafka clusters in a single call, including status distribution, total broker count, per-cluster summaries with cross-resource relationship counts (topics, users, active rebalances, connected KafkaConnect/Bridge/MirrorMaker2), and warnings for clusters that need attention
- **AI agent best practices documentation** -- expanded usage examples and troubleshooting with guidance on response interpretation, script avoidance, pagination handling, diagnostic report structure, Sampling/Elicitation, and parameter optimization (#135)
- **Prompt template validation tests** -- unit tests for all 13 prompt templates covering null parameter safety, format validation (no unresolved `%s` placeholders or literal `null` injection), and `ERROR_HANDLING_INSTRUCTION` presence
- **Production deployment checklist** in configuration docs covering authentication, rate limiting, CORS, TLS, and log redaction hardening
- **Documentation audit and improvements** -- comprehensive review of all user-facing documentation for correctness and zero-experience usability
- **Structured content output** -- all tools now return `structuredContent` with auto-generated output schemas, enabling MCP clients to programmatically consume tool results; compatibility mode ensures existing clients continue to receive text content alongside structured JSON
- **Input validation** -- added `quarkus-mcp-server-hibernate-validator` for Jakarta Validation constraints on tool arguments (`@NotBlank` on required resource names, `@Min` on numeric parameters); constraints are enforced at runtime and enriched in JSON input schemas for LLM-visible type information
- **Default values in tool schemas** -- exposed fixed default values (`previous`) via `@ToolArg(defaultValue)` so MCP clients can see them in the input schema

### Changed

- Migrated all diagnostic and log-collection user feedback from MCP logging (`notifications/message`) to Progress notifications (`notifications/progress`); `McpLog` is no longer injected into any tool or diagnostic service
- Replaced `LogCollectionParams.notifier` and `BiConsumer<Integer, Integer> progressCallback` with a unified `LogProgressCallback` interface that includes the pod name
- Removed `JavadocStyle` checkstyle check (dropped in Checkstyle 13.9.0) — existing `MissingJavadocType`/`MissingJavadocMethod` checks cover javadoc validation
- Bumped Strimzi API dependency from 1.0.1 to 1.1.0; the MCP server remains compatible with clusters running Strimzi 1.0.1
- Extracted `BaseDiagnosticService` base class in the `common` module -- all 9 diagnostic services now inherit shared fields (`ObjectMapper`, sampling/log config) and reusable `performSampling()`, `performAnalysis()`, `performTriage()` utility methods
- `quarkus.kubernetes-client.trust-certs` is now scoped to the `%dev` profile only -- production builds validate Kubernetes API server certificates by default using the in-cluster CA. Override with `QUARKUS_KUBERNETES_CLIENT_TRUST_CERTS=true` if needed.
- List-returning tools now return wrapper DTOs (`KafkaClusterListResponse`, etc.) with `items` and `count` fields instead of raw lists, enabling structured content output schemas
- Reorganized tool classes into domain sub-packages (`kafka/`, `kafkatopic/`, `operator/`, `diagnostic/`, etc.) matching the existing service and DTO package structure
- Resource subscriptions (`mcp.resource-watches.enabled`) are now **disabled by default** because most AI clients do not yet support MCP resource subscriptions; resource templates still work for on-demand queries
- Renamed deployment and related resources from `streamshub-strimzi-mcp` to `streamshub-mcp-strimzi` for consistent naming
- Unified `get_strimzi_events` event query: merged separate Kafka and non-Kafka code paths into a single method, renamed `clusterName` parameter to `resourceName`, made `resourceKind` required, added `Kafka`, `StrimziOperator`, and `DrainCleaner` as supported resource kinds

### Fixed

- Fixed flaky system test log assertions across 8 test files by replacing hard assumptions about cluster health (zero errors, specific log content) with structural consistency checks that validate field relationships without depending on transient log state
- Fixed Prometheus and Loki providers leaking Java exception class names (e.g., `java.net.UnknownHostException`) in error responses; connectivity failures now return clean messages with the target hostname and root cause
- Fixed Prometheus metrics system tests failing on OpenShift: added optional `cluster-monitoring-view` ClusterRoleBinding for querying the Thanos querier, enabled `metricsConfig` on test Kafka CRs, and deployed PodMonitors for metrics scraping
- Fixed `get_kafka_cluster` and `get_kafka_fleet_overview` counting all node pool replicas (brokers + controllers) as a single combined total; now reports separate `broker_replicas` and `controller_replicas` with per-role storage information (#171)
- Replaced deprecated `Elicitation.isSupported()` with `isFormModeSupported()` across all diagnostic services
- Fixed MCP server metrics config key typo (`quarkus.mcp-server` → `quarkus.mcp.server`); tool-call metrics were silently disabled
- Fixed `auth-mode` documentation to use correct values (`sa-token` and `bearer-token`) matching the actual implementation
- Fixed diagnostic services and prompt templates incorrectly passing KafkaConnect/KafkaMirrorMaker2 names to `get_strimzi_events` as Kafka cluster names (#145)
- Fixed cluster overview Drain Cleaner summary missing namespace, readiness, and replica count
- Fixed diagnostic tools returning empty metrics when using the pod-scraping provider because range queries dropped pod targets

## [0.1.0] - 2026-06-02

### Added

- **KafkaConnect and KafkaConnector tools** -- `list_kafka_connects`, `get_kafka_connect`, `get_kafka_connect_pods`, `get_kafka_connect_logs`, `list_kafka_connectors`, `get_kafka_connector`
- **KafkaBridge tools** -- `list_kafka_bridges`, `get_kafka_bridge`, `get_kafka_bridge_pods`, `get_kafka_bridge_logs`
- **KafkaMirrorMaker2 tools** -- `list_kafka_mirror_makers`, `get_kafka_mirror_maker`, `get_kafka_mirror_maker_pods`, `get_kafka_mirror_maker_logs`
- **KafkaUser tools** -- `list_kafka_users`, `get_kafka_user`
- **KafkaRebalance tools** -- `list_kafka_rebalances`, `get_kafka_rebalance`
- **Drain Cleaner tools** -- `list_drain_cleaners`, `get_drain_cleaner`, `get_drain_cleaner_logs`, `check_drain_cleaner_readiness`
- **Configuration tools** -- `get_kafka_cluster_config`, `compare_kafka_clusters`
- **Cluster overview tool** -- `get_strimzi_kafka_cluster_overview`
- **KafkaConnect metrics** -- `get_kafka_connect_metrics`
- **KafkaBridge metrics** -- `get_kafka_bridge_metrics`
- **KafkaUser resource template** -- `kafka-user-status`
- **Composite diagnostic tools** -- `diagnose_kafka_connect`, `diagnose_kafka_connector`, `diagnose_kafka_topic`, `assess_upgrade_readiness`, `diagnose_kafka_mirror_maker`
- **Prompt templates** -- `compare-cluster-configs`, `audit-security`, `troubleshoot-connect`, `troubleshoot-connector`, `troubleshoot-bridge`, `troubleshoot-topic`, `troubleshoot-mirror-maker`, `analyze-capacity`, `assess-upgrade-readiness`
- **OpenTelemetry tracing** on all tools with `tool.<tool_name>` span naming
- **Metrics aggregation** with hierarchical levels (partition, topic, broker, cluster)
- **MCP tool metrics** for self-monitoring via `strimzi_mcp_tool_invocations_total` and `strimzi_mcp_tool_duration_seconds`
- **Resource watch reconnection** with exponential backoff, safe closure, and state reconciliation
- **Kubernetes name validation** on all services that query the Kubernetes API
- **Metrics sample cap** -- configurable `mcp.metrics.max-samples` (default 10000) to prevent memory spikes from large metric queries
- **Watch health readiness check** -- readiness probe reports DOWN when resource watches exhaust reconnection attempts
- **Metrics improvements** -- common label extraction, rate conversion for counter metrics, and response size optimization with summary statistics
- **LogQueryException** for structured error handling in log collection across Kubernetes and Loki providers

### Changed

- **Pod-level log filtering** -- `get_kafka_cluster_logs` now accepts an optional `podNames` parameter to collect logs from specific pods instead of all. The diagnostic workflow uses this automatically, filtering to problematic pods (not Running, not ready, or restart count > configurable threshold `mcp.diagnostic.restart-threshold`, default 3) when unhealthy pods are detected.
- **Smart time window for log collection** -- diagnostic triage LLM recommends a time window for log and event collection. Supports relative windows (last N minutes) for active issues and absolute windows (start/end ISO 8601) for past incidents. Defaults to 30 minutes when not specified. Auto-escalates once if no errors found, then uses Elicitation to ask the user if they want to expand further.
- Generalized pagination into reusable `PaginatedResponse` and `PaginationUtils` in `common`
- Increased base deployment memory requests/limits (384Mi/768Mi)
- Migrated Strimzi API from v1beta2 to v1
- Improved error propagation from Kubernetes queries
- Improved input validation and log deduplication
- Cluster overview now searches Connect, Bridge, and MirrorMaker2 across all namespaces instead of only the Kafka cluster namespace
- Diagnostic services and prompt templates now auto-discover the Strimzi operator namespace instead of assuming it is in the Kafka cluster namespace
- `dev-deploy.sh` creates `cluster-logging-application-view` ClusterRole if missing (required for OpenShift Logging v6.x)
- `setup-strimzi.sh` now supports `--connect` flag to deploy KafkaConnect with a sample connector
- **MirrorMaker2 dev environment** -- namespace isolation (separate namespaces for mirror cluster and MM2), verification consumer for validating mirroring

### Fixed

- Resource subscription notifications on watch update failures
- IPv6 address handling
- Teardown phase no longer gets stuck when orphaned KafkaTopics exist
- Operator status and logs in diagnostic services no longer fail when the operator is in a different namespace than Kafka
- Cluster overview not finding Connect, Bridge, and MirrorMaker2 deployed in namespaces other than the Kafka cluster
- Prompt templates (`assess-upgrade-readiness`, `troubleshoot-topic`) instructing the LLM to pass the Kafka namespace when querying operator tools
- Loki 403 Forbidden on OpenShift Logging v6.x due to missing `cluster-logging-application-view` ClusterRole

## [0.0.1] - 2026-04-24

Initial release of the MCP Server for Strimzi.

### Added

- **Kafka cluster tools** -- `list_kafka_clusters`, `get_kafka_cluster`, `get_kafka_cluster_pods`, `get_kafka_bootstrap_servers`, `get_kafka_cluster_certificates`, `get_kafka_cluster_logs`
- **Kafka topic tools** -- `list_kafka_topics` (paginated), `get_kafka_topic`
- **Kafka node pool tools** -- `list_kafka_node_pools`, `get_kafka_node_pool`, `get_kafka_node_pool_pods`
- **Strimzi operator tools** -- `list_strimzi_operators`, `get_strimzi_operator`, `get_strimzi_operator_logs`, `get_strimzi_operator_pod`
- **Events tool** -- `get_strimzi_events`
- **Metrics tools** -- `get_kafka_metrics`, `get_kafka_exporter_metrics`, `get_strimzi_operator_metrics`
- **Composite diagnostics** -- `diagnose_kafka_cluster`, `diagnose_kafka_connectivity`, `diagnose_kafka_metrics`, `diagnose_operator_metrics` with Sampling and Elicitation support
- **Prompt templates** -- `diagnose-cluster-issue`, `troubleshoot-connectivity`, `analyze-kafka-metrics`, `analyze-strimzi-operator-metrics`
- **Resource templates** -- `kafka-cluster-status`, `kafka-cluster-topology`, `kafka-nodepool-status`, `kafka-topic-status`, `strimzi-operator-status`
- **Resource subscriptions** with `notifications/resources/updated` for Kafka, KafkaNodePool, KafkaTopic, and Strimzi operator Deployments
- **Grafana Loki** log provider with LogQL queries
- **Prometheus** metrics provider with PromQL queries
- **Security guardrails** -- log redaction, response size limits, rate limiting, input validation, PromQL/LogQL sanitization
- **Two-tier Kubernetes RBAC** -- ClusterRole for non-sensitive resources, optional per-namespace Role for Secrets and pod metrics
- **Kustomize deployment** with base, dev, dev-openshift, prod, and prod-openshift overlays
- Container image published to `quay.io/streamshub/strimzi-mcp`
- Automatic namespace discovery on all tools
- Dynamic parameter completions via live Kubernetes queries

[0.3.0]: https://github.com/streamshub/streamshub-mcp/compare/v0.2.1...main
[0.2.1]: https://github.com/streamshub/streamshub-mcp/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/streamshub/streamshub-mcp/releases/tag/v0.2.0
[0.1.0]: https://github.com/streamshub/streamshub-mcp/releases/tag/v0.1.0
[0.0.1]: https://github.com/streamshub/streamshub-mcp/releases/tag/v0.0.1
