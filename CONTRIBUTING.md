# Contributing

Contributions to StreamsHub MCP are welcome.

## Getting started

1. Fork the repository and create a feature branch
2. Make your changes following the code style and patterns in [AGENTS.md](AGENTS.md)
3. Write tests for new functionality
4. Run `./mvnw compile` to verify checkstyle compliance
5. Run `./mvnw test` to ensure all tests pass
6. Open a pull request

## Branch naming

Use descriptive branch names:

- `feature/add-kafka-metrics-tool`
- `fix/namespace-discovery-bug`
- `docs/update-installation-guide`

## Build and test

```bash
./mvnw compile              # Compile and run checkstyle
./mvnw test                 # Run unit tests
./mvnw verify -Psystemtest  # Run system tests (requires Kubernetes cluster)
```

## Local cluster development

Set up a local Strimzi environment for testing:

```bash
# Deploy Strimzi operator and Kafka cluster
./dev/scripts/setup-strimzi.sh deploy

# With Prometheus for metrics
./dev/scripts/setup-strimzi.sh deploy --prometheus

# Teardown when done
./dev/scripts/setup-strimzi.sh teardown
```

Build the container image and deploy to a local cluster:

```bash
# Build the image
./mvnw package -pl strimzi-mcp -am -DskipTests \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.tag=dev

# Deploy (with Kind, Minikube, or k3d image loading)
./dev/scripts/dev-deploy.sh quay.io/streamshub/strimzi-mcp:dev --kind
./dev/scripts/dev-deploy.sh quay.io/streamshub/strimzi-mcp:dev --minikube
./dev/scripts/dev-deploy.sh quay.io/streamshub/strimzi-mcp:dev --k3d

# Or build and push to a custom registry, then deploy
./mvnw package -pl strimzi-mcp -am -DskipTests \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.push=true \
  -Dquarkus.container-image.registry=quay.io \
  -Dquarkus.container-image.group=your-user \
  -Dquarkus.container-image.name=strimzi-mcp \
  -Dquarkus.container-image.tag=test

./dev/scripts/dev-deploy.sh quay.io/your-user/strimzi-mcp:test
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

- [ ] All tests pass (`./mvnw test`)
- [ ] Checkstyle passes (`./mvnw compile`)
- [ ] Code is documented (Javadoc on public APIs)
- [ ] Changes are covered by tests
- [ ] User documentation updated if applicable (see `docs/`)
- [ ] Commit messages are clear and descriptive

Provide a clear PR description with a summary of changes, testing performed, and any related issues.

## License

By contributing, you agree that your contributions will be licensed under the [Apache License 2.0](LICENSE).
