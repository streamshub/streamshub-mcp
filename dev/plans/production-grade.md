# StreamsHub MCP: Production Readiness Roadmap

## Context

StreamsHub MCP has a solid foundation (20 tools, 4 prompts, 5 resources, guardrails, pluggable providers) but has critical gaps before production use: no authentication, limited Strimzi CRD coverage (missing Connect, User, Rebalance, MM2, Bridge, DrainCleaner), no self-monitoring, and deployment tooling gaps. This roadmap addresses all gaps identified in the cross-reference of the proposal, deep dive, auth architecture doc, and Strimzi operator capabilities.

**Design decisions made:**
- Auth: Quarkus HTTP security (`quarkus-oidc`) at HTTP layer, optional (off in dev, on in prod)
- Deployment: Kustomize overlays (not Helm), no K8s operator
- All new tools: read-only, following existing patterns (service layer + tool layer + guardrails)

---

## Phase 1: Security Foundation

**Goal:** No anonymous access in production. Per-user identity with K8s propagation. Audit trail.

| Item | What | Effort |
|------|------|--------|
| [1.1](phase-1/1.1-oidc-authentication.md) | OIDC authentication — `quarkus-oidc` on `/mcp/*`, `SecurityIdentity` into `@RequestScoped` `McpSecurityContext` | M |
| [1.2](phase-1/1.2-k8s-impersonation.md) | K8s user impersonation — `Impersonate-User/Group` headers derived from OIDC identity, MCP SA scoped to impersonation permissions | M |
| [1.3](phase-1/1.3-audit-logging.md) | Audit logging — interceptor on `@Tool` methods, structured JSON: user, tool, args, result, duration | S-M |
| [1.4](phase-1/1.4-namespace-filtering.md) | Namespace filtering — `mcp.namespaces` config, filter all queries + watches | M |
| [1.5](phase-1/1.5-json-logging.md) | JSON logging for prod — `quarkus-logging-json`, profile-gated | S |

**Outcome:** MCP endpoint is authenticated in prod, K8s calls use the real user's identity via impersonation, every tool call is auditable, resource visibility is scoped.

---

## Phase 2: Strimzi CRD Coverage Expansion

**Goal:** Cover the Strimzi CRDs customers actually ask about. Connect/Connector is highest priority. Full operand coverage including MM2, Bridge, and DrainCleaner.

| Item | What | Effort |
|------|------|--------|
| [2.1](phase-2/2.1-kafka-connect-connector-tools.md) | **KafkaConnect + KafkaConnector tools** — list/get for Connect clusters and connectors, Connect logs, `diagnose_kafka_connector` composite tool, `troubleshoot-connector` prompt | L |
| [2.2](phase-2/2.2-kafka-user-tools.md) | **KafkaUser tools** — list/get users with auth type, ACLs, quotas; `audit-security` prompt | S-M |
| [2.3](phase-2/2.3-kafka-rebalance-tools.md) | **KafkaRebalance tools** — list/get rebalance proposals with state, goals, optimization result; `assess-upgrade-readiness` prompt | M |
| [2.4](phase-2/2.4-kafka-mirror-maker2-tools.md) | **KafkaMirrorMaker2 tools** — list/get MM2 instances, mirror configs, source/target clusters, replication status, logs | M |
| [2.5](phase-2/2.5-kafka-bridge-tools.md) | **KafkaBridge tools** — list/get bridges, HTTP config, producer/consumer settings | S-M |
| [2.6](phase-2/2.6-drain-cleaner-tools.md) | **DrainCleaner tools** — list/get DrainCleaner deployments, status, webhook config, pod eviction readiness | S-M |
| [2.7](phase-2/2.7-configuration-tools.md) | **Configuration tools** — `get_kafka_cluster_config`, `compare_kafka_clusters` | M |

**Outcome:** MCP can answer "why is my connector failing?", "who has access to what?", "is my cluster ready for upgrade?", "what's the MM2 replication status?", "is DrainCleaner properly configured?", "how do prod and staging differ?"

---

## Phase 3: Production Hardening

**Goal:** Observable, deployable, traceable MCP server.

| Item | What | Effort |
|------|------|--------|
| [3.1](phase-3/3.1-kustomize-overlays.md) | Kustomize overlays — base + dev/prod overlays for RBAC, resources, auth config, namespace scope | M |
| [3.2](phase-3/3.2-server-self-monitoring.md) | Server self-monitoring — Micrometer/Prometheus metrics | M |
| [3.3](phase-3/3.3-opentelemetry-tracing.md) | OpenTelemetry tracing — spans for K8s API calls | M |
| [3.4](phase-3/3.4-jacoco-coverage.md) | JaCoCo code coverage in CI | S |

**Outcome:** MCP is deployable via GitOps, self-monitoring, traceable.

---

## Phase 4: Polish & Completeness

**Goal:** Full prompt/completion coverage, metrics modernization.

| Item | What | Effort |
|------|------|--------|
| [4.1](phase-4/4.1-metrics-reporter-support.md) | Strimzi Metrics Reporter support — preferred provider alongside JMX exporter | M |
| [4.2](phase-4/4.2-additional-prompts.md) | Additional prompts — `troubleshoot-topic`, `analyze-capacity` | M |
| [4.3](phase-4/4.3-tool-argument-completions.md) | Tool argument completions — `@CompleteToolArg` for diagnostics | S-M |
| [4.4](phase-4/4.4-resource-templates-new-crds.md) | Resource templates for new CRDs + subscriptions | M |

**Outcome:** Every Strimzi CRD queryable. Modern metrics pipeline. Full prompt and completion coverage.

---

## Phase 5: Comprehensive E2E Test Coverage

**Goal:** Every tool and key scenario verified end-to-end on a real cluster.

| Item | What | Effort |
|------|------|--------|
| [5.1](phase-5/5.1-e2e-new-crd-tools.md) | E2E for all new CRD tools | L |
| [5.2](phase-5/5.2-e2e-diagnostic-workflows.md) | Diagnostic workflow E2E with injected faults | M |
| [5.3](phase-5/5.3-e2e-auth.md) | Auth E2E with Keycloak on Kind | M |
| [5.4](phase-5/5.4-e2e-namespace-filtering.md) | Namespace filtering E2E | S-M |
| [5.5](phase-5/5.5-e2e-metrics-logging.md) | Metrics & logging E2E | M |
| [5.6](phase-5/5.6-e2e-negative-edge-cases.md) | Negative/edge case scenarios | M |
| [5.7](phase-5/5.7-e2e-prompts-resources.md) | Prompt and resource E2E | S-M |

**Outcome:** Full regression suite. Production confidence.

---

## Effort Key

- **S** = Small (hours to 1 day)
- **S-M** = Small-Medium (1-2 days)
- **M** = Medium (2-4 days)
- **L** = Large (1-2 weeks)

## Estimated Total

- Phase 1: ~2-3 weeks
- Phase 2: ~3-4 weeks
- Phase 3: ~2 weeks
- Phase 4: ~2 weeks
- Phase 5: ~3-4 weeks
