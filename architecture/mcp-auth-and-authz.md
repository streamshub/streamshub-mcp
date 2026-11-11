# Auth & AuthZ Architecture Design for Multi-MCP Platform

## Context

The StreamsHub MCP platform currently has **zero authentication** on its MCP endpoint. The goal is
to design a unified auth & authz story for multiple MCP servers (Strimzi, Kafka, Console, Apicurio)
running on Kubernetes, with a dedicated Kafka MCP also deployable on VM.

The existing architecture plan (`mcp-architecture-summary.md`) outlines Keycloak PAT + token exchange
+ K8s impersonation as the foundation. This document extends it with concrete design decisions,
module structure, and implementation phases.

---

## Design Decisions

### 1. Auth Strategy: Shared Auth Library in `common/`

Each MCP validates tokens independently via `quarkus-oidc`. No API gateway required. Token exchange
happens per-MCP for its downstream systems.

**Why not a gateway?** Token exchange must happen inside each MCP anyway. A gateway only handles
authn, not credential propagation. VM deployment would be harder. A gateway can be layered later
for TLS termination.

### 2. Authorization: Full Delegation (no MCP-level authz)

MCP makes **no authorization decisions**. It delegates entirely to downstream systems:
- **K8s RBAC** via user impersonation
- **Kafka ACL** via Keycloak Authorization Services
- **Apicurio roles** via JWT claims
- **Console RBAC** via Console's own auth system

MCP catches 403s from downstream and returns clear MCP error messages. No tool-level access control
in MCP -- if a user can authenticate with Keycloak, they can call any tool. Downstream systems
enforce what they can actually do.

### 3. Module Structure: Separate Kafka MCP

Instead of a deployment mode toggle in Strimzi MCP, create a **dedicated `kafka-mcp` module** that
works purely with Kafka protocol (AdminClient, no K8s dependency). Cleaner than conditional bean
activation.

```
streamshub-mcp/
├── common/              (shared auth lib, K8s helpers, metrics)
├── metrics-prometheus/  (Prometheus metrics provider)
├── strimzi-mcp/         (Strimzi/K8s-native MCP -- K8s only)
├── kafka-mcp/           (Pure Kafka MCP -- works on K8s or VM)
├── console-mcp/         (Thin REST adapter to Console API)
└── apicurio-mcp/        (future: Apicurio Registry MCP)
```

### 4. Console MCP: REST API Adapter

Console MCP wraps Console's existing REST API. Token exchange (PAT -> console-audience token)
authenticates against Console. Console enforces all its own auth/authz. No duplication of business
logic.

### 5. MCP Topology: Multiple Endpoints

Each MCP server is a separate endpoint. Claude Code configures each independently:

```json
{
  "mcpServers": {
    "strimzi": { "url": "https://strimzi-mcp.example.com/mcp" },
    "kafka":   { "url": "https://kafka-mcp.example.com/mcp" },
    "console": { "url": "https://console-mcp.example.com/mcp" }
  }
}
```

### 6. Token Type: Keycloak PAT (with offline token fallback for Keycloak < 26)

PAT (Personal Access Token) is the primary credential for Keycloak 26+. User generates a PAT in
Keycloak Account Console, places it in Claude Code config as `Authorization: Bearer <PAT>`. MCP
introspects it on every request.

**For Keycloak < 26 (e.g., 24): Offline tokens as fallback.**

Keycloak 24 does not support PAT. The recommended alternative is offline tokens, which provide
the same properties: per-user identity, long-lived, revocable from Keycloak admin.

Setup flow for Keycloak < 26:

1. User runs a one-time CLI command or opens a browser URL that triggers the `authorization_code`
   flow against Keycloak with `scope=offline_access`
2. Keycloak returns an access token + offline refresh token
3. The offline refresh token is stored in Claude Code's MCP server config as the Bearer credential
4. The MCP auth library detects it is a refresh token, exchanges it for a fresh access token
   transparently (using `quarkus-oidc-client`), and caches until near-expiry

The offline refresh token does not expire unless explicitly revoked by a Keycloak admin. It maps
to a real user identity (`sub`, `email`, `groups`) just like PAT.

| Property             | PAT (Keycloak 26+)       | Offline Token (Keycloak 24+)      |
|----------------------|--------------------------|-----------------------------------|
| Per-user identity    | Yes                      | Yes                               |
| Long-lived           | Yes (until revoked)      | Yes (until revoked)               |
| User self-service    | Yes (Account Console)    | No (admin revokes via Admin API)  |
| Token refresh needed | No (introspect directly) | Yes (auth lib handles it)         |
| Setup UX             | Copy PAT from UI         | One-time browser login + copy     |
| Keycloak version     | 26+                      | 24+                               |

The auth library (`McpSecurityContext` + `quarkus-oidc`) handles both token types transparently.
No code changes needed per deployment -- only Keycloak version determines which credential type
the user obtains. The MCP server does not care whether it receives a PAT or an exchanged access
token; both are valid Bearer tokens that Keycloak can introspect/validate.

### 7. Identity Provider: Keycloak as Single Trust Anchor

Keycloak is always required. External IdPs (GitHub, Google, corporate SAML/LDAP) federate through
Keycloak as identity broker. Every MCP always sees a standard Keycloak JWT with `groups`, `email`,
`sub`. Token exchange works uniformly regardless of upstream IdP. The auth library only speaks
Keycloak OIDC.

### 8. Pluggable Authentication Provider (custom auth plugins)

The auth system follows the same pluggable pattern as `MetricsProvider` — an interface in `common/`,
implementations selected at runtime by a config property. This allows users to write their own
auth plugin to extract credentials and resolve user identity in any custom way.

#### AuthProvider Interface

```java
/**
 * Pluggable interface for resolving user identity from an incoming MCP request.
 * The default implementation uses Keycloak OIDC (quarkus-oidc SecurityIdentity).
 * Custom implementations can extract credentials from headers, mutual TLS,
 * external vaults, or any other source.
 */
public interface AuthProvider {

    /**
     * Resolves the authenticated user identity from the current request context.
     *
     * @return the resolved user identity (user ID, email, groups)
     * @throws AuthenticationException if credentials are missing or invalid
     */
    McpIdentity resolveIdentity();

    /**
     * Obtains a credential scoped to the given audience for downstream calls.
     * Returns a typed credential that can be a bearer token, username/password, etc.
     *
     * @param audience the target audience (e.g., "kafka", "streamshub-console")
     * @return a typed credential for authenticating to the downstream system
     * @throws TokenExchangeException if credential resolution fails
     */
    McpCredential getCredentialForAudience(String audience);
}
```

#### McpIdentity Record

```java
/**
 * Represents an authenticated user identity resolved by an {@link AuthProvider}.
 *
 * @param userId  unique user identifier (e.g., Keycloak "sub" claim, or SCRAM username)
 * @param email   user email address (may be null for SCRAM-only deployments)
 * @param groups  set of group/role memberships for authorization delegation
 */
public record McpIdentity(
    String userId,
    String email,
    Set<String> groups
) { }
```

#### McpCredential (typed credential hierarchy)

```java
/**
 * Typed credential for downstream system authentication.
 * Each subtype represents a different authentication mechanism.
 */
public sealed interface McpCredential {

    /**
     * OAuth2 / OIDC bearer token credential.
     * Used with Keycloak token exchange, OAUTHBEARER Kafka, Console REST API.
     */
    record BearerToken(String token) implements McpCredential { }

    /**
     * Username + password credential.
     * Used with SCRAM-SHA-256/512 Kafka, PLAIN SASL, basic auth REST APIs.
     */
    record UsernamePassword(String username, String password) implements McpCredential { }

    /**
     * Client certificate credential (mTLS).
     * Used when downstream requires mutual TLS authentication.
     */
    record ClientCertificate(String certPath, String keyPath) implements McpCredential { }
}
```

Services that talk to downstream systems inspect the credential type:

```java
// Example: configuring Kafka AdminClient based on credential type
switch (credential) {
    case McpCredential.BearerToken bt -> {
        props.put("sasl.mechanism", "OAUTHBEARER");
        props.put("sasl.jaas.config", oauthJaasConfig(bt.token()));
    }
    case McpCredential.UsernamePassword up -> {
        props.put("sasl.mechanism", "SCRAM-SHA-512");
        props.put("sasl.jaas.config", scramJaasConfig(up.username(), up.password()));
    }
    case McpCredential.ClientCertificate cc -> {
        props.put("security.protocol", "SSL");
        props.put("ssl.keystore.location", cc.certPath());
    }
}
```

#### Built-in Implementations

```java
// Scenario A: Keycloak OIDC (full identity + token exchange)
@ApplicationScoped
@LookupIfProperty(name = "mcp.auth.provider", stringValue = "keycloak")
public class KeycloakAuthProvider implements AuthProvider {
    @Inject SecurityIdentity identity;
    @Inject TokenExchangeService tokenExchange;

    // resolveIdentity(): extracts sub, email, groups from JWT claims
    // getCredentialForAudience(): token exchange -> returns BearerToken
}

// Scenario B: SCRAM passthrough (no Keycloak, credentials from request headers)
@ApplicationScoped
@LookupIfProperty(name = "mcp.auth.provider", stringValue = "scram")
public class ScramAuthProvider implements AuthProvider {
    // resolveIdentity(): extracts SCRAM username from request header,
    //   returns McpIdentity(username, null, Set.of())
    // getCredentialForAudience("kafka"): returns UsernamePassword from request headers
}

// Static token: for development/testing
@ApplicationScoped
@LookupIfProperty(name = "mcp.auth.provider", stringValue = "static-token")
public class StaticTokenAuthProvider implements AuthProvider {
    // Reads fixed credentials from config. Useful for dev/test.
}
```

#### Writing a Custom Auth Plugin

Users can provide their own `AuthProvider` implementation as a JAR on the classpath (same pattern
as the `metrics-prometheus` module provides `PrometheusMetricsProvider`).

**Example: Custom vault-based auth plugin**

```java
package com.example.mcp.auth;

import io.streamshub.mcp.common.auth.AuthProvider;
import io.streamshub.mcp.common.auth.McpIdentity;
import io.quarkus.arc.lookup.LookupIfProperty;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
@LookupIfProperty(name = "mcp.auth.provider", stringValue = "vault")
public class VaultAuthProvider implements AuthProvider {

    @Override
    public McpIdentity resolveIdentity() {
        // Extract API key from request header
        // Look up user identity in HashiCorp Vault or custom user store
        // Return McpIdentity with resolved userId, email, groups
    }

    @Override
    public McpCredential getCredentialForAudience(String audience) {
        // Fetch audience-scoped credential from Vault
        // Could return BearerToken, UsernamePassword, or ClientCertificate
        // depending on what the downstream system expects
    }
}
```

**To use a custom plugin:**

1. Implement `AuthProvider` in a separate Maven module
2. Annotate with `@LookupIfProperty(name = "mcp.auth.provider", stringValue = "your-name")`
3. Add the JAR to the MCP server classpath (or as a Maven dependency)
4. Set `mcp.auth.provider=your-name` in `application.properties` or environment variable

```properties
# Switch auth provider (default: keycloak)
mcp.auth.provider=vault

# Custom plugin config (plugin reads its own properties)
mcp.auth.vault.address=https://vault.example.com
mcp.auth.vault.role=mcp-server
```

#### How Services Use It

Domain services and tools never interact with `AuthProvider` directly. They inject
`McpSecurityContext`, which delegates to the active `AuthProvider` internally:

```
@Tool method
    → injects McpSecurityContext
        → McpSecurityContext calls AuthProvider.resolveIdentity()
            → returns McpIdentity (userId, email, groups)

Downstream call (e.g., Kafka):
    → service calls McpSecurityContext.getCredentialForAudience("kafka")
        → McpSecurityContext delegates to AuthProvider.getCredentialForAudience("kafka")
            → Keycloak provider: returns BearerToken (from token exchange)
            → SCRAM provider:    returns UsernamePassword (from request headers)
        → service configures Kafka client based on credential type
```

This keeps tools and services decoupled from any specific auth mechanism. Swapping Keycloak for
SCRAM (or a custom provider) requires zero changes to tool or service code -- only the
`mcp.auth.provider` config property changes.

#### Auth Provider Package Structure

```
io.streamshub.mcp.common.auth/
├── AuthProvider.java                  Pluggable interface
├── McpIdentity.java                   User identity record
├── McpCredential.java                 Sealed interface: BearerToken | UsernamePassword | ClientCertificate
├── McpSecurityContext.java            @RequestScoped facade, delegates to active AuthProvider
├── KeycloakAuthProvider.java          Keycloak OIDC (PAT introspect + token exchange)
├── ScramAuthProvider.java             SCRAM passthrough (lives in kafka-mcp, not common)
├── StaticTokenAuthProvider.java       Dev/test (fixed credentials from config)
├── TokenExchangeService.java          RFC 8693 token exchange (used by KeycloakAuthProvider)
└── KubernetesImpersonationService.java  K8s impersonation (uses McpIdentity)
```

Config property: `mcp.auth.provider` (default: `keycloak`)

---

## Deployment Scenarios

The platform supports two fundamentally different auth deployment models, selected by the
`mcp.auth.provider` config property. The pluggable `AuthProvider` interface ensures tool and
service code is identical in both scenarios.

### Scenario A: Keycloak OIDC (`mcp.auth.provider=keycloak`)

Full OIDC stack with per-user identity, token exchange, and K8s impersonation.
Used by all MCP servers (Strimzi, Kafka, Console, Apicurio).

```
  ┌─────────────────────────────────────────────────────────────────────────┐
  │                   External Identity Providers (optional)               │
  │                   (GitHub, Google, SAML, LDAP, ...)                    │
  └────────────────────────────────┬────────────────────────────────────────┘
                                   │ federation / brokering
                                   v
                          ┌─────────────────┐
                          │    Keycloak      │
                          │  (Realm: firma)  │
                          └────────┬────────┘
                                   │ issues PAT / access token
                                   v
                          Claude Code / CI
                          Bearer: <PAT>
                                   │
                     ┌─────────────┼─────────────┐
                     v             v             v
               ┌──────────┐ ┌──────────┐ ┌──────────┐
               │ Strimzi  │ │  Kafka   │ │ Console  │
               │   MCP    │ │   MCP    │ │   MCP    │
               │ Keycloak │ │ Keycloak │ │ Keycloak │
               │ AuthProv.│ │ AuthProv.│ │ AuthProv.│
               └────┬─────┘ └────┬─────┘ └────┬─────┘
                    │             │             │
          ┌────────┴──┐          │        ┌────┘
          v           v          v        v
       K8s API     Kafka      Kafka   Console
      (imperson.) (OAUTHBEARER)      REST API
                  (token exch.)      (token exch.)

  Identity: per-user (sub, email, groups from JWT)
  AuthZ: delegated to K8s RBAC, Kafka ACL, Console RBAC, Apicurio roles
  Credential type: BearerToken (via McpCredential)
```

**Config:**
```properties
mcp.auth.provider=keycloak
quarkus.oidc.auth-server-url=https://keycloak.example.com/realms/firma
quarkus.oidc.client-id=k8s-mcp-server
```

### Scenario B: SCRAM-SHA Passthrough (`mcp.auth.provider=scram`)

No Keycloak. **Kafka MCP only.** Caller passes Kafka SCRAM credentials via request headers.
MCP uses them per-request. This scenario is for the `kafka-mcp` module exclusively --
Strimzi MCP, Console MCP, and Apicurio MCP always require Keycloak (Scenario A).

```
                          Claude Code / CI
                          X-Kafka-User: honza
                          X-Kafka-Password: ****
                                   │
                                   v
                             ┌──────────┐
                             │  Kafka   │
                             │   MCP    │
                             │  SCRAM   │
                             │ AuthProv.│
                             └────┬─────┘
                                  │
                                  v
                            Kafka Brokers
                            (SCRAM-SHA-512)
                            (passthrough)

  Identity: SCRAM username (no email/groups unless mapped externally)
  AuthZ: Kafka ACL keyed on SCRAM username
  Credential type: UsernamePassword (via McpCredential)
```

**Config:**
```properties
mcp.auth.provider=scram
# MCP endpoint protection: TLS + network policy (no OIDC)
# Kafka connection
mcp.kafka.bootstrap-servers=kafka.example.com:9093
mcp.kafka.security.protocol=SASL_SSL
```

**How SCRAM passthrough works:**
1. Claude Code sends SCRAM credentials in request headers (over TLS)
2. `ScramAuthProvider.resolveIdentity()` extracts username from header
   -> returns `McpIdentity(username, null, Set.of())`
3. `ScramAuthProvider.getCredentialForAudience("kafka")` returns
   `McpCredential.UsernamePassword(username, password)` from headers
4. Kafka service builds AdminClient with SCRAM-SHA JAAS config using the credential
5. Kafka broker enforces ACLs based on the SCRAM principal

**Security notes for SCRAM passthrough:**
- MCP endpoint MUST use TLS (credentials transit in headers)
- Consider network policies to restrict who can reach the MCP endpoint
- Credentials are per-request, never stored by MCP

### Scenario Comparison

| Aspect                   | Keycloak OIDC              | SCRAM Passthrough           |
|--------------------------|----------------------------|-----------------------------|
| Applicable MCPs          | All (Strimzi, Kafka, etc.) | Kafka MCP only              |
| Identity provider        | Keycloak (required)        | None                        |
| MCP endpoint auth        | OIDC token validation      | TLS + network policy        |
| Kafka auth mechanism     | OAUTHBEARER (token exch.)  | SCRAM-SHA-256/512           |
| Per-user identity        | Full (sub, email, groups)  | Username only               |
| Token management         | PAT / offline token        | Static credentials          |
| Multi-tenant support     | Yes (groups -> ACL)        | Limited (per-username ACL)  |
| Infrastructure required  | Keycloak + OIDC setup      | TLS only                    |
| Best for                 | Production, multi-team     | Dev, single-team, VM        |

---

## Architecture Overview (Keycloak OIDC -- primary deployment)

```
  ┌───────────────────────────────────────────────────────────────────────────┐
  │                        External Identity Providers                       │
  │                   (GitHub, Google, SAML, LDAP, ...)                      │
  └──────────────────────────────┬────────────────────────────────────────────┘
                                 │ federation / brokering
                                 v
                        ┌─────────────────┐
                        │    Keycloak      │
                        │  (Realm: firma)  │
                        │                  │
                        │  Clients:        │
                        │  - k8s-mcp       │
                        │  - kafka         │
                        │  - console       │
                        │  - apicurio      │
                        │  - kubernetes    │
                        │                  │
                        │  Groups:         │
                        │  - platform-admin│
                        │  - kafka-dev     │
                        │  - ns-<name>-rw  │
                        │  - readonly      │
                        └────────┬────────┘
                                 │
                     ┌───────────┼────────────┐
                     │   PAT / Access Token   │
                     │  (issued by Keycloak)  │
                     └───────────┬────────────┘
                                 │
              User / Claude Code / CI Pipeline
              sends: Authorization: Bearer <PAT>
                                 │
        ┌────────────┬───────────┼───────────┬────────────┐
        v            v           v           v            v
  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌────────┐
  │ Strimzi  │ │  Kafka   │ │ Console  │ │ Apicurio │ │  ...   │
  │   MCP    │ │   MCP    │ │   MCP    │ │   MCP    │ │ future │
  │ (K8s     │ │ (K8s or  │ │          │ │          │ │        │
  │  only)   │ │   VM)    │ │          │ │          │ │        │
  └─────┬────┘ └─────┬────┘ └─────┬────┘ └─────┬────┘ └────────┘
        │            │            │            │
  ┌─────┘            │            │            └──────┐
  │ Each MCP:        │            │                   │
  │ 1. Validates PAT against Keycloak (introspection) │
  │ 2. Extracts user identity (sub, email, groups)    │
  │ 3. Performs token exchange for downstream calls   │
  └─────┬────────────┼────────────┼───────────────────┘
        │            │            │            │
        v            v            v            v
  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐
  │ K8s API  │ │  Kafka   │ │ Console  │ │ Apicurio │
  │ Server   │ │ Brokers  │ │ REST API │ │ Registry │
  │          │ │          │ │          │ │          │
  │ AuthZ:   │ │ AuthZ:   │ │ AuthZ:   │ │ AuthZ:   │
  │ K8s RBAC │ │ Kafka ACL│ │ Console  │ │ JWT role │
  │ via      │ │ via      │ │ own RBAC │ │ mapping  │
  │ imperson.│ │ Keycloak │ │ (subjects│ │          │
  │          │ │ AuthZ    │ │  -> roles│ │          │
  │          │ │ Services │ │  -> rules│ │          │
  └──────────┘ └──────────┘ └──────────┘ └──────────┘
```

---

## Detailed Authentication Flows

### Flow 1: Strimzi MCP (K8s only)

```
Claude Code                Strimzi MCP             Keycloak              K8s API / Kafka
    │                          │                       │                    │
    │  Bearer: <PAT>           │                       │                    │
    │─────────────────────────>│                       │                    │
    │                          │                       │                    │
    │                          │  introspect(PAT)      │                    │
    │                          │──────────────────────>│                    │
    │                          │  {sub, email, groups} │                    │
    │                          │<──────────────────────│                    │
    │                          │                       │                    │
    │                          │  K8s call with impersonation headers:      │
    │                          │  Impersonate-User: honza@firma.cz         │
    │                          │  Impersonate-Group: kafka-dev             │
    │                          │──────────────────────────────────────────>│
    │                          │                       │    K8s RBAC check  │
    │                          │  K8s response         │                    │
    │                          │<──────────────────────────────────────────│
    │                          │                       │                    │
    │                          │  (if Kafka call needed)                    │
    │                          │  token_exchange(PAT,  │                    │
    │                          │   audience=kafka)     │                    │
    │                          │──────────────────────>│                    │
    │                          │  kafka_access_token   │                    │
    │                          │<──────────────────────│                    │
    │                          │                       │                    │
    │                          │  AdminClient(OAUTHBEARER, kafka_token)     │
    │                          │──────────────────────────────────────────>│
    │                          │                       │  Kafka ACL check   │
    │  MCP result              │                       │                    │
    │<─────────────────────────│                       │                    │
```

### Flow 2: Kafka MCP (K8s or VM)

```
Claude Code                Kafka MCP               Keycloak              Kafka Brokers
    │                          │                       │                    │
    │  Bearer: <PAT>           │                       │                    │
    │─────────────────────────>│                       │                    │
    │                          │                       │                    │
    │                          │  introspect(PAT)      │                    │
    │                          │──────────────────────>│                    │
    │                          │  {sub, email, groups} │                    │
    │                          │<──────────────────────│                    │
    │                          │                       │                    │
    │                          │  token_exchange(PAT,  │                    │
    │                          │   audience=kafka)     │                    │
    │                          │──────────────────────>│                    │
    │                          │  kafka_access_token   │                    │
    │                          │<──────────────────────│                    │
    │                          │                       │                    │
    │                          │  AdminClient(OAUTHBEARER, kafka_token)     │
    │                          │──────────────────────────────────────────>│
    │                          │                       │  Kafka ACL check   │
    │  MCP result              │                       │                    │
    │<─────────────────────────│                       │                    │
```

No K8s dependency. Connects directly to Kafka bootstrap servers via config.

### Flow 3: Console MCP

```
Claude Code                Console MCP             Keycloak              Console REST API
    │                          │                       │                    │
    │  Bearer: <PAT>           │                       │                    │
    │─────────────────────────>│                       │                    │
    │                          │                       │                    │
    │                          │  introspect(PAT)      │                    │
    │                          │──────────────────────>│                    │
    │                          │  {sub, email, groups} │                    │
    │                          │<──────────────────────│                    │
    │                          │                       │                    │
    │                          │  token_exchange(PAT,  │                    │
    │                          │  aud=streamshub-      │                    │
    │                          │      console)         │                    │
    │                          │──────────────────────>│                    │
    │                          │  console_token        │                    │
    │                          │<──────────────────────│                    │
    │                          │                       │                    │
    │                          │  GET /api/kafkas/{id}/topics              │
    │                          │  Authorization: Bearer <console_token>    │
    │                          │──────────────────────────────────────────>│
    │                          │                       │  Console RBAC      │
    │  MCP result              │                       │                    │
    │<─────────────────────────│                       │                    │
```

Console handles all business logic and its own RBAC (subjects -> roles -> rules).

---

## Shared Auth Library (`common/`)

### Package Structure

See the full `AuthProvider` plugin architecture in section 8 ("Pluggable Authentication Provider")
above. Summary:

```
io.streamshub.mcp.common.auth/
├── AuthProvider.java                  Pluggable interface (resolveIdentity, getTokenForAudience)
├── McpIdentity.java                   Immutable record (userId, email, groups)
├── McpSecurityContext.java            @RequestScoped facade, delegates to active AuthProvider
├── KeycloakAuthProvider.java          Default: quarkus-oidc + token exchange
├── StaticTokenAuthProvider.java       Dev/test: static credentials from config
├── TokenExchangeService.java          RFC 8693, caches per (user_sub, audience)
└── KubernetesImpersonationService.java Impersonated fabric8 clients (strimzi-mcp only)

io.streamshub.mcp.common.audit/
├── AuditLogger.java                   CDI interceptor on @Tool methods
│                                      Structured JSON: user, tool, args, status, duration
└── AuditEvent.java                    Immutable record for audit entries
```

Custom plugins: implement `AuthProvider`, use `@LookupIfProperty(name = "mcp.auth.provider",
stringValue = "your-name")`, set `mcp.auth.provider=your-name` in config.

### MCP Endpoint Protection (HTTP-level auth on `/mcp`)

The MCP server listens on an HTTP endpoint (`/mcp`). "Endpoint protection" means requiring a valid
Bearer token on every HTTP request to this path. This is standard Quarkus HTTP security -- not
MCP-protocol-specific. Without it, anyone who can reach the endpoint can call any tool.

Quarkus OIDC validates the token against Keycloak automatically. Health endpoints stay open so
K8s liveness/readiness probes work without credentials.

```properties
# Require valid Bearer token on all MCP requests
quarkus.http.auth.permission.mcp.paths=/mcp/*
quarkus.http.auth.permission.mcp.policy=authenticated

# Health/readiness endpoints remain open (K8s probes)
quarkus.http.auth.permission.health.paths=/q/*
quarkus.http.auth.permission.health.policy=permit
```

### McpSecurityContext

```java
@RequestScoped
public class McpSecurityContext {
    @Inject SecurityIdentity identity;

    public String getUserId()        { /* from "sub" claim   */ }
    public String getEmail()         { /* from "email" claim  */ }
    public Set<String> getGroups()   { /* from "groups" claim */ }
}
```

### K8s Impersonation

```java
// strimzi-mcp only
Config impersonated = new ConfigBuilder(base.getConfiguration())
    .withImpersonateUsername(securityContext.getEmail())
    .withImpersonateGroups(securityContext.getGroups())
    .build();
```

MCP ServiceAccount ClusterRole reduced to impersonation-only:

```yaml
rules:
  - apiGroups: [""]
    resources: ["users", "groups"]
    verbs: ["impersonate"]
```

### Token Audience Isolation

Each component accepts only tokens scoped to its own audience:

| Component          | Required token audience  |
|--------------------|--------------------------|
| Kubernetes         | kubernetes               |
| Kafka              | kafka                    |
| MCP Server         | k8s-mcp-server           |
| Apicurio Registry  | apicurio-registry        |
| StreamsHub Console | streamshub-console       |

### Keycloak Realm Structure

```
Realm: firma
├── Clients
│   ├── kubernetes              (K8s API server OIDC)
│   ├── kafka                   (+ Keycloak Authorization Services enabled)
│   ├── apicurio-registry
│   ├── streamshub-console
│   └── k8s-mcp-server          (+ Standard Token Exchange enabled)
│
├── Groups
│   ├── platform-admin          -> K8s cluster-admin, kafka-admin, sr-admin
│   ├── kafka-dev               -> Kafka R/W on dev topics, sr-developer
│   ├── ns-<name>-rw            -> K8s RoleBinding in specific namespace
│   └── readonly                -> sr-readonly, Kafka read-only
│
├── Identity Providers          (brokered)
│   ├── github
│   ├── google
│   └── corporate-saml
│
└── Users
    └── honza@firma.cz
        groups: [kafka-dev, ns-honza-rw]
```

JWT `groups` claim propagates automatically. Adding a user to a Keycloak group immediately
updates their access across all components.

---

## Implementation Phases

### Phase 1: Basic Authentication + Audit

**Goal:** All MCP endpoints require authentication. Tool calls are audit-logged.

**Files to modify/create:**
- `common/pom.xml` -- add `quarkus-oidc`
- `common/.../auth/McpSecurityContext.java` -- new
- `common/.../audit/AuditLogger.java` -- new
- `common/.../audit/AuditEvent.java` -- new
- `strimzi-mcp/src/main/resources/application.properties` -- OIDC config + auth permissions

**Result:** MCP requires valid Bearer token. All authenticated users can call all tools. Audit
log captures every tool invocation with user identity.

**Verification:** Deploy with `quarkus-test-keycloak` dev services. Verify 401 for unauthenticated
requests, success for authenticated, and audit log entries.

### Phase 2: K8s Impersonation + Kafka Token Exchange

**Goal:** K8s calls use real user identity. Kafka calls use audience-scoped tokens.

**Files to modify/create:**
- `common/pom.xml` -- add `quarkus-oidc-client`
- `common/.../auth/TokenExchangeService.java` -- new
- `common/.../auth/KubernetesImpersonationService.java` -- new
- `common/.../service/KubernetesResourceService.java` -- refactor to accept impersonated client
- `strimzi-mcp/install/003-ClusterRole.yaml` -- reduce to impersonation-only

**Result:** Downstream authorization is per-user. MCP never operates under its own SA privileges
for user-facing operations.

**Verification:** Deploy with Keycloak + K8s OIDC. Verify K8s audit logs show impersonated user.
Verify Kafka ACLs are enforced per user.

### Phase 3: Kafka MCP Module

**Goal:** Pure Kafka MCP that works on K8s or VM.

**New module:**
- `kafka-mcp/pom.xml` -- depends on `common/`, no K8s client dependency
- `kafka-mcp/.../tool/KafkaAdminTools.java` -- topic, group, cluster, ACL tools
- `kafka-mcp/.../service/KafkaAdminService.java` -- AdminClient operations
- `kafka-mcp/.../config/KafkaConnectionConfig.java` -- bootstrap servers, SASL config

**Result:** Kafka operations via MCP without K8s dependency. Auth via Keycloak + token exchange.

**Verification:** Deploy on VM with Kafka + Keycloak. Verify topic listing, consumer group
operations with per-user ACL enforcement.

### Phase 4: Console MCP Module

**Goal:** Console functionality via MCP, auth delegated to Console.

**New module:**
- `console-mcp/pom.xml` -- depends on `common/`, uses `quarkus-rest-client`
- `console-mcp/.../tool/ConsoleTools.java` -- thin REST API wrappers
- `console-mcp/.../service/ConsoleClient.java` -- REST client to Console API

**Result:** Console tools available via MCP. Console enforces its own RBAC.

**Verification:** Deploy alongside Console. Verify tools work with proper auth delegation.
Verify Console RBAC restricts access correctly.

---

## Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| `SecurityIdentity` not propagated in Quarkus MCP server threads | Validate early in Phase 1 with integration test |
| K8s impersonation restricted on managed clusters (EKS/AKS/GKE) | Document requirements; fall back to SA-based access with clear warning |
| Keycloak PAT requires v26+ | Offline tokens (Keycloak 24+) as fallback; auth lib handles both transparently |
| Token exchange caching complexity | Use `quarkus-oidc-client` built-in `Tokens` with expiry-aware refresh |
| Console API changes break Console MCP | Version Console REST client; integration tests catch breaking changes |

---

## Grant Type Summary

| Use case                   | Grant type          | Identity       | State stored |
|----------------------------|---------------------|----------------|--------------|
| Claude Code -> MCP (human) | PAT / authz_code    | Real user      | Keycloak     |
| CI/CD pipeline -> MCP      | client_credentials  | Service account| None         |
| MCP -> Kafka               | token_exchange      | Delegated user | None         |
| MCP -> Console             | token_exchange      | Delegated user | None         |
| MCP -> Apicurio            | token_exchange      | Delegated user | None         |
| MCP -> K8s API             | impersonation       | Delegated user | None         |

---

## MCP Server Responsibilities

| Responsibility      | MCP Server does it? | Delegated to              |
|----------------------|---------------------|---------------------------|
| Validate identity    | Yes (PAT introspect) | Keycloak                 |
| K8s authorization    | No                  | K8s RBAC via impersonation |
| Kafka authorization  | No                  | Keycloak AuthZ Services   |
| Console authorization| No                  | Console RBAC              |
| Apicurio authorization| No                 | Apicurio + JWT roles      |
| Audit logging        | Yes                 | --                        |
| Token exchange       | Yes                 | Keycloak                  |
| Rate limiting        | Yes (future)        | --                        |