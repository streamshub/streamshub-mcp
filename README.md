# StreamsHub MCP

[![Build](https://github.com/streamshub/streamshub-mcp/actions/workflows/build.yml/badge.svg)](https://github.com/streamshub/streamshub-mcp/actions/workflows/build.yml)
[![License](https://img.shields.io/github/license/streamshub/streamshub-mcp)](LICENSE)
![Java](https://img.shields.io/badge/Java-21%2B-blue)

> [!WARNING]
> This project is in early alpha and under active development. APIs, tool definitions, and configuration may change without notice.

StreamsHub MCP provides Model Context Protocol (MCP) servers that give AI assistants direct access to Kubernetes-based streaming infrastructure.
Each component of your streaming stack gets its own MCP server that can be deployed independently.

Works with any MCP-compatible client including Claude Code, Claude Desktop, VS Code, Copilot, and more.

## MCP servers

| Server | Description |
|--------|-------------|
| [Strimzi MCP](strimzi-mcp/) | Manage and troubleshoot Strimzi-managed Kafka clusters on Kubernetes |

## Key capabilities

StreamsHub MCP servers leverage the full MCP specification:

- **Prompt templates** -- Structured diagnostic workflows that guide the LLM step-by-step through complex troubleshooting
- **Resource templates** -- Live cluster state exposed as structured context that clients can attach directly to conversations
- **Resource subscriptions** -- Kubernetes watches that push real-time notifications when cluster state changes
- **Completions** -- Dynamic autocomplete for parameters (namespaces, cluster names, topics) powered by live Kubernetes queries
- **Progress and cancellation** -- Long-running operations report progress and support mid-operation cancellation

## Getting started

See the [getting started guide](docs/getting-started.md) for a quick setup.

For server-specific documentation, see the README in each server directory.

## Documentation

Documentation is available in the [`docs/`](docs/) directory:

- [Getting started](docs/getting-started.md) -- Quick start guide
- [Strimzi MCP Server](docs/strimzi-mcp/) -- Installation, configuration, tools, and troubleshooting

## Development

```bash
./mvnw compile              # Compile all modules (includes checkstyle)
./mvnw test                 # Run unit tests
./mvnw verify -Psystemtest  # Run system tests (requires Kubernetes cluster)
```

For more details:

- [Contributing](CONTRIBUTING.md) -- Contribution workflow and guidelines
- [Testing](dev/docs/TESTING.md) -- Unit and system test guide

## Built with

- [Quarkus](https://quarkus.io/) -- Cloud-native Java framework
- [Fabric8 Kubernetes Client](https://github.com/fabric8io/kubernetes-client) -- Kubernetes API access
- [Quarkus MCP Server](https://docs.quarkiverse.io/quarkus-mcp-server/dev/index.html) -- MCP protocol implementation

## License

[Apache License 2.0](LICENSE)
