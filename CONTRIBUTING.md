# Contributing

Contributions to StreamsHub MCP are welcome.

## Getting started

1. Fork the repository and create a feature branch
2. Make your changes following the code style and patterns in [AGENTS.md](AGENTS.md)
3. Write tests for new functionality
4. Run `mvn compile` to verify checkstyle compliance
5. Run `mvn test` to ensure all tests pass
6. Open a pull request

## Branch naming

Use descriptive branch names:

- `feature/add-kafka-metrics-tool`
- `fix/namespace-discovery-bug`
- `docs/update-installation-guide`

## Build and test

```bash
mvn compile              # Compile and run checkstyle
mvn test                 # Run unit tests
mvn verify -Psystemtest  # Run system tests (requires Kubernetes cluster)
```

## Code style and patterns

All code style rules, architecture patterns, and conventions are documented in [AGENTS.md](AGENTS.md).

Key points:

- Checkstyle runs during compile and must pass
- All public types, methods, and fields require Javadoc
- Use `final` on method parameters
- Use `InputUtils.normalizeInput()` for all user-supplied input
- Every file must end with a newline

## Pull request guidelines

Before submitting:

- [ ] All tests pass (`mvn test`)
- [ ] Checkstyle passes (`mvn compile`)
- [ ] Code is documented (Javadoc on public APIs)
- [ ] Changes are covered by tests
- [ ] User documentation updated if applicable (see `docs/`)
- [ ] Commit messages are clear and descriptive

Provide a clear PR description with a summary of changes, testing performed, and any related issues.

## License

By contributing, you agree that your contributions will be licensed under the [Apache License 2.0](LICENSE).
