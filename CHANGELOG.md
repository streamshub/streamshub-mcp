# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.2.0] - Unreleased

### Added

- **Fleet overview tool** -- `get_kafka_fleet_overview` returns aggregated health across all Kafka clusters in a single call, including status distribution, total broker count, per-cluster summaries with cross-resource relationship counts (topics, users, active rebalances, connected KafkaConnect/Bridge/MirrorMaker2), and warnings for clusters that need attention

### Changed

### Fixed

- Replaced deprecated `Elicitation.isSupported()` with `isFormModeSupported()` across all diagnostic services

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

[0.2.0]: https://github.com/streamshub/streamshub-mcp/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/streamshub/streamshub-mcp/releases/tag/v0.1.0
[0.0.1]: https://github.com/streamshub/streamshub-mcp/releases/tag/v0.0.1
