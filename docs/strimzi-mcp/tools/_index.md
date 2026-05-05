+++
title = 'Tools reference'
weight = 3
+++

Complete reference for all tools, prompts, and resources provided by the Strimzi MCP Server.

## Tool categories

- **[Kafka cluster tools](kafka-clusters.md)** -- Manage and inspect Kafka clusters, pods, bootstrap servers, certificates, and logs
- **[Kafka topic and node pool tools](kafka-topics.md)** -- List and inspect Kafka topics and KafkaNodePool resources
- **[Kafka user tools](kafka-users.md)** -- List and inspect KafkaUser resources, ACL rules, quotas, and authentication
- **[Strimzi operator tools](strimzi-operators.md)** -- Manage Strimzi operators and view Kubernetes events for Kafka resources
- **[KafkaConnect tools](kafka-connect.md)** -- Manage KafkaConnect clusters and KafkaConnectors
- **[KafkaBridge tools](kafka-bridge.md)** -- Manage KafkaBridge HTTP REST API bridges
- **[Drain Cleaner tools](drain-cleaner.md)** -- Monitor Strimzi Drain Cleaner deployment, webhook configuration, and readiness
- **[Diagnostic tools](diagnostics.md)** -- Run multi-step diagnostic workflows with LLM-guided triage
- **[Metrics tools](metrics.md)** -- Retrieve and analyze Prometheus metrics from Kafka and Strimzi components
- **[Prompts, resources, and subscriptions](prompts-and-resources.md)** -- Prompt templates, resource templates, and resource subscriptions

## Smart discovery

All tools support **smart discovery** -- the namespace parameter is always optional.
When omitted, tools automatically search across the entire cluster to find the requested resources.

## Next steps

- **[Usage Examples](../usage-examples.md)** -- See practical examples
- **[Configuration](../configuration.md)** -- Configure integrations
- **[Troubleshooting](../troubleshooting.md)** -- Resolve common issues
