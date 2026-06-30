+++
title = 'AI Agent Best Practices'
weight = 3
+++

Tips, tricks, and corner cases for getting the best results when using the MCP Server for Strimzi with AI assistants.

## Why structured MCP responses are better than scripts

All MCP tool responses are structured JSON with pre-computed summary statistics, interpretation guides, and (for diagnostics) automated root cause analysis.
The AI assistant should read these fields directly rather than writing scripts to re-process the raw data.

**What the MCP server already provides**:

- **Summary statistics** on metrics: `min`, `max`, `avg`, `latest`, `delta` — no need to compute these from raw data points
- **Interpretation guides** with thresholds: the response tells the AI what values are healthy, warning, or critical
- **Diagnostic analysis**: the `analysis` field in diagnostic reports contains LLM-generated root cause analysis
- **Error categorization**: log collection responses group errors by category and frequency

**Example**:
```
User: "What are the current metrics for mcp-cluster?"

Good AI response: "Based on the metrics response:
- Under-replicated partitions: 0 (healthy)
- Messages in: 1,250/sec across all brokers
- CPU usage: 45% average (within normal range)"
```

If the AI assistant starts writing Python or bash scripts to parse MCP data, remind it: *"Interpret the JSON response directly — the response already contains summary statistics and analysis."*

## Choose the right investigation approach

The MCP server provides three ways to investigate issues, each with different trade-offs:

### Prompt templates — AI-driven, step-by-step guidance

Prompt templates guide the AI through a structured diagnostic workflow step by step. The AI calls individual tools in sequence, following the template's instructions.

Available prompt templates:
- `diagnose-cluster-issue`
- `troubleshoot-connectivity`
- `audit-security`
- `analyze-kafka-metrics`
- `troubleshoot-connect`
- `troubleshoot-connector`
- `troubleshoot-topic`
- `troubleshoot-mirror-maker`
- `troubleshoot-bridge`
- `analyze-capacity`
- `compare-cluster-configs`
- `assess-upgrade-readiness`

**When to use**: When you want the AI to walk through the investigation interactively, explaining each step and allowing you to steer the analysis. This gives you the most visibility into what the AI is doing.

**How to use**: Ask the AI to use a prompt template by name, or select it in AI clients that support prompt selection:
```
"Use the diagnose-cluster-issue prompt for mcp-cluster"
"Use the audit-security prompt to check mcp-cluster security"
"Use the analyze-capacity prompt for prod-cluster"
```

See [Prompts, resources, and subscriptions](tools/prompts-and-resources.md) for the full list and parameters.

### Diagnostic tools — server-driven, automated

Diagnostic tools run the entire multi-step workflow server-side in a single tool call and return a consolidated report.

Available diagnostic tools:
- `diagnose_kafka_cluster`
- `diagnose_kafka_connectivity`
- `diagnose_kafka_metrics`
- `diagnose_kafka_connect`
- `diagnose_kafka_connector`
- `diagnose_kafka_topic`
- `diagnose_kafka_mirror_maker`
- `assess_upgrade_readiness`
- `compare_kafka_clusters`

**When to use**: When you want a quick, complete answer without step-by-step interaction. When the AI client supports Sampling, diagnostic tools use it for intelligent triage and automated root cause analysis.

**How to use**: Simply ask the AI to diagnose an issue:
```
"Diagnose issues with mcp-cluster"
"Check connectivity for prod-cluster"
```

**Prefer diagnostic tools over manual chaining**: One `diagnose_kafka_cluster` call replaces manually chaining `get_kafka_cluster` → `get_kafka_cluster_pods` → `get_kafka_cluster_logs` → `get_strimzi_events` → `get_kafka_metrics` (five separate calls).

### Individual tools — manual, most control

Individual tools (`get_kafka_cluster`, `list_kafka_topics`, `get_kafka_cluster_logs`, `get_kafka_metrics`, etc.) query a single resource or data source. The AI calls them directly.

**When to use**: When you know exactly what data you need, or when diagnostic tools and prompts are too broad for your specific question.

**How to use**:
```
"Show me the logs from mcp-cluster-kafka-2 in the last 30 minutes"
"What are the replication metrics for prod-cluster?"
```

## Start with fleet overview

When investigating an environment with multiple Kafka clusters, start with `get_kafka_fleet_overview` for a quick health summary across all Kafka clusters:

```
User: "What's the state of my Kafka infrastructure?"
```

The fleet overview returns aggregated health including status distribution, total broker count, per-cluster summaries with relationship counts (topics, users, connectors), and warnings for Kafka clusters that need attention. Use this to identify which Kafka clusters to investigate further.

## Understand response structure

### Metrics responses include summary statistics

Every metrics time series includes a `summary` with pre-computed statistics. The AI should use these directly instead of processing raw data points:

| Field | Description |
|-------|-------------|
| `min` | Minimum value in the series |
| `max` | Maximum value in the series |
| `avg` | Average value across all data points |
| `latest` | Most recent value |
| `delta` | Change from first to last value |
| `original_data_point_count` | Number of data points before compression |

For single-point queries (instant metrics), only `latest` and `original_data_point_count` are populated; other fields are omitted to reduce response size.

Metrics responses also include an `interpretation` field with thresholds and diagnostic guidance that the AI can use directly.

When a time series has a `compressed: true` flag, it means consecutive identical values were collapsed to save tokens. The summary statistics reflect the full original data.

### Diagnostic reports include step tracking

All diagnostic reports include these fields:

- **`steps_completed`** — list of diagnostic steps that succeeded
- **`steps_failed`** — list of steps that failed, with error descriptions
- **`analysis`** — LLM-generated root cause analysis (when Sampling is supported), or null
- **`message`** — human-readable summary of the diagnostic run

The AI should always check `steps_failed` for incomplete data. When the `analysis` field is present, it contains the root cause analysis, and the AI should present it rather than re-processing the raw data sections.

### Large responses are automatically truncated

Responses exceeding 500 KB are truncated with the notice `[...response truncated to stay within size limit]`. This is normal behavior, not an error. The summary statistics and analysis fields remain intact even when raw data is truncated.

## Use parameters to reduce response size

Use tool parameters strategically to avoid timeouts and reduce response sizes:

- **`podNames`** — Filter pod logs to specific pods instead of collecting from all pods. Important for large Kafka clusters with many brokers.
- **`rangeMinutes`** — Use shorter time ranges for log queries. "Last 30 minutes" instead of "last 24 hours" avoids timeouts and oversized responses.
- **`namespace`** — Specify the namespace to avoid scanning all namespaces in the Kubernetes cluster.
- **`aggregation`** — For metrics, use higher aggregation levels (`cluster` or `broker` instead of `partition` or `topic`) when per-partition detail is not needed.

```
Instead of: "Show me logs from the last 24 hours"
Better:     "Show me logs from the last hour for mcp-cluster in namespace kafka-prod"
```

## Handle pagination for topic lists

The `list_kafka_topics` tool returns paginated results. The response includes:

| Field | Description |
|-------|-------------|
| `items` | Topics in the current page |
| `total` | Total number of topics matching the query |
| `offset` | Zero-based offset of the first item |
| `limit` | Maximum items per page (default 100) |
| `has_more` | Whether more pages exist |

When `has_more` is `true`, call `list_kafka_topics` again with an incremented `offset` to retrieve the next page. For example, if `total` is 250 and `limit` is 100, use `offset=0`, then `offset=100`, then `offset=200`.

## Effective questions

Mentioning the Kafka cluster name in the question helps the AI choose the right tool parameters immediately, avoiding an extra list call:

**Good**: "What's the status of Kafka mcp-cluster and are there any issues?"
- Specific Kafka cluster name
- Clear intent
- Allows AI to choose appropriate tools

**Better**: "Diagnose issues with Kafka mcp-cluster in namespace kafka-prod, focusing on recent errors"
- Specific Kafka cluster, namespace, and scope
- Guides the diagnostic approach
- More efficient tool usage

**Less efficient**: "Check my Kafka cluster for issues" (AI must first call `list_kafka_clusters` to find the name)

## Iterative investigation

Start broad, then narrow down:
1. "What's the fleet overview?" → Multi-cluster health check
2. "What's the status of mcp-cluster?" → Specific Kafka cluster
3. "Diagnose issues with mcp-cluster" → Automated multi-step investigation
4. "Show me errors from mcp-cluster in the last hour" → Targeted log analysis

## Attach resources for faster responses

MCP resource templates expose live Kubernetes state as structured JSON. Attach a Kafka cluster status resource to the conversation context so the AI can analyze it without making additional tool calls:

1. Attach the `kafka-cluster-status` resource for a specific Kafka cluster
2. Ask: "Based on this status, what should I check?"
3. The AI can analyze immediately without additional tool calls

## Understand Sampling and Elicitation

**Sampling** and **Elicitation** are optional MCP features that enhance diagnostic workflows.
You do not need to call them directly — the MCP server uses them internally when running diagnostic tools.

**Sampling in plain language**: When you ask the MCP server to diagnose a Kafka cluster, it first collects basic data (status, pods, conditions).
If your AI client supports Sampling, the server sends this initial data to the AI to ask "What areas should I investigate deeper?"
The AI responds with a focus area (for example, "the broker with restarts"), and the server collects detailed data only for that area.
This makes diagnostics faster and more focused.
Without Sampling, the server collects everything and lets the AI analyze the full dataset afterward — this still works, but responses may be larger.

**Elicitation in plain language**: When a tool finds multiple matches (for example, two namespaces both contain a cluster named "my-cluster"), the server asks you to choose which one.
If your AI client supports Elicitation, you see a prompt asking you to pick.
Without Elicitation, the tool returns an error asking you to specify the namespace in your next message.

Neither feature is required.
All tools work without them — they just provide a smoother experience when available.

## MCP client compatibility

Any MCP-compatible AI client can use the tools. The experience varies based on which optional MCP features the client supports:

| Feature | Effect |
|---------|--------|
| **Sampling** | Diagnostic tools use LLM-driven triage for smarter, targeted investigation and automated root cause analysis. Without Sampling, diagnostics still work but gather all data without selective investigation, which may produce larger responses. |
| **Elicitation** | When a tool needs user input (for example, choosing which namespace when multiple match), the server prompts the user directly through the AI client. Without Elicitation, the tool returns an error asking the user to specify the missing parameter. |

Check your AI client's documentation for which MCP features it supports.

### Programmatic access

Direct tool calls via MCP protocol:
```json
{
  "method": "tools/call",
  "params": {
    "name": "get_kafka_cluster",
    "arguments": {
      "clusterName": "mcp-cluster",
      "namespace": "strimzi-kafka"
    }
  }
}
```

## Getting started: progressive complexity

If you are new to the MCP Server for Strimzi, follow this path to build familiarity:

**Level 1 — Basic queries** (individual tools):
```
"List all Kafka clusters"
"What's the status of mcp-cluster?"
"List topics in mcp-cluster"
```

**Level 2 — Targeted investigation** (individual tools with parameters):
```
"Show me logs from mcp-cluster in the last 30 minutes, filtered to errors"
"What are the replication metrics for prod-cluster?"
"Show me Kubernetes events for mcp-cluster"
```

**Level 3 — Automated diagnostics** (diagnostic tools):
```
"Diagnose issues with mcp-cluster"
"Check connectivity for prod-cluster"
"Compare dev-cluster and prod-cluster configurations"
```

**Level 4 — Guided investigation** (prompt templates):
```
"Use the diagnose-cluster-issue prompt for mcp-cluster with symptom 'consumers lagging'"
"Use the audit-security prompt to review mcp-cluster"
"Use the analyze-capacity prompt for prod-cluster"
```

See [prompts and resources](tools/prompts-and-resources.md) for the full list of prompt templates and their parameters.

## Next steps

- **[Usage Examples](usage-examples.md)** — See practical example interactions
- **[Tools reference](tools/)** — Complete tool catalog
- **[Troubleshooting](troubleshooting.md)** — Resolve common issues
- **[Configuration](configuration.md)** — Configure integrations
