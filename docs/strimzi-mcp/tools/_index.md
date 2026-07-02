+++
title = 'Tools reference'
weight = 3
+++

Complete reference for all tools, prompts, and resources provided by the MCP Server for Strimzi.

**Start here**: If you are new, read [Kafka cluster tools](kafka-clusters.md) first to understand the basic tools, then [diagnostic tools](diagnostics.md) to see how automated workflows build on them.

## Tool summary

| Category | Tools | Type | Description |
|----------|-------|------|-------------|
| [Kafka clusters](kafka-clusters.md) | 9 | Read-only | Cluster status, pods, bootstrap servers, certificates, logs, config |
| [Topics & node pools](kafka-topics.md) | 5 | Read-only | Topic listing/details, node pool status and pods |
| [Users](kafka-users.md) | 2 | Read-only | KafkaUser resources, ACLs, quotas |
| [Rebalances](kafka-rebalances.md) | 2 | Read-only | KafkaRebalance status and Cruise Control sessions |
| [Operators](strimzi-operators.md) | 5 | Read-only | Operator status, pods, logs, Kubernetes events |
| [KafkaConnect](kafka-connect.md) | 6 | Read-only | Connect clusters, connectors, pods, logs |
| [KafkaBridge](kafka-bridge.md) | 4 | Read-only | Bridge instances, pods, logs |
| [MirrorMaker2](kafka-mirror-maker.md) | 4 | Read-only | MM2 instances, pods, logs |
| [Drain Cleaner](drain-cleaner.md) | 4 | Read-only | Drain Cleaner deployment, webhook, readiness |
| [Diagnostics](diagnostics.md) | 10 | Composite | Multi-step workflows with LLM-guided triage |
| [Metrics](metrics.md) | 5 | Read-only | Prometheus metrics from all Kafka components |
| [Prompts & resources](prompts-and-resources.md) | 13 prompts, 6 resources | - | Prompt templates and resource templates |

**All tools are read-only** — the MCP server never creates, modifies, or deletes Kubernetes resources.

**Diagnostic tools are composite** — they make multiple internal API calls in a single tool invocation and may take longer to execute (10-60 seconds).

## Tool categories

- **[Kafka cluster tools](kafka-clusters.md)** -- Manage and inspect Kafka clusters, pods, bootstrap servers, certificates, and logs
- **[Kafka topic and node pool tools](kafka-topics.md)** -- List and inspect Kafka topics and KafkaNodePool resources
- **[Kafka user tools](kafka-users.md)** -- List and inspect KafkaUser resources, ACL rules, quotas, and authentication
- **[KafkaRebalance tools](kafka-rebalances.md)** -- List and inspect KafkaRebalance resources, optimization results, and Cruise Control sessions
- **[Strimzi operator tools](strimzi-operators.md)** -- Manage Strimzi operators and view Kubernetes events for Kafka resources
- **[KafkaConnect tools](kafka-connect.md)** -- Manage KafkaConnect clusters and KafkaConnectors
- **[KafkaBridge tools](kafka-bridge.md)** -- Manage KafkaBridge HTTP REST API bridges
- **[KafkaMirrorMaker2 tools](kafka-mirror-maker.md)** -- Manage KafkaMirrorMaker2 cross-cluster replication
- **[Drain Cleaner tools](drain-cleaner.md)** -- Monitor Strimzi Drain Cleaner deployment, webhook configuration, and readiness
- **[Diagnostic tools](diagnostics.md)** -- Run multi-step diagnostic workflows with LLM-guided triage
- **[Metrics tools](metrics.md)** -- Retrieve and analyze Prometheus metrics from Kafka and Strimzi components
- **[Prompts, resources, and subscriptions](prompts-and-resources.md)** -- Prompt templates, resource templates, and resource subscriptions

## Smart discovery

All tools support **smart discovery** -- the namespace parameter is always optional.
When omitted, tools automatically search across the entire Kubernetes cluster to find the requested resources.

**How it works**: When you ask about "mcp-cluster" without specifying a namespace, the tool lists all Kafka clusters across all namespaces and finds the one matching the name.
If exactly one match is found, it is returned directly.
If multiple namespaces contain a resource with the same name, the tool asks you to specify which namespace (via Elicitation if your AI client supports it, or via an error message if not).

**Performance note**: Specifying the namespace explicitly avoids the cross-cluster search and is faster, especially in large Kubernetes clusters with many namespaces.

## Next steps

- **[Usage Examples](../usage-examples.md)** -- See practical examples
- **[Configuration](../configuration.md)** -- Configure integrations
- **[Troubleshooting](../troubleshooting.md)** -- Resolve common issues
