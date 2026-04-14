# StreamsHub MCP

[![Build](https://github.com/streamshub/streamshub-mcp/actions/workflows/build.yml/badge.svg)](https://github.com/streamshub/streamshub-mcp/actions/workflows/build.yml)
[![License](https://img.shields.io/github/license/streamshub/streamshub-mcp)](LICENSE)
![Java](https://img.shields.io/badge/Java-21%2B-blue)

> [!WARNING]
> This project is in early alpha and under active development. APIs, tool definitions, and configuration may change without notice.

**Talk to your streaming platform.** StreamsHub MCP gives AI assistants direct access to your Kubernetes-based streaming infrastructure through the [Model Context Protocol](https://modelcontextprotocol.io/). Deploy only the servers you need — each component of your streaming stack gets its own MCP server.

Works with any MCP-compatible client including Claude Code, Claude Desktop, VS Code, Copilot, and more.

## MCP Servers

| Server | Description |
|--------|-------------|
| [Strimzi MCP](strimzi-mcp/) | Manage and troubleshoot Strimzi-managed Kafka clusters on Kubernetes |

Each server has its own README with full documentation — available tools, resource templates, deployment, and configuration.

## Beyond Simple Tools

StreamsHub MCP servers go beyond wrapping APIs as tool calls. They leverage the full MCP specification to create an AI-native operations experience:

- **Prompt templates** — structured diagnostic workflows that guide the LLM step-by-step through complex troubleshooting, telling it exactly which tools to call and in what order
- **Resource templates** — live cluster state exposed as structured context that clients can attach directly to conversations, giving the LLM immediate visibility without requiring tool calls
- **Resource subscriptions** — Kubernetes watches that push real-time notifications when cluster state changes, enabling reactive agents that detect and investigate issues automatically
- **Completions** — dynamic autocomplete for parameters (namespaces, cluster names, topics) powered by live Kubernetes queries
- **Progress and cancellation** — long-running operations like log collection report progress back to the client and support mid-operation cancellation

## Getting Started

Pick an MCP server from the table above and follow its README. Each server can be deployed independently as a standalone application or container.

## Documentation

Comprehensive documentation is available in the [`docs/`](docs/) directory:

**For Users:**
- [Getting Started](docs/user/getting-started.md) — Quick start guide
- [Usage Guide](docs/user/usage-guide.md) — How to use with AI assistants
- [MCP Servers](docs/user/mcp-servers/) — Browse available MCP servers
  - [Strimzi MCP Server](docs/user/mcp-servers/strimzi-mcp/) — Kafka cluster management

**For Developers:**
- [Architecture](docs/developer/architecture.md) — System design and components
- [Building](docs/developer/building.md) — Build and run locally
- [Testing](docs/developer/testing.md) — Unit and system tests
- [Contributing](docs/developer/contributing.md) — Contribution guidelines
- [Deployment](docs/developer/deployment.md) — Kubernetes deployment patterns
- [Module Documentation](docs/developer/modules/) — Shared module internals

## Built With

- [Quarkus](https://quarkus.io/) — cloud-native Java framework
- [Fabric8 Kubernetes Client](https://github.com/fabric8io/kubernetes-client) — Kubernetes API access
- [Quarkus MCP Server](https://docs.quarkiverse.io/quarkus-mcp-server/dev/index.html) — MCP protocol implementation

## Development

```bash
mvn compile              # Compile all modules (includes checkstyle)
mvn test                 # Run unit tests
mvn verify -Psystemtest  # Run system tests (requires Kubernetes cluster)
```

See the [Development Guide](docs/development.md) and [Testing Guide](docs/testing.md) for detailed information.

## Contributing

Contributions are welcome. To get started:

1. Fork the repository and create a feature branch
2. Run `mvn compile` to verify checkstyle compliance
3. Run `mvn test` to ensure all tests pass
4. Open a pull request

## License

[Apache License 2.0](LICENSE)
