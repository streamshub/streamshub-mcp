# StreamsHub MCP Server - Tool Test Plan

This test plan is designed to be executed by a code agent (Claude Code, GPT, Copilot, etc.)
connected to the StreamsHub MCP server via MCP protocol. The server must be running against
a dev Kubernetes cluster provisioned using the project's `dev/manifests/` and `dev/scripts/setup-strimzi.sh`.

> **Note:** This test plan targets OpenShift. For plain Kubernetes, the same tests apply
> except: omit `--ocp` from the deploy command, and skip checks related to `route` listeners
> (routes are an OpenShift-only feature).

## Dev Deployment Reference

The dev environment is deployed via:

```bash
# Full deployment with all optional components
./dev/scripts/setup-strimzi.sh deploy \
  --drain-cleaner --connect --bridge --mirror-maker --loki --ocp \
  --test-clients --jaeger
```

### Namespaces

| Namespace               | Contents                                      |
|-------------------------|-----------------------------------------------|
| `strimzi`               | Strimzi Cluster Operator                      |
| `strimzi-kafka`         | Main Kafka cluster, topics, users, Connect, Bridge, test clients |
| `strimzi-kafka-mirror`  | Mirror Kafka cluster (`mcp-cluster-mirror`)   |
| `strimzi-mirror-maker`  | KafkaMirrorMaker2 (`mcp-mirror-maker`)        |
| `strimzi-drain-cleaner` | Strimzi Drain Cleaner                         |

### Resources Deployed

**Always deployed (core):**

| Kind           | Name                | Namespace       | Details                                           |
|----------------|---------------------|-----------------|---------------------------------------------------|
| Kafka          | `mcp-cluster`       | `strimzi-kafka` | v4.2.0, 4 listeners (plain/tls/route/console), Cruise Control, Kafka Exporter |
| KafkaNodePool  | `controller-np`     | `strimzi-kafka` | 3 replicas, role: controller                      |
| KafkaNodePool  | `broker-np1`        | `strimzi-kafka` | 3 replicas, role: broker, 2x50Gi JBOD             |
| KafkaNodePool  | `broker-np2`        | `strimzi-kafka` | 3 replicas, role: broker, 2x50Gi JBOD             |
| KafkaUser      | `mcp-scram-user`    | `strimzi-kafka` | SCRAM-SHA-512, topic prefix `orders-`, quotas      |
| KafkaUser      | `mcp-tls-user`      | `strimzi-kafka` | TLS, read-all topics/groups                        |
| KafkaUser      | `mcp-admin-user`    | `strimzi-kafka` | SCRAM-SHA-512, cluster-wide admin (intentionally overpermissive) |
| Deployment     | `strimzi-cluster-operator` | `strimzi` | Strimzi operator                             |

**Deployed with `--test-clients`:**

| Kind           | Name                | Namespace       | Details                                           |
|----------------|---------------------|-----------------|---------------------------------------------------|
| KafkaTopic     | `mcp-test-topic`    | `strimzi-kafka` | 3 partitions, 3 replicas                           |
| KafkaUser      | `mcp-test-client`   | `strimzi-kafka` | TLS, access to `mcp-test-topic`                    |

**Deployed with `--connect`:**

| Kind           | Name                   | Namespace       | Details                                        |
|----------------|------------------------|-----------------|------------------------------------------------|
| KafkaConnect   | `mcp-connect`          | `strimzi-kafka` | 1 replica, Camel Timer Source plugin            |
| KafkaConnector | `mcp-timer-source`     | `strimzi-kafka` | Camel Timer Source, 1 task, emits every 60s     |
| KafkaTopic     | `mcp-connect-timer-topic` | `strimzi-kafka` | 3 partitions, 3 replicas                    |
| KafkaUser      | `mcp-connect-user`     | `strimzi-kafka` | TLS auth for Connect                            |

**Deployed with `--bridge`:**

| Kind           | Name                   | Namespace       | Details                                        |
|----------------|------------------------|-----------------|------------------------------------------------|
| KafkaBridge    | `mcp-bridge`           | `strimzi-kafka` | 1 replica, HTTP port 8080                       |
| KafkaTopic     | `mcp-bridge-topic`     | `strimzi-kafka` | 3 partitions, 3 replicas                        |
| KafkaUser      | `mcp-bridge-user`      | `strimzi-kafka` | TLS auth for Bridge                              |

**Deployed with `--mirror-maker`:**

| Kind              | Name                  | Namespace               | Details                                  |
|-------------------|-----------------------|-------------------------|------------------------------------------|
| Kafka             | `mcp-cluster-mirror`  | `strimzi-kafka-mirror`  | v4.2.0, 1 combined node, plain listener only, RF=1 |
| KafkaNodePool     | `mirror-combined`     | `strimzi-kafka-mirror`  | 1 replica, roles: controller+broker       |
| KafkaMirrorMaker2 | `mcp-mirror-maker`    | `strimzi-mirror-maker`  | 1 replica, source: mcp-cluster, target: mcp-cluster-mirror |
| KafkaUser         | `mm2-user`            | `strimzi-kafka`         | SCRAM-SHA-512, cluster admin for MM2      |
| KafkaTopic        | `source-orders`       | `strimzi-kafka`         | 3 partitions, 3 replicas, 24h retention   |
| KafkaTopic        | `source-events`       | `strimzi-kafka`         | 3 partitions, 3 replicas, 24h retention   |

**Deployed with `--drain-cleaner`:**

| Kind           | Name                     | Namespace               | Details                                 |
|----------------|--------------------------|-------------------------|-----------------------------------------|
| Deployment     | `strimzi-drain-cleaner`  | `strimzi-drain-cleaner` | Webhook-based drain handler              |

**No KafkaRebalance CR** is included in dev manifests (Cruise Control is enabled but no
rebalance proposal is pre-created).

---

## Test Execution Instructions

For each test case:

1. Call the specified MCP tool with the given parameters
2. Verify the response against the **expected result** criteria
3. Record: PASS / FAIL / SKIP (with reason)
4. If FAIL, capture the error message or unexpected response

Tests are organized into phases. Phases 1-8 test core resources (always available).
Phases 9-13 require optional components (marked with `[requires: --flag]`).
Phase 14+ are cross-cutting.

---

## Phase 1: Fleet Overview and Discovery

### T1.1 - Fleet Overview (all namespaces)

```
Tool: get_kafka_fleet_overview
Parameters: {}
```

**Expected:**
- [ ] Response lists at least 1 Kafka cluster (`mcp-cluster` in `strimzi-kafka`)
- [ ] If `--mirror-maker` deployed: also lists `mcp-cluster-mirror` in `strimzi-kafka-mirror`
- [ ] Each cluster entry includes: name, namespace, status, broker count
- [ ] `mcp-cluster` shows 6 brokers (broker-np1: 3 + broker-np2: 3)
- [ ] Status values are one of: Ready, NotReady, Unknown

### T1.2 - Fleet Overview (namespace-scoped)

```
Tool: get_kafka_fleet_overview
Parameters: { "namespace": "strimzi-kafka" }
```

**Expected:**
- [ ] Returns only `mcp-cluster`
- [ ] `mcp-cluster-mirror` is NOT listed (it is in `strimzi-kafka-mirror`)

### T1.3 - List Kafka Clusters (all)

```
Tool: list_kafka_clusters
Parameters: {}
```

**Expected:**
- [ ] Returns array containing `mcp-cluster` (namespace: `strimzi-kafka`)
- [ ] If `--mirror-maker` deployed: also contains `mcp-cluster-mirror` (namespace: `strimzi-kafka-mirror`)
- [ ] Each object has: name, namespace, status, version (`4.2.0`), listeners

### T1.4 - List Kafka Clusters (namespace-scoped)

```
Tool: list_kafka_clusters
Parameters: { "namespace": "strimzi-kafka" }
```

**Expected:**
- [ ] Returns only `mcp-cluster`
- [ ] Namespace field is `strimzi-kafka`

### T1.5 - Cluster Overview (mcp-cluster)

```
Tool: get_strimzi_kafka_cluster_overview
Parameters: { "clusterName": "mcp-cluster" }
```

**Expected:**
- [ ] Cluster status is Ready
- [ ] Node pools section lists 3 pools: `controller-np` (3 controllers), `broker-np1` (3 brokers), `broker-np2` (3 brokers)
- [ ] Topics section shows count >= 1 (at least `mcp-test-topic` if test-clients deployed)
- [ ] Users section shows count >= 3 (`mcp-scram-user`, `mcp-tls-user`, `mcp-admin-user`)
- [ ] If `--connect` deployed: KafkaConnect section lists `mcp-connect`
- [ ] If `--bridge` deployed: KafkaBridge section lists `mcp-bridge`
- [ ] Strimzi operator info references `strimzi-cluster-operator`

### T1.6 - Cluster Overview (nonexistent)

```
Tool: get_strimzi_kafka_cluster_overview
Parameters: { "clusterName": "nonexistent-cluster-xyz" }
```

**Expected:**
- [ ] Returns descriptive error (not a stack trace)

---

## Phase 2: Kafka Cluster Tools

### T2.1 - Get Kafka Cluster Details

```
Tool: get_kafka_cluster
Parameters: { "clusterName": "mcp-cluster" }
```

**Expected:**
- [ ] Name: `mcp-cluster`, namespace: `strimzi-kafka`
- [ ] Kafka version: `4.2.0`
- [ ] 4 listeners returned:
  - `plain`: port 9092, internal, tls=false
  - `tls`: port 9093, internal, tls=true, auth=tls
  - `route`: port 9666, route type, tls=true, auth=tls
  - `console`: port 9777, internal, tls=true, auth=scram-sha-512
- [ ] Authorization type: `simple`
- [ ] Conditions section shows Ready condition

### T2.2 - Get Kafka Cluster (with explicit namespace)

```
Tool: get_kafka_cluster
Parameters: { "clusterName": "mcp-cluster", "namespace": "strimzi-kafka" }
```

**Expected:**
- [ ] Same result as T2.1
- [ ] Namespace in response is `strimzi-kafka`

### T2.3 - Get Kafka Cluster (nonexistent)

```
Tool: get_kafka_cluster
Parameters: { "clusterName": "nonexistent-cluster-xyz" }
```

**Expected:**
- [ ] Returns descriptive error indicating cluster not found
- [ ] No stack trace in response

### T2.4 - Get Kafka Cluster (wrong namespace)

```
Tool: get_kafka_cluster
Parameters: { "clusterName": "mcp-cluster", "namespace": "strimzi-kafka-mirror" }
```

**Expected:**
- [ ] Returns error (mcp-cluster does not exist in strimzi-kafka-mirror)

### T2.5 - Get Bootstrap Servers

```
Tool: get_kafka_bootstrap_servers
Parameters: { "clusterName": "mcp-cluster" }
```

**Expected:**
- [ ] Returns bootstrap addresses for 4 listeners
- [ ] `plain` listener: `mcp-cluster-kafka-bootstrap.strimzi-kafka.svc:9092`
- [ ] `tls` listener: `mcp-cluster-kafka-bootstrap.strimzi-kafka.svc:9093`
- [ ] `console` listener: `mcp-cluster-kafka-bootstrap.strimzi-kafka.svc:9777`
- [ ] `route` listener: external route address on port 9666

### T2.6 - Get Cluster Certificates (all)

```
Tool: get_kafka_cluster_certificates
Parameters: { "clusterName": "mcp-cluster" }
```

**Expected:**
- [ ] Returns cluster CA certificate info
- [ ] Certificate is not expired
- [ ] No private key material exposed
- [ ] TLS listeners (`tls`, `route`, `console`) have certificate data

### T2.7 - Get Cluster Certificates (specific listener)

```
Tool: get_kafka_cluster_certificates
Parameters: { "clusterName": "mcp-cluster", "listenerName": "tls" }
```

**Expected:**
- [ ] Returns certificate info specific to the `tls` listener

### T2.8 - Get Cluster Pods

```
Tool: get_kafka_cluster_pods
Parameters: { "clusterName": "mcp-cluster" }
```

**Expected:**
- [ ] Returns 12 pods:
  - 3 controller pods (from `controller-np`)
  - 3 broker pods (from `broker-np1`)
  - 3 broker pods (from `broker-np2`)
  - 1 Entity Operator pod (contains topic-operator and user-operator containers)
  - 1 Cruise Control pod
  - 1 Kafka Exporter pod
- [ ] All pods are in Running status
- [ ] All pods are Ready
- [ ] Kafka node pods follow naming pattern `mcp-cluster-<pool>-<id>`

### T2.9 - Get Cluster Config

```
Tool: get_kafka_cluster_config
Parameters: { "clusterName": "mcp-cluster" }
```

**Expected:**
- [ ] Contains config values from the Kafka CR:
  - `offsets.topic.replication.factor`: 3
  - `default.replication.factor`: 3
  - `min.insync.replicas`: 2
  - `log.retention.hours`: -1
  - `log.retention.bytes`: 128000000
  - `log.segment.bytes`: 64000000

### T2.10 - Get Cluster Logs (basic)

```
Tool: get_kafka_cluster_logs
Parameters: { "clusterName": "mcp-cluster", "tailLines": 20 }
```

**Expected:**
- [ ] Returns log lines from broker pods
- [ ] Logs are grouped by pod name
- [ ] At most 20 lines per pod

### T2.11 - Get Cluster Logs (filtered for errors)

```
Tool: get_kafka_cluster_logs
Parameters: {
  "clusterName": "mcp-cluster",
  "filter": "ERROR",
  "tailLines": 100,
  "sinceMinutes": 60
}
```

**Expected:**
- [ ] Only ERROR-level log lines returned (or empty if healthy cluster)
- [ ] Logs are from the last 60 minutes only

### T2.12 - Get Cluster Logs (keyword search)

```
Tool: get_kafka_cluster_logs
Parameters: {
  "clusterName": "mcp-cluster",
  "keywords": ["partition", "leader"],
  "tailLines": 50
}
```

**Expected:**
- [ ] Only log lines containing "partition" or "leader" are returned

### T2.13 - Get Cluster Logs (specific pod)

```
Tool: get_kafka_cluster_logs
Parameters: {
  "clusterName": "mcp-cluster",
  "podNames": ["mcp-cluster-broker-np1-0"],
  "tailLines": 20
}
```

**Expected:**
- [ ] Only logs from `mcp-cluster-broker-np1-0` returned
- [ ] No logs from other pods

### T2.14 - Get Cluster Logs (no matching filter)

```
Tool: get_kafka_cluster_logs
Parameters: {
  "clusterName": "mcp-cluster",
  "filter": "ZZZZNONEXISTENTZZZZ",
  "tailLines": 100
}
```

**Expected:**
- [ ] Returns empty log output
- [ ] No error thrown

---

## Phase 3: KafkaTopic Tools

### T3.1 - List Topics

```
Tool: list_kafka_topics
Parameters: { "clusterName": "mcp-cluster" }
```

**Expected:**
- [ ] Returns paginated list of topics
- [ ] Contains `mcp-test-topic` (if test-clients deployed)
- [ ] Each topic has: name, partitions, replicas, status
- [ ] Internal Kafka topics managed by Connect/MM2 may also appear (e.g., `mcp-connect-offsets`)

### T3.2 - List Topics (paginated)

```
Tool: list_kafka_topics
Parameters: { "clusterName": "mcp-cluster", "limit": 2, "offset": 0 }
```

**Expected:**
- [ ] Returns at most 2 topics
- [ ] Response includes pagination metadata (total count, offset, limit)

### T3.3 - List Topics (page 2)

```
Tool: list_kafka_topics
Parameters: { "clusterName": "mcp-cluster", "limit": 2, "offset": 2 }
```

**Expected:**
- [ ] Returns the next page of topics (no overlap with T3.2)

### T3.4 - Get Topic Details (mcp-test-topic)

> Requires: `--test-clients`

```
Tool: get_kafka_topic
Parameters: { "clusterName": "mcp-cluster", "topicName": "mcp-test-topic" }
```

**Expected:**
- [ ] Name: `mcp-test-topic`
- [ ] Partitions: 3
- [ ] Replicas: 3
- [ ] Status condition shows Ready

### T3.5 - Get Topic (nonexistent)

```
Tool: get_kafka_topic
Parameters: { "clusterName": "mcp-cluster", "topicName": "nonexistent-topic-xyz" }
```

**Expected:**
- [ ] Returns error or empty response indicating topic not found

---

## Phase 4: KafkaNodePool Tools

### T4.1 - List Node Pools

```
Tool: list_kafka_node_pools
Parameters: { "clusterName": "mcp-cluster" }
```

**Expected:**
- [ ] Returns exactly 3 node pools:
  - `controller-np`: role=controller, replicas=3
  - `broker-np1`: role=broker, replicas=3
  - `broker-np2`: role=broker, replicas=3

### T4.2 - Get Node Pool (controller)

```
Tool: get_kafka_node_pool
Parameters: { "clusterName": "mcp-cluster", "nodePoolName": "controller-np" }
```

**Expected:**
- [ ] Roles: `[controller]`
- [ ] Replicas: 3
- [ ] Storage: JBOD with 1 volume, 10Gi persistent-claim

### T4.3 - Get Node Pool (broker)

```
Tool: get_kafka_node_pool
Parameters: { "clusterName": "mcp-cluster", "nodePoolName": "broker-np1" }
```

**Expected:**
- [ ] Roles: `[broker]`
- [ ] Replicas: 3
- [ ] Storage: JBOD with 2 volumes, each 50Gi persistent-claim

### T4.4 - Get Node Pool Pods

```
Tool: get_kafka_node_pool_pods
Parameters: { "clusterName": "mcp-cluster", "nodePoolName": "broker-np1" }
```

**Expected:**
- [ ] Returns exactly 3 pods
- [ ] All pods are Running and Ready
- [ ] Pod names contain `broker-np1`

### T4.5 - Get Node Pool (nonexistent)

```
Tool: get_kafka_node_pool
Parameters: { "clusterName": "mcp-cluster", "nodePoolName": "nonexistent-pool" }
```

**Expected:**
- [ ] Returns error indicating node pool not found

---

## Phase 5: KafkaUser Tools

### T5.1 - List Users (all)

```
Tool: list_kafka_users
Parameters: {}
```

**Expected:**
- [ ] Returns at least 3 users: `mcp-scram-user`, `mcp-tls-user`, `mcp-admin-user`
- [ ] If `--test-clients`: also `mcp-test-client`
- [ ] If `--connect`: also `mcp-connect-user`
- [ ] If `--bridge`: also `mcp-bridge-user`
- [ ] If `--mirror-maker`: also `mm2-user`
- [ ] Each user has: name, namespace, authentication type, status

### T5.2 - List Users (by cluster)

```
Tool: list_kafka_users
Parameters: { "clusterName": "mcp-cluster" }
```

**Expected:**
- [ ] Returns only users labeled with `strimzi.io/cluster: mcp-cluster`
- [ ] All users are in namespace `strimzi-kafka`

### T5.3 - Get User (SCRAM with quotas)

```
Tool: get_kafka_user
Parameters: { "userName": "mcp-scram-user" }
```

**Expected:**
- [ ] Authentication type: `scram-sha-512`
- [ ] ACLs present:
  - Topic `orders-` (prefix pattern): Read, Write, Describe
  - Group `order-consumers`: Read
- [ ] Quotas present:
  - producerByteRate: 1048576
  - consumerByteRate: 2097152
  - requestPercentage: 55
- [ ] Status conditions present

### T5.4 - Get User (TLS)

```
Tool: get_kafka_user
Parameters: { "userName": "mcp-tls-user" }
```

**Expected:**
- [ ] Authentication type: `tls`
- [ ] ACLs: Read/Describe on all topics, Read on all groups
- [ ] No quotas configured

### T5.5 - Get User (admin with overpermissive ACLs)

```
Tool: get_kafka_user
Parameters: { "userName": "mcp-admin-user" }
```

**Expected:**
- [ ] Authentication type: `scram-sha-512`
- [ ] ACLs: All operations on all topics, groups, and cluster resources
- [ ] No quotas configured

### T5.6 - Get User (nonexistent)

```
Tool: get_kafka_user
Parameters: { "userName": "nonexistent-user-xyz" }
```

**Expected:**
- [ ] Returns error indicating user not found

---

## Phase 6: Strimzi Operator Tools

### T6.1 - List Operators

```
Tool: list_strimzi_operators
Parameters: {}
```

**Expected:**
- [ ] Returns at least 1 operator: `strimzi-cluster-operator` in namespace `strimzi`
- [ ] Entry includes: name, namespace, replicas, ready status

### T6.2 - Get Operator

```
Tool: get_strimzi_operator
Parameters: { "operatorName": "strimzi-cluster-operator" }
```

**Expected:**
- [ ] Name: `strimzi-cluster-operator`, namespace: `strimzi`
- [ ] Deployment is ready (available replicas match desired)
- [ ] Strimzi version info present

### T6.3 - Get Operator (namespace-scoped)

```
Tool: get_strimzi_operator
Parameters: { "operatorName": "strimzi-cluster-operator", "namespace": "strimzi" }
```

**Expected:**
- [ ] Same result as T6.2

### T6.4 - Get Operator Logs

```
Tool: get_strimzi_operator_logs
Parameters: { "tailLines": 50, "sinceMinutes": 30 }
```

**Expected:**
- [ ] Returns operator log lines
- [ ] Logs show reconciliation activity for `mcp-cluster`
- [ ] At most 50 lines returned

### T6.5 - Get Operator Logs (filtered for errors)

```
Tool: get_strimzi_operator_logs
Parameters: { "filter": "ERROR", "tailLines": 100 }
```

**Expected:**
- [ ] Only ERROR-level log lines returned (or empty if operator healthy)

### T6.6 - Get Operator Pod

> Note: get actual pod name from T6.2 results (e.g., `strimzi-cluster-operator-<suffix>`)

```
Tool: get_strimzi_operator_pod
Parameters: { "namespace": "strimzi", "podName": "<pod-name-from-T6.2>" }
```

**Expected:**
- [ ] Returns detailed pod info: containers, volumes, conditions

---

## Phase 7: Strimzi Events

### T7.1 - Get Events for Kafka Cluster

```
Tool: get_strimzi_events
Parameters: { "resourceName": "mcp-cluster", "resourceKind": "Kafka" }
```

**Expected:**
- [ ] Returns Kubernetes events related to `mcp-cluster`
- [ ] Events grouped by resource (Kafka CR, pods, PVCs)
- [ ] Each event has: timestamp, reason, message, type (Normal/Warning)

### T7.2 - Get Events (time-scoped)

```
Tool: get_strimzi_events
Parameters: {
  "resourceName": "mcp-cluster",
  "resourceKind": "Kafka",
  "sinceMinutes": 60
}
```

**Expected:**
- [ ] Only events from the last 60 minutes

### T7.3 - Get Events for Operator

```
Tool: get_strimzi_events
Parameters: { "resourceName": "strimzi-cluster-operator", "resourceKind": "StrimziOperator" }
```

**Expected:**
- [ ] Returns events for the operator deployment in namespace `strimzi`

### T7.4 - Get Events (nonexistent resource)

```
Tool: get_strimzi_events
Parameters: { "resourceName": "nonexistent-xyz", "resourceKind": "Kafka" }
```

**Expected:**
- [ ] Returns empty events list or descriptive error
- [ ] No stack trace

### T7.5 - Get Events (invalid kind)

```
Tool: get_strimzi_events
Parameters: { "resourceName": "mcp-cluster", "resourceKind": "InvalidKind" }
```

**Expected:**
- [ ] Returns validation error about unsupported resource kind

---

## Phase 8: KafkaRebalance Tools

> **Note:** No KafkaRebalance CR is deployed by dev manifests. Cruise Control is enabled
> on `mcp-cluster` so the tools should work, but return empty lists.

### T8.1 - List Rebalances (empty)

```
Tool: list_kafka_rebalances
Parameters: {}
```

**Expected:**
- [ ] Returns empty list (no KafkaRebalance CRs deployed)
- [ ] No error thrown

### T8.2 - List Rebalances (by cluster, empty)

```
Tool: list_kafka_rebalances
Parameters: { "clusterName": "mcp-cluster" }
```

**Expected:**
- [ ] Returns empty list
- [ ] No error thrown

### T8.3 - Get Rebalance (nonexistent)

```
Tool: get_kafka_rebalance
Parameters: { "rebalanceName": "nonexistent-rebalance" }
```

**Expected:**
- [ ] Returns error indicating rebalance not found

---

## Phase 9: Drain Cleaner Tools

> Requires: `--drain-cleaner`

### T9.1 - List Drain Cleaners

```
Tool: list_drain_cleaners
Parameters: {}
```

**Expected:**
- [ ] Returns 1 entry: `strimzi-drain-cleaner` in namespace `strimzi-drain-cleaner`
- [ ] Entry shows ready status

### T9.2 - Get Drain Cleaner

```
Tool: get_drain_cleaner
Parameters: { "drainCleanerName": "strimzi-drain-cleaner" }
```

**Expected:**
- [ ] Namespace: `strimzi-drain-cleaner`
- [ ] Deployment is ready
- [ ] Webhook configuration present (`ValidatingWebhookConfiguration`)
- [ ] Environment variables visible: `STRIMZI_DENY_EVICTION`, `STRIMZI_DRAIN_KAFKA`

### T9.3 - Check Drain Cleaner Readiness

```
Tool: check_drain_cleaner_readiness
Parameters: {}
```

**Expected:**
- [ ] Deployment: healthy
- [ ] Webhook: configured
- [ ] Certificates: valid
- [ ] Overall verdict: ready

### T9.4 - Get Drain Cleaner Logs

```
Tool: get_drain_cleaner_logs
Parameters: { "tailLines": 50 }
```

**Expected:**
- [ ] Returns logs from drain cleaner pod(s)

### T9.5 - Get Events for Drain Cleaner

```
Tool: get_strimzi_events
Parameters: { "resourceName": "strimzi-drain-cleaner", "resourceKind": "DrainCleaner" }
```

**Expected:**
- [ ] Returns events for drain cleaner deployment

---

## Phase 10: KafkaConnect Tools

> Requires: `--connect`

### T10.1 - List Connects

```
Tool: list_kafka_connects
Parameters: {}
```

**Expected:**
- [ ] Returns 1 entry: `mcp-connect` in namespace `strimzi-kafka`
- [ ] Replicas: 1, status: Ready

### T10.2 - Get Connect

```
Tool: get_kafka_connect
Parameters: { "connectName": "mcp-connect" }
```

**Expected:**
- [ ] Name: `mcp-connect`, namespace: `strimzi-kafka`
- [ ] Bootstrap: `mcp-cluster-kafka-bootstrap:9093`
- [ ] Replicas: 1
- [ ] TLS + client cert authentication configured
- [ ] Build plugins include: `camel-timer-source` (v4.18.0)
- [ ] Status conditions present

### T10.3 - Get Connect Pods

```
Tool: get_kafka_connect_pods
Parameters: { "connectName": "mcp-connect" }
```

**Expected:**
- [ ] Returns exactly 1 pod
- [ ] Pod is Running and Ready

### T10.4 - Get Connect Logs

```
Tool: get_kafka_connect_logs
Parameters: { "connectName": "mcp-connect", "tailLines": 50 }
```

**Expected:**
- [ ] Returns Connect worker logs
- [ ] Should show connector activity (timer source emitting messages)

### T10.5 - Get Events for KafkaConnect

```
Tool: get_strimzi_events
Parameters: { "resourceName": "mcp-connect", "resourceKind": "KafkaConnect", "namespace": "strimzi-kafka" }
```

**Expected:**
- [ ] Returns events for `mcp-connect` including pod events

---

## Phase 11: KafkaConnector Tools

> Requires: `--connect`

### T11.1 - List Connectors

```
Tool: list_kafka_connectors
Parameters: {}
```

**Expected:**
- [ ] Returns at least 1 connector: `mcp-timer-source` in namespace `strimzi-kafka`
- [ ] Each entry has: name, namespace, connect cluster (`mcp-connect`), class, status

### T11.2 - List Connectors (by Connect cluster)

```
Tool: list_kafka_connectors
Parameters: { "connectCluster": "mcp-connect" }
```

**Expected:**
- [ ] Returns `mcp-timer-source`
- [ ] All returned connectors belong to `mcp-connect`

### T11.3 - Get Connector

```
Tool: get_kafka_connector
Parameters: { "connectorName": "mcp-timer-source" }
```

**Expected:**
- [ ] Class: `org.apache.camel.kafkaconnector.timersource.CamelTimersourceSourceConnector`
- [ ] Tasks max: 1
- [ ] Config includes: `topics: mcp-connect-timer-topic`, `camel.kamelet.timer-source.period: 60000`
- [ ] Task status: Running

---

## Phase 12: KafkaBridge Tools

> Requires: `--bridge`

### T12.1 - List Bridges

```
Tool: list_kafka_bridges
Parameters: {}
```

**Expected:**
- [ ] Returns 1 entry: `mcp-bridge` in namespace `strimzi-kafka`
- [ ] Replicas: 1

### T12.2 - Get Bridge

```
Tool: get_kafka_bridge
Parameters: { "bridgeName": "mcp-bridge" }
```

**Expected:**
- [ ] Name: `mcp-bridge`, namespace: `strimzi-kafka`
- [ ] Bootstrap: `mcp-cluster-kafka-bootstrap:9093`
- [ ] HTTP port: 8080
- [ ] TLS + client cert authentication configured
- [ ] Replicas: 1

### T12.3 - Get Bridge Pods

```
Tool: get_kafka_bridge_pods
Parameters: { "bridgeName": "mcp-bridge" }
```

**Expected:**
- [ ] Returns exactly 1 pod
- [ ] Pod is Running and Ready

### T12.4 - Get Bridge Logs

```
Tool: get_kafka_bridge_logs
Parameters: { "bridgeName": "mcp-bridge", "tailLines": 50 }
```

**Expected:**
- [ ] Returns bridge pod logs

### T12.5 - Get Events for KafkaBridge

```
Tool: get_strimzi_events
Parameters: { "resourceName": "mcp-bridge", "resourceKind": "KafkaBridge" }
```

**Expected:**
- [ ] Returns events for `mcp-bridge`

---

## Phase 13: KafkaMirrorMaker2 Tools

> Requires: `--mirror-maker`

### T13.1 - List MirrorMakers

```
Tool: list_kafka_mirror_makers
Parameters: {}
```

**Expected:**
- [ ] Returns 1 entry: `mcp-mirror-maker` in namespace `strimzi-mirror-maker`
- [ ] Replicas: 1

### T13.2 - Get MirrorMaker

```
Tool: get_kafka_mirror_maker
Parameters: { "mirrorMakerName": "mcp-mirror-maker" }
```

**Expected:**
- [ ] Name: `mcp-mirror-maker`, namespace: `strimzi-mirror-maker`
- [ ] Version: 4.2.0, replicas: 1
- [ ] Source cluster alias: `mcp-cluster` (bootstrap: `mcp-cluster-kafka-bootstrap.strimzi-kafka.svc:9777`)
- [ ] Target cluster alias: `mcp-cluster-mirror` (bootstrap: `mcp-cluster-mirror-kafka-bootstrap.strimzi-kafka-mirror.svc:9092`)
- [ ] Topics pattern: `.*`, excludes: `__.*`
- [ ] SCRAM-SHA-512 authentication to source

### T13.3 - Get MirrorMaker Pods

```
Tool: get_kafka_mirror_maker_pods
Parameters: { "mirrorMakerName": "mcp-mirror-maker" }
```

**Expected:**
- [ ] Returns exactly 1 pod
- [ ] Pod is Running and Ready

### T13.4 - Get MirrorMaker Logs

```
Tool: get_kafka_mirror_maker_logs
Parameters: { "mirrorMakerName": "mcp-mirror-maker", "tailLines": 50 }
```

**Expected:**
- [ ] Returns MM2 worker logs
- [ ] Should show replication activity for mirrored topics

### T13.5 - Get Events for MirrorMaker2

```
Tool: get_strimzi_events
Parameters: { "resourceName": "mcp-mirror-maker", "resourceKind": "KafkaMirrorMaker2" }
```

**Expected:**
- [ ] Returns events for `mcp-mirror-maker`

### T13.6 - Get Mirror Cluster Details

```
Tool: get_kafka_cluster
Parameters: { "clusterName": "mcp-cluster-mirror" }
```

**Expected:**
- [ ] Name: `mcp-cluster-mirror`, namespace: `strimzi-kafka-mirror`
- [ ] Version: 4.2.0
- [ ] Only 1 listener: `plain` on port 9092, no TLS
- [ ] No authorization configured

### T13.7 - List Mirror Cluster Node Pools

```
Tool: list_kafka_node_pools
Parameters: { "clusterName": "mcp-cluster-mirror" }
```

**Expected:**
- [ ] Returns 1 pool: `mirror-combined`
- [ ] Roles: `[controller, broker]` (combined)
- [ ] Replicas: 1

---

## Phase 14: Metrics Tools

> Metrics require either Prometheus (`--prometheus`) or pod-scraping (default).
> Pod-scraping works without Prometheus but returns point-in-time values only.

### T14.1 - Get Kafka Metrics (all categories)

```
Tool: get_kafka_metrics
Parameters: { "clusterName": "mcp-cluster" }
```

**Expected:**
- [ ] Returns broker metrics from `mcp-cluster`
- [ ] Includes categories: replication, throughput, performance, resources
- [ ] Each metric has interpretation hints

### T14.2 - Get Kafka Metrics (replication)

```
Tool: get_kafka_metrics
Parameters: { "clusterName": "mcp-cluster", "category": "replication" }
```

**Expected:**
- [ ] Returns replication metrics only
- [ ] Includes: offlinepartitionscount (should be 0 on healthy cluster), underreplicatedpartitions, leadercount

### T14.3 - Get Kafka Metrics (time range)

```
Tool: get_kafka_metrics
Parameters: {
  "clusterName": "mcp-cluster",
  "category": "throughput",
  "rangeMinutes": 30,
  "stepSeconds": 60
}
```

**Expected:**
- [ ] Returns time-series metrics for the last 30 minutes
- [ ] Data points spaced ~60 seconds apart
- [ ] Throughput values present (bytes in/out rates)

### T14.4 - Get Kafka Exporter Metrics

```
Tool: get_kafka_exporter_metrics
Parameters: { "clusterName": "mcp-cluster" }
```

**Expected:**
- [ ] Returns Kafka Exporter metrics (Kafka Exporter is enabled on `mcp-cluster` with `topicRegex: ".*"`)
- [ ] Consumer lag and topic-level stats present

### T14.5 - Get Bridge Metrics

> Requires: `--bridge`

```
Tool: get_kafka_bridge_metrics
Parameters: { "bridgeName": "mcp-bridge" }
```

**Expected:**
- [ ] Returns bridge metrics (JMX exporter is configured on `mcp-bridge`)

### T14.6 - Get Connect Metrics

> Requires: `--connect`

```
Tool: get_kafka_connect_metrics
Parameters: { "connectName": "mcp-connect" }
```

**Expected:**
- [ ] Returns Connect worker metrics (JMX exporter is configured on `mcp-connect`)
- [ ] Includes connector and task metrics for `mcp-timer-source`

### T14.7 - Get Operator Metrics

```
Tool: get_strimzi_operator_metrics
Parameters: {}
```

**Expected:**
- [ ] Returns Strimzi operator metrics
- [ ] Includes: reconciliation counts, durations, managed resource counts

### T14.8 - Get Operator Metrics (specific cluster)

```
Tool: get_strimzi_operator_metrics
Parameters: { "clusterName": "mcp-cluster", "category": "reconciliation" }
```

**Expected:**
- [ ] Returns reconciliation metrics for `mcp-cluster`
- [ ] Includes: strimzi_reconciliations_total, strimzi_reconciliations_failed_total

### T14.9 - Get Metrics (nonexistent cluster)

```
Tool: get_kafka_metrics
Parameters: { "clusterName": "nonexistent-cluster-xyz" }
```

**Expected:**
- [ ] Returns error indicating cluster not found

---

## Phase 15: Diagnostic Tools

### T15.1 - Diagnose Kafka Cluster

```
Tool: diagnose_kafka_cluster
Parameters: { "clusterName": "mcp-cluster" }
```

**Expected:**
- [ ] Returns multi-section diagnostic report
- [ ] Sections include: cluster status, node pool health (3 pools), pod health (12 pods), operator status
- [ ] Includes: log analysis, event analysis, metrics summary
- [ ] Steps completed/failed list present
- [ ] Completes within 120 seconds

### T15.2 - Diagnose Kafka Cluster (with symptom)

```
Tool: diagnose_kafka_cluster
Parameters: {
  "clusterName": "mcp-cluster",
  "symptom": "high latency",
  "sinceMinutes": 60
}
```

**Expected:**
- [ ] Diagnosis focuses on latency-related metrics and logs
- [ ] Time window matches `sinceMinutes: 60`

### T15.3 - Diagnose Connectivity

```
Tool: diagnose_kafka_connectivity
Parameters: { "clusterName": "mcp-cluster" }
```

**Expected:**
- [ ] Report covers all 4 listeners (plain, tls, route, console)
- [ ] Bootstrap servers listed
- [ ] TLS certificates checked for tls/route/console listeners
- [ ] KafkaUser ACLs reviewed (at least 3 users)
- [ ] Pod health assessed (12 cluster pods: 9 nodes + EO + CC + KE)

### T15.4 - Diagnose Connectivity (specific listener)

```
Tool: diagnose_kafka_connectivity
Parameters: { "clusterName": "mcp-cluster", "listenerName": "tls" }
```

**Expected:**
- [ ] Focuses analysis on the `tls` listener (port 9093, mutual TLS auth)
- [ ] TLS certificate details included

### T15.5 - Diagnose Kafka Metrics

```
Tool: diagnose_kafka_metrics
Parameters: { "clusterName": "mcp-cluster" }
```

**Expected:**
- [ ] Returns metrics analysis covering: replication, performance, resources, throughput
- [ ] Thresholds and interpretation present
- [ ] For a healthy dev cluster, no critical anomalies expected

### T15.6 - Diagnose Operator Metrics

```
Tool: diagnose_operator_metrics
Parameters: {}
```

**Expected:**
- [ ] Returns operator health report
- [ ] Covers: reconciliation success rate, duration, JVM health
- [ ] Operator logs analyzed for errors

### T15.7 - Diagnose Topic

```
Tool: diagnose_kafka_topic
Parameters: { "topicName": "mcp-test-topic" }
```

> Requires: `--test-clients`

**Expected:**
- [ ] Returns topic diagnostic for `mcp-test-topic`
- [ ] Topic CR status, partition config (3 partitions, 3 replicas)
- [ ] Reconciliation status from topic operator

### T15.8 - Diagnose KafkaConnect

> Requires: `--connect`

```
Tool: diagnose_kafka_connect
Parameters: { "connectName": "mcp-connect" }
```

**Expected:**
- [ ] Returns Connect cluster diagnostic
- [ ] Covers: `mcp-connect` status, `mcp-timer-source` connector inventory
- [ ] Pod health (1 pod), logs analyzed

### T15.9 - Diagnose KafkaConnector

> Requires: `--connect`

```
Tool: diagnose_kafka_connector
Parameters: { "connectorName": "mcp-timer-source" }
```

**Expected:**
- [ ] Returns connector-level diagnostic for `mcp-timer-source`
- [ ] Covers: connector status (Camel Timer Source), task status (1 task)
- [ ] Parent cluster `mcp-connect` health checked

### T15.10 - Diagnose MirrorMaker2

> Requires: `--mirror-maker`

```
Tool: diagnose_kafka_mirror_maker
Parameters: { "mirrorMakerName": "mcp-mirror-maker" }
```

**Expected:**
- [ ] Returns MM2 diagnostic report
- [ ] Covers: `mcp-mirror-maker` status, connector health
- [ ] Source (mcp-cluster) and target (mcp-cluster-mirror) connectivity

### T15.11 - Compare Clusters (main vs mirror)

> Requires: `--mirror-maker`

```
Tool: compare_kafka_clusters
Parameters: {
  "clusterName1": "mcp-cluster",
  "namespace1": "strimzi-kafka",
  "clusterName2": "mcp-cluster-mirror",
  "namespace2": "strimzi-kafka-mirror"
}
```

**Expected:**
- [ ] Returns side-by-side comparison highlighting significant differences:
  - `mcp-cluster`: 6 brokers + 3 controllers, 4 listeners, TLS, authorization, RF=3
  - `mcp-cluster-mirror`: 1 combined node, 1 listener (plain), no auth, RF=1
- [ ] Listener configuration differences clearly shown
- [ ] Replication factor / min ISR differences highlighted

### T15.12 - Assess Upgrade Readiness

```
Tool: assess_upgrade_readiness
Parameters: { "clusterName": "mcp-cluster" }
```

**Expected:**
- [ ] Returns upgrade readiness report
- [ ] Checks: cluster health, replication (RF=3 with min ISR=2), operator readiness
- [ ] Checks: certificate validity
- [ ] If `--drain-cleaner`: Drain Cleaner status included
- [ ] GO / NO-GO verdict present

### T15.13 - Diagnose Cluster (nonexistent)

```
Tool: diagnose_kafka_cluster
Parameters: { "clusterName": "nonexistent-cluster-xyz" }
```

**Expected:**
- [ ] Returns error or report indicating cluster not found
- [ ] Does not hang or timeout

---

## Phase 16: Error Handling and Edge Cases

### T16.1 - Cluster in wrong namespace

```
Tool: get_kafka_cluster
Parameters: { "clusterName": "mcp-cluster", "namespace": "nonexistent-namespace" }
```

**Expected:**
- [ ] Descriptive error (not a stack trace)

### T16.2 - List in empty namespace

```
Tool: list_kafka_clusters
Parameters: { "namespace": "default" }
```

**Expected:**
- [ ] Returns empty list (no Kafka clusters in `default` namespace)
- [ ] No error thrown

### T16.3 - Metrics with very short range

```
Tool: get_kafka_metrics
Parameters: {
  "clusterName": "mcp-cluster",
  "rangeMinutes": 1,
  "stepSeconds": 10
}
```

**Expected:**
- [ ] Returns metrics or appropriate response for a 1-minute range
- [ ] Does not crash

### T16.4 - Large log request (response size guardrail)

```
Tool: get_kafka_cluster_logs
Parameters: { "clusterName": "mcp-cluster", "tailLines": 1000 }
```

**Expected:**
- [ ] Returns within reasonable time
- [ ] Response respects `mcp.guardrail.max-response-bytes` (default 500KB)
- [ ] Sensitive data is redacted if `mcp.guardrail.log-redaction.enabled` is true

---

## Phase 17: End-to-End Scenarios

These scenarios test realistic workflows. The agent should execute each step
sequentially and verify cross-tool consistency.

### E2E-1: Cluster Health Check

1. `get_kafka_fleet_overview` - note `mcp-cluster` status and broker count
2. `get_strimzi_kafka_cluster_overview` for `mcp-cluster` - note component counts
3. `get_kafka_cluster` for `mcp-cluster` - verify status matches fleet overview
4. `get_kafka_cluster_pods` for `mcp-cluster` - verify 12 pods (3+3+3 nodes + EO + CC + KE)
5. `list_kafka_node_pools` for `mcp-cluster` - verify 3 pools, node replicas sum to 9

**Expected:**
- [ ] Cluster status is consistent across fleet overview, cluster overview, and cluster detail
- [ ] Pod count (12) = controller-np (3) + broker-np1 (3) + broker-np2 (3) + Entity Operator (1) + Cruise Control (1) + Kafka Exporter (1)
- [ ] No contradictory information between tools

### E2E-2: Topic Investigation

1. `list_kafka_topics` for `mcp-cluster` - find `mcp-test-topic`
2. `get_kafka_topic` for `mcp-test-topic` - verify 3 partitions, 3 replicas
3. `get_strimzi_events` for `mcp-cluster` (kind: Kafka) - check for events
4. `diagnose_kafka_topic` for `mcp-test-topic` - verify diagnostic references same data

**Expected:**
- [ ] Topic details consistent between list and get
- [ ] Diagnostic references correct topic config
- [ ] Events (if any) correlate with cluster status

### E2E-3: Connectivity Troubleshooting

1. `get_kafka_cluster` for `mcp-cluster` - note 4 listeners
2. `get_kafka_bootstrap_servers` for `mcp-cluster` - note 4 addresses
3. `get_kafka_cluster_certificates` for `mcp-cluster` - note cert expiry for TLS listeners
4. `list_kafka_users` for `mcp-cluster` - note auth types (SCRAM + TLS users)
5. `diagnose_kafka_connectivity` for `mcp-cluster` - verify all above data referenced

**Expected:**
- [ ] Bootstrap addresses match listener config (4 listeners, 4 bootstrap entries)
- [ ] Certificates present for `tls`, `route`, `console` listeners
- [ ] Diagnostic references listeners, certs, and users

### E2E-4: Operator Health

1. `list_strimzi_operators` - find `strimzi-cluster-operator` in `strimzi` namespace
2. `get_strimzi_operator` for `strimzi-cluster-operator`
3. `get_strimzi_operator_logs` with `sinceMinutes: 30` - check for errors
4. `get_strimzi_operator_metrics` - check reconciliation stats
5. `diagnose_operator_metrics` - verify comprehensive analysis

**Expected:**
- [ ] Operator is healthy and reconciling
- [ ] Reconciliation metrics show activity for `mcp-cluster`
- [ ] Diagnostic covers all aspects

### E2E-5: KafkaConnect Pipeline

> Requires: `--connect`

1. `list_kafka_connects` - find `mcp-connect` in `strimzi-kafka`
2. `get_kafka_connect` for `mcp-connect` - verify 1 replica, TLS auth
3. `get_kafka_connect_pods` for `mcp-connect` - verify 1 pod Running
4. `list_kafka_connectors` for `mcp-connect` - find `mcp-timer-source`
5. `get_kafka_connector` for `mcp-timer-source` - verify Camel Timer Source, 1 task Running
6. `diagnose_kafka_connect` for `mcp-connect` - comprehensive check

**Expected:**
- [ ] Connect cluster is Ready with 1 pod
- [ ] `mcp-timer-source` is Running with 1 task
- [ ] Connector belongs to `mcp-connect`
- [ ] Diagnostic covers cluster + connector health

### E2E-6: Upgrade Readiness Assessment

1. `get_kafka_cluster` for `mcp-cluster` - note version 4.2.0
2. `get_kafka_cluster_pods` for `mcp-cluster` - verify all 12 pods healthy
3. `get_kafka_metrics` with category `replication` - verify 0 offline partitions
4. If `--drain-cleaner`: `check_drain_cleaner_readiness` - verify ready
5. `get_kafka_cluster_certificates` for `mcp-cluster` - verify certs not expired
6. `assess_upgrade_readiness` for `mcp-cluster` - comprehensive assessment

**Expected:**
- [ ] All individual checks pass on a healthy dev cluster
- [ ] Assessment gives GO verdict
- [ ] All data points from individual tools are referenced in assessment

### E2E-7: Cross-Cluster Comparison (main vs mirror)

> Requires: `--mirror-maker`

1. `get_kafka_cluster` for `mcp-cluster` - note: 4 listeners, auth, RF=3
2. `get_kafka_cluster` for `mcp-cluster-mirror` - note: 1 listener, no auth, RF=1
3. `get_kafka_cluster_config` for `mcp-cluster` - effective config
4. `get_kafka_cluster_config` for `mcp-cluster-mirror` - effective config
5. `compare_kafka_clusters` - verify differences match

**Expected:**
- [ ] Comparison highlights the large differences between production-like and minimal mirror cluster
- [ ] Config differences (replication.factor, min.insync.replicas, listeners) are all flagged
- [ ] No false equivalences reported

---

## Phase 18: Namespace-Scoped RBAC (App Developer)

**Requires:** Two Kafka clusters in different namespaces, MCP deployed with namespace-scoped RoleBindings (not ClusterRoleBinding).

**System test class:** `NamespaceScopedRbacST`

**Setup:** MCP has RoleBinding access only to `strimzi-kafka` and `strimzi` namespaces. A second cluster exists in `strimzi-kafka-2` (inaccessible).

This phase tests all three server-side error handling patterns under RBAC restriction:
1. **Direct tools** — 403 propagated as tool error
2. **Fleet overview** — graceful degradation
3. **Diagnostic tools** — partial access

### T18.1 — list_kafka_clusters (accessible namespace)
- **Tool:** `list_kafka_clusters`
- **Args:** `namespace: strimzi-kafka`
- **Expected:** Returns `mcp-cluster`, no error

### T18.2 — list_kafka_clusters (inaccessible namespace)
- **Tool:** `list_kafka_clusters`
- **Args:** `namespace: strimzi-kafka-2`
- **Expected:** `isError=true`, message contains "403 Forbidden"

### T18.3 — list_kafka_clusters (cluster-wide, no namespace)
- **Tool:** `list_kafka_clusters`
- **Args:** _(none)_
- **Expected:** `isError=true` (no cluster-wide list permission)

### T18.4 — get_kafka_cluster (accessible namespace)
- **Tool:** `get_kafka_cluster`
- **Args:** `clusterName: mcp-cluster, namespace: strimzi-kafka`
- **Expected:** Returns cluster, Ready status

### T18.5 — get_kafka_cluster (inaccessible namespace)
- **Tool:** `get_kafka_cluster`
- **Args:** `clusterName: mcp-cluster-2, namespace: strimzi-kafka-2`
- **Expected:** `isError=true`

### T18.6 — get_kafka_cluster (auto-discover, no namespace)
- **Tool:** `get_kafka_cluster`
- **Args:** `clusterName: mcp-cluster`
- **Expected:** `isError=true` (auto-discover uses `.inAnyNamespace()`)

### T18.7 — get_kafka_fleet_overview (accessible namespace)
- **Tool:** `get_kafka_fleet_overview`
- **Args:** `namespace: strimzi-kafka`
- **Expected:** `total_clusters=1`, success

### T18.8 — get_kafka_fleet_overview (inaccessible namespace)
- **Tool:** `get_kafka_fleet_overview`
- **Args:** `namespace: strimzi-kafka-2`
- **Expected:** `isError=true`

### T18.9 — get_kafka_fleet_overview (cluster-wide, no namespace)
- **Tool:** `get_kafka_fleet_overview`
- **Args:** _(none)_
- **Expected:** `isError=true`

### T18.10 — diagnose_kafka_cluster (accessible namespace)
- **Tool:** `diagnose_kafka_cluster`
- **Args:** `clusterName: mcp-cluster, namespace: strimzi-kafka`
- **Expected:** Success, `steps_completed` non-empty

### T18.11 — diagnose_kafka_cluster (inaccessible namespace)
- **Tool:** `diagnose_kafka_cluster`
- **Args:** `clusterName: mcp-cluster-2, namespace: strimzi-kafka-2`
- **Expected:** `isError=true`

### T18.12 — list_drain_cleaners (requires cluster-scope)
- **Tool:** `list_drain_cleaners`
- **Args:** _(none)_
- **Expected:** `isError=true` (ValidatingWebhookConfigurations need cluster-scoped access)

### T18.13 — get_kafka_cluster_certificates (no sensitive RBAC)
- **Tool:** `get_kafka_cluster_certificates`
- **Args:** `clusterName: mcp-cluster, namespace: strimzi-kafka`
- **Expected:** `isError=true` (no secrets access)

### T18.14 — get_kafka_cluster_certificates (with sensitive RBAC)
- **Tool:** `get_kafka_cluster_certificates`
- **Args:** Same as T18.13, after deploying sensitive Role+RoleBinding
- **Expected:** Success, certificates array present, no private keys

---

## Phase 19: Multi-Cluster Same Namespace (Admin)

**Requires:** Two differently-named Kafka clusters in the same namespace, MCP with ClusterRole.

**System test class:** `MultiClusterST`

**Setup:** `mcp-cluster` and `mcp-cluster-2` both in `strimzi-kafka`, MCP with full cluster-wide access.

### T19.1 — get_kafka_fleet_overview (both clusters)
- **Tool:** `get_kafka_fleet_overview`
- **Expected:** `total_clusters >= 2`, both cluster names listed, `total_brokers` = sum

### T19.2 — list_kafka_clusters (both in same namespace)
- **Tool:** `list_kafka_clusters`
- **Args:** `namespace: strimzi-kafka`
- **Expected:** Array with >= 2 entries, both names present

### T19.3 — get_kafka_cluster (cluster 1)
- **Tool:** `get_kafka_cluster`
- **Args:** `clusterName: mcp-cluster`
- **Expected:** Correct name, Ready

### T19.4 — get_kafka_cluster (cluster 2)
- **Tool:** `get_kafka_cluster`
- **Args:** `clusterName: mcp-cluster-2`
- **Expected:** Correct name, Ready

### T19.5 — get_kafka_bootstrap_servers (different per cluster)
- **Tool:** `get_kafka_bootstrap_servers`
- **Expected:** Different bootstrap addresses for each cluster

### T19.6 — list_kafka_node_pools (per cluster)
- **Tool:** `list_kafka_node_pools`
- **Expected:** Each cluster has its own pools

### T19.7 — get_strimzi_kafka_cluster_overview (per cluster)
- **Tool:** `get_strimzi_kafka_cluster_overview`
- **Expected:** Each returns its own node pools

### T19.8 — compare_kafka_clusters
- **Tool:** `compare_kafka_clusters`
- **Args:** `clusterName1: mcp-cluster, clusterName2: mcp-cluster-2`
- **Expected:** Side-by-side comparison, success

---

## Phase 20: Cross-Namespace Same-Name Disambiguation (Admin)

**Requires:** Same-named Kafka cluster in two different namespaces, MCP with ClusterRole.

**System test class:** `CrossNamespaceAdminST`

**Setup:** `mcp-cluster` in both `strimzi-kafka` and `strimzi-kafka-2`, MCP with full cluster-wide access.

### T20.1 — get_kafka_cluster (ambiguous, no namespace)
- **Tool:** `get_kafka_cluster`
- **Args:** `clusterName: mcp-cluster`
- **Expected:** `isError=true`, message contains "Multiple clusters" and both namespace names

### T20.2 — get_kafka_cluster (resolved via namespace 1)
- **Tool:** `get_kafka_cluster`
- **Args:** `clusterName: mcp-cluster, namespace: strimzi-kafka`
- **Expected:** Returns cluster with `namespace: strimzi-kafka`

### T20.3 — get_kafka_cluster (resolved via namespace 2)
- **Tool:** `get_kafka_cluster`
- **Args:** `clusterName: mcp-cluster, namespace: strimzi-kafka-2`
- **Expected:** Returns cluster with `namespace: strimzi-kafka-2`

### T20.4 — get_kafka_fleet_overview (all namespaces)
- **Tool:** `get_kafka_fleet_overview`
- **Expected:** `total_clusters >= 2`

### T20.5 — get_kafka_fleet_overview (per namespace)
- **Tool:** `get_kafka_fleet_overview`
- **Expected:** Each namespace shows exactly 1 cluster

### T20.6 — list_kafka_clusters (per namespace)
- **Tool:** `list_kafka_clusters`
- **Expected:** Each namespace returns 1 cluster with correct namespace field

### T20.7 — compare_kafka_clusters (cross-namespace)
- **Tool:** `compare_kafka_clusters`
- **Args:** Same name with `namespace1: strimzi-kafka, namespace2: strimzi-kafka-2`
- **Expected:** Success, side-by-side comparison

### T20.8 — get_kafka_bootstrap_servers (per namespace)
- **Tool:** `get_kafka_bootstrap_servers`
- **Expected:** Different addresses for same-named cluster in different namespaces

### T20.9 — diagnose_kafka_cluster (with namespace)
- **Tool:** `diagnose_kafka_cluster`
- **Args:** `clusterName: mcp-cluster, namespace: strimzi-kafka`
- **Expected:** Success, targets correct cluster

---

## Test Results Summary Template

| Phase | Test ID | Tool | Requires | Result | Notes |
|-------|---------|------|----------|--------|-------|
| 1     | T1.1    | get_kafka_fleet_overview | core | | |
| 1     | T1.2    | get_kafka_fleet_overview (ns) | core | | |
| 1     | T1.3    | list_kafka_clusters | core | | |
| 1     | T1.4    | list_kafka_clusters (ns) | core | | |
| 1     | T1.5    | get_strimzi_kafka_cluster_overview | core | | |
| 1     | T1.6    | get_strimzi_kafka_cluster_overview (nonexistent) | core | | |
| 2     | T2.1    | get_kafka_cluster | core | | |
| 2     | T2.2    | get_kafka_cluster (ns) | core | | |
| 2     | T2.3    | get_kafka_cluster (nonexistent) | core | | |
| 2     | T2.4    | get_kafka_cluster (wrong ns) | core | | |
| 2     | T2.5    | get_kafka_bootstrap_servers | core | | |
| 2     | T2.6    | get_kafka_cluster_certificates | core | | |
| 2     | T2.7    | get_kafka_cluster_certificates (listener) | core | | |
| 2     | T2.8    | get_kafka_cluster_pods | core | | |
| 2     | T2.9    | get_kafka_cluster_config | core | | |
| 2     | T2.10   | get_kafka_cluster_logs | core | | |
| 2     | T2.11   | get_kafka_cluster_logs (filter) | core | | |
| 2     | T2.12   | get_kafka_cluster_logs (keywords) | core | | |
| 2     | T2.13   | get_kafka_cluster_logs (specific pod) | core | | |
| 2     | T2.14   | get_kafka_cluster_logs (no match) | core | | |
| 3     | T3.1    | list_kafka_topics | core | | |
| 3     | T3.2    | list_kafka_topics (paginated) | core | | |
| 3     | T3.3    | list_kafka_topics (page 2) | core | | |
| 3     | T3.4    | get_kafka_topic | --test-clients | | |
| 3     | T3.5    | get_kafka_topic (nonexistent) | core | | |
| 4     | T4.1    | list_kafka_node_pools | core | | |
| 4     | T4.2    | get_kafka_node_pool (controller) | core | | |
| 4     | T4.3    | get_kafka_node_pool (broker) | core | | |
| 4     | T4.4    | get_kafka_node_pool_pods | core | | |
| 4     | T4.5    | get_kafka_node_pool (nonexistent) | core | | |
| 5     | T5.1    | list_kafka_users | core | | |
| 5     | T5.2    | list_kafka_users (cluster) | core | | |
| 5     | T5.3    | get_kafka_user (SCRAM+quotas) | core | | |
| 5     | T5.4    | get_kafka_user (TLS) | core | | |
| 5     | T5.5    | get_kafka_user (admin) | core | | |
| 5     | T5.6    | get_kafka_user (nonexistent) | core | | |
| 6     | T6.1    | list_strimzi_operators | core | | |
| 6     | T6.2    | get_strimzi_operator | core | | |
| 6     | T6.3    | get_strimzi_operator (ns) | core | | |
| 6     | T6.4    | get_strimzi_operator_logs | core | | |
| 6     | T6.5    | get_strimzi_operator_logs (filter) | core | | |
| 6     | T6.6    | get_strimzi_operator_pod | core | | |
| 7     | T7.1    | get_strimzi_events (Kafka) | core | | |
| 7     | T7.2    | get_strimzi_events (time-scoped) | core | | |
| 7     | T7.3    | get_strimzi_events (Operator) | core | | |
| 7     | T7.4    | get_strimzi_events (nonexistent) | core | | |
| 7     | T7.5    | get_strimzi_events (invalid kind) | core | | |
| 8     | T8.1    | list_kafka_rebalances (empty) | core | | |
| 8     | T8.2    | list_kafka_rebalances (cluster) | core | | |
| 8     | T8.3    | get_kafka_rebalance (nonexistent) | core | | |
| 9     | T9.1    | list_drain_cleaners | --drain-cleaner | | |
| 9     | T9.2    | get_drain_cleaner | --drain-cleaner | | |
| 9     | T9.3    | check_drain_cleaner_readiness | --drain-cleaner | | |
| 9     | T9.4    | get_drain_cleaner_logs | --drain-cleaner | | |
| 9     | T9.5    | get_strimzi_events (DrainCleaner) | --drain-cleaner | | |
| 10    | T10.1   | list_kafka_connects | --connect | | |
| 10    | T10.2   | get_kafka_connect | --connect | | |
| 10    | T10.3   | get_kafka_connect_pods | --connect | | |
| 10    | T10.4   | get_kafka_connect_logs | --connect | | |
| 10    | T10.5   | get_strimzi_events (KafkaConnect) | --connect | | |
| 11    | T11.1   | list_kafka_connectors | --connect | | |
| 11    | T11.2   | list_kafka_connectors (cluster) | --connect | | |
| 11    | T11.3   | get_kafka_connector | --connect | | |
| 12    | T12.1   | list_kafka_bridges | --bridge | | |
| 12    | T12.2   | get_kafka_bridge | --bridge | | |
| 12    | T12.3   | get_kafka_bridge_pods | --bridge | | |
| 12    | T12.4   | get_kafka_bridge_logs | --bridge | | |
| 12    | T12.5   | get_strimzi_events (KafkaBridge) | --bridge | | |
| 13    | T13.1   | list_kafka_mirror_makers | --mirror-maker | | |
| 13    | T13.2   | get_kafka_mirror_maker | --mirror-maker | | |
| 13    | T13.3   | get_kafka_mirror_maker_pods | --mirror-maker | | |
| 13    | T13.4   | get_kafka_mirror_maker_logs | --mirror-maker | | |
| 13    | T13.5   | get_strimzi_events (MM2) | --mirror-maker | | |
| 13    | T13.6   | get_kafka_cluster (mirror) | --mirror-maker | | |
| 13    | T13.7   | list_kafka_node_pools (mirror) | --mirror-maker | | |
| 14    | T14.1   | get_kafka_metrics | core | | |
| 14    | T14.2   | get_kafka_metrics (replication) | core | | |
| 14    | T14.3   | get_kafka_metrics (time range) | core | | |
| 14    | T14.4   | get_kafka_exporter_metrics | core | | |
| 14    | T14.5   | get_kafka_bridge_metrics | --bridge | | |
| 14    | T14.6   | get_kafka_connect_metrics | --connect | | |
| 14    | T14.7   | get_strimzi_operator_metrics | core | | |
| 14    | T14.8   | get_strimzi_operator_metrics (cluster) | core | | |
| 14    | T14.9   | get_kafka_metrics (nonexistent) | core | | |
| 15    | T15.1   | diagnose_kafka_cluster | core | | |
| 15    | T15.2   | diagnose_kafka_cluster (symptom) | core | | |
| 15    | T15.3   | diagnose_kafka_connectivity | core | | |
| 15    | T15.4   | diagnose_kafka_connectivity (listener) | core | | |
| 15    | T15.5   | diagnose_kafka_metrics | core | | |
| 15    | T15.6   | diagnose_operator_metrics | core | | |
| 15    | T15.7   | diagnose_kafka_topic | --test-clients | | |
| 15    | T15.8   | diagnose_kafka_connect | --connect | | |
| 15    | T15.9   | diagnose_kafka_connector | --connect | | |
| 15    | T15.10  | diagnose_kafka_mirror_maker | --mirror-maker | | |
| 15    | T15.11  | compare_kafka_clusters | --mirror-maker | | |
| 15    | T15.12  | assess_upgrade_readiness | core | | |
| 15    | T15.13  | diagnose_kafka_cluster (nonexistent) | core | | |
| 16    | T16.1   | error: wrong namespace | core | | |
| 16    | T16.2   | error: empty namespace | core | | |
| 16    | T16.3   | error: short metric range | core | | |
| 16    | T16.4   | perf: large log request | core | | |
| 17    | E2E-1   | Cluster Health Check | core | | |
| 17    | E2E-2   | Topic Investigation | --test-clients | | |
| 17    | E2E-3   | Connectivity Troubleshooting | core | | |
| 17    | E2E-4   | Operator Health | core | | |
| 17    | E2E-5   | KafkaConnect Pipeline | --connect | | |
| 17    | E2E-6   | Upgrade Readiness | core | | |
| 17    | E2E-7   | Cross-Cluster Comparison | --mirror-maker | | |
| 18    | T18.1   | list_kafka_clusters (accessible ns) | rbac-scoped | | |
| 18    | T18.2   | list_kafka_clusters (inaccessible ns) | rbac-scoped | | |
| 18    | T18.3   | list_kafka_clusters (cluster-wide) | rbac-scoped | | |
| 18    | T18.4   | get_kafka_cluster (accessible ns) | rbac-scoped | | |
| 18    | T18.5   | get_kafka_cluster (inaccessible ns) | rbac-scoped | | |
| 18    | T18.6   | get_kafka_cluster (auto-discover) | rbac-scoped | | |
| 18    | T18.7   | get_kafka_fleet_overview (accessible) | rbac-scoped | | |
| 18    | T18.8   | get_kafka_fleet_overview (inaccessible) | rbac-scoped | | |
| 18    | T18.9   | get_kafka_fleet_overview (cluster-wide) | rbac-scoped | | |
| 18    | T18.10  | diagnose_kafka_cluster (accessible) | rbac-scoped | | |
| 18    | T18.11  | diagnose_kafka_cluster (inaccessible) | rbac-scoped | | |
| 18    | T18.12  | list_drain_cleaners (cluster-scope) | rbac-scoped | | |
| 18    | T18.13  | certificates (no sensitive RBAC) | rbac-scoped | | |
| 18    | T18.14  | certificates (with sensitive RBAC) | rbac-scoped | | |
| 19    | T19.1   | fleet overview (both clusters) | multi-cluster | | |
| 19    | T19.2   | list clusters (both in same ns) | multi-cluster | | |
| 19    | T19.3   | get cluster 1 | multi-cluster | | |
| 19    | T19.4   | get cluster 2 | multi-cluster | | |
| 19    | T19.5   | bootstrap servers differ | multi-cluster | | |
| 19    | T19.6   | node pools per cluster | multi-cluster | | |
| 19    | T19.7   | cluster overview per cluster | multi-cluster | | |
| 19    | T19.8   | compare clusters | multi-cluster | | |
| 20    | T20.1   | get cluster (ambiguous) | cross-ns | | |
| 20    | T20.2   | get cluster (resolved ns-1) | cross-ns | | |
| 20    | T20.3   | get cluster (resolved ns-2) | cross-ns | | |
| 20    | T20.4   | fleet overview (all) | cross-ns | | |
| 20    | T20.5   | fleet overview (per ns) | cross-ns | | |
| 20    | T20.6   | list clusters (per ns) | cross-ns | | |
| 20    | T20.7   | compare (cross-ns) | cross-ns | | |
| 20    | T20.8   | bootstrap servers (per ns) | cross-ns | | |
| 20    | T20.9   | diagnose (with ns) | cross-ns | | |
