# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] - Unreleased

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

### Changed

- Generalized pagination into reusable `PaginatedResponse` and `PaginationUtils` in `common`
- Increased base deployment memory requests/limits (384Mi/768Mi)
- Migrated Strimzi API from v1beta2 to v1
- Improved error propagation from Kubernetes queries
- Improved input validation and log deduplication

### Fixed

- Resource subscription notifications on watch update failures
- IPv6 address handling
- Teardown phase no longer gets stuck when orphaned KafkaTopics exist

## [0.0.1] - 2026-04-24

Initial release of the Strimzi MCP Server.

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

[0.1.0]: https://github.com/streamshub/streamshub-mcp/compare/v0.0.1...v0.1.0
[0.0.1]: https://github.com/streamshub/streamshub-mcp/releases/tag/v0.0.1
