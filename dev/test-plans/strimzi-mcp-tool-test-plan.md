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

The Kafka version used throughout this plan is the one pinned in
`dev/manifests/strimzi/kafka/010-Kafka.yaml` (currently `4.3.1`, metadata version `4.3-IV0`).
Update the expected versions below when the manifests are bumped.

### Sensitive RBAC prerequisite

Certificate checks (T2.6, T2.7, T9.3, T15.3, T15.12, T15.12b, E2E-3, E2E-6) read Secrets, which needs the
optional sensitive Role. It is not part of any overlay, so grant it in the Strimzi namespaces before running the plan:

```bash
for ns in strimzi-kafka strimzi-kafka-mirror strimzi-drain-cleaner; do
  kubectl apply -n "$ns" -f install/strimzi-mcp/optional/role-sensitive.yaml
  kubectl apply -n "$ns" -f install/strimzi-mcp/optional/rolebinding-sensitive.yaml
done
```

Without it, these checks return `403 Forbidden` entries in `errors` and the Drain Cleaner TLS check cannot pass.

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
| Kafka          | `mcp-cluster`       | `strimzi-kafka` | v4.3.1, 4 listeners (plain/tls/route/console), Cruise Control, Kafka Exporter |
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
| Kafka             | `mcp-cluster-mirror`  | `strimzi-kafka-mirror`  | v4.3.1, 1 combined node, plain listener only, RF=1 |
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

Client capability notes:

- **Error codes:** some clients (e.g. Claude Code) show only the error message text, not the JSON-RPC
  `code` or `error.data`. When a test expects a specific code or `error.data` field and only the text is
  visible, record PARTIAL with the text you saw.
- **Sampling:** `analysis` fields, GO/NO-GO verdicts and "highlighted" differences are produced by LLM
  Sampling. With a client that does not advertise Sampling, diagnostics gather all areas and return raw
  data only; judge those tests on the raw data.

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
- [ ] Each cluster entry includes: name, namespace, `readiness`, broker count
- [ ] `mcp-cluster` shows 6 brokers (broker-np1: 3 + broker-np2: 3)
- [ ] `readiness` values are one of: Ready, NotReady, Unknown

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
- [ ] Each object has: name, namespace, `readiness`, `kafka_version` (`4.3.1`), listeners
- [ ] Each entry includes: `running_kafka_version`, `kafka_metadata_version`, `operator_last_successful_version`, `cluster_id`
- [ ] Each entry includes: `cluster_security` (encryption, authentication)
- [ ] `auto_rebalance` is absent (Strimzi only sets `status.autoRebalance` when `spec.cruiseControl.autoRebalance` is configured, which the dev cluster does not do)
- [ ] Each entry includes: `reconciliation` (generation, observed_generation, up_to_date)

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
- [ ] Kafka version: `4.3.1`
- [ ] 4 listeners returned, each with `name`, `type` and `bootstrap_address` (port, TLS and auth details are checked in T2.6 and T2.9):
  - `plain`: internal, `...-kafka-bootstrap.strimzi-kafka.svc:9092`
  - `tls`: internal, `...-kafka-bootstrap.strimzi-kafka.svc:9093`
  - `route`: route, `<route host>:443`
  - `console`: internal, `...-kafka-bootstrap.strimzi-kafka.svc:9777`
- [ ] `authorization_enabled` is `true` (the authorization type `simple` is checked in T2.9)
- [ ] Conditions section shows Ready condition
- [ ] `running_kafka_version` present and matches `4.3.1` (or a patch thereof)
- [ ] `kafka_metadata_version` present (non-null string, `4.3-IV0`)
- [ ] `operator_last_successful_version` present
- [ ] `cluster_id` present and non-empty
- [ ] `auto_rebalance` absent (autoRebalance is not configured on the dev cluster)
- [ ] `cluster_security` present: `encryption` (`strimzi-tls`) and `authentication` (`strimzi-mtls`) fields populated
- [ ] `reconciliation` present: `generation` and `observed_generation` are equal, `up_to_date` is `true`

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
- [ ] `route` listener: external route address on port 443 (OpenShift route; 9666 is the listener's internal port)

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
- [ ] `cluster_ca_policy` present: `renewal_days`, `validity_days`, `generate_certificate_authority`, `certificate_expiration_policy`, `calculated_renewal_date` all populated
- [ ] `clients_ca_policy` present: same sub-fields as `cluster_ca_policy`

### T2.7 - Get Cluster Certificates (specific listener)

```
Tool: get_kafka_cluster_certificates
Parameters: { "clusterName": "mcp-cluster", "listenerName": "tls" }
```

**Expected:**
- [ ] Returns certificate info specific to the `tls` listener

### T2.7b - Get Cluster Policies

```
Tool: get_kafka_cluster_policies
Parameters: { "clusterName": "mcp-cluster" }
```

**Expected:**
- [ ] `cluster_name` matches "mcp-cluster"
- [ ] `pod_disruption_budgets` array contains PDBs created for Kafka components
- [ ] `network_policies` array contains NetworkPolicies created for Kafka components

### T2.7c - Get Cluster PersistentVolumeClaims

```
Tool: get_kafka_pvcs
Parameters: { "clusterName": "mcp-cluster" }
```

**Expected:**
- [ ] `cluster_name` matches "mcp-cluster"
- [ ] `pvcs` array contains PVCs with phase `Bound`, storage class, requested/actual capacity, volume name, and `allow_volume_expansion`

### T2.7d - Get StrimziPodSets (rolling update state)

```
Tool: get_strimzi_pod_sets
Parameters: { "clusterName": "mcp-cluster" }
```

**Expected:**
- [ ] `count` is 3 and `items` has 3 StrimziPodSets (one per node pool: `mcp-cluster-controller-np`, `mcp-cluster-broker-np1`, `mcp-cluster-broker-np2`)
- [ ] Each item has `cluster` equal to "mcp-cluster" and the correct `namespace`
- [ ] Each item reports `pods_count`, `ready_pods`, and `current_pods` equal to 3 (cluster is healthy and fully rolled out)
- [ ] `pod_revisions` is absent on every item in steady state (all pods share one revision; the map appears only during a rolling update)

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
- [ ] Each Kafka node pod's `annotations` map includes `strimzi.io/revision` (non-null string)
- [ ] Each Kafka node pod's `annotations` map includes `strimzi.io/cluster-ca-cert-generation` and `strimzi.io/clients-ca-cert-generation` (e.g. `"0"`)
- [ ] Each Kafka node pod's `annotations` map includes `strimzi.io/server-cert-hash` with a real hash value (not `[REDACTED]`; a hash is not a secret)
- [ ] Non-Kafka pods (EO, CC, KE) carry their own Strimzi annotations; the CA generation / revision keys above are only guaranteed on Kafka node pods

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
- [ ] `authorization.type` is `simple`
- [ ] `listeners[]` carries per-listener details:
  - `plain`: port 9092, internal, tls=false, no auth
  - `tls`: port 9093, internal, tls=true, `auth_type`=tls
  - `route`: port 9666, route, tls=true, `auth_type`=tls
  - `console`: port 9777, internal, tls=true, `auth_type`=scram-sha-512
- [ ] `tiered_storage` field present (null or a `TieredStorageInfo` object — dev cluster does not use tiered storage, so it should be null/absent)
- [ ] `quotas` field present (null or a `QuotasInfo` object — dev cluster has no cluster-level quotas plugin configured, so it should be null/absent)

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

### T2.15 - Get Cluster Logs (single container)

```
Tool: get_kafka_cluster_logs
Parameters: {
  "clusterName": "mcp-cluster",
  "container": "kafka",
  "tailLines": 20
}
```

**Expected:**
- [ ] Returns log lines only from the 'kafka' container
- [ ] No logs from other containers (e.g., init containers)
- [ ] Logs are grouped by pod name
- [ ] At most 20 lines per pod

**System test coverage:**
- Kubernetes provider: `KafkaClusterToolsST.testGetKafkaClusterLogsContainerSelector()`
- Loki provider: `LogsLokiToolsST.testGetKafkaClusterLogsContainerViaLoki()`
- OpenSearch provider: `LogsOpenSearchToolsST.testGetKafkaClusterLogsContainerViaOpenSearch()`

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
- [ ] Each topic entry includes: `topic_id` (non-null Kafka topic ID: a UUID in Kafka's 22-character base64 form, e.g. `QOmFlRZ5Rn6wyWgelqDVew`, matching `status.topicId`), `topic_name` (actual Kafka topic name)
- [ ] Each topic entry includes: `conditions` list and `reconciliation` (generation, observed_generation, up_to_date)
- [ ] Each topic entry includes: `managed` flag (`false` only when annotated `strimzi.io/managed=false`)
- [ ] `replicas_change` is null/absent for topics not currently undergoing a replication factor change

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

### T3.3a - List Topics (name filter)

```
Tool: list_kafka_topics
Parameters: { "clusterName": "mcp-cluster", "nameFilter": "mcp-*" }
```

**Expected:**
- [ ] Every returned topic's `name` matches the glob (starts with `mcp-`)
- [ ] `total` reflects the filtered count (filter applied before pagination)

### T3.3b - List Topics (status filter)

```
Tool: list_kafka_topics
Parameters: { "clusterName": "mcp-cluster", "statusFilter": "READY" }
```

**Expected:**
- [ ] Every returned topic has `status` = `Ready`
- [ ] An invalid value (e.g. `statusFilter`: `Bogus`) is rejected by the schema `enum` before the tool runs

### T3.3c - List Topics (unmanaged filter)

```
Tool: list_kafka_topics
Parameters: { "clusterName": "mcp-cluster", "statusFilter": "UNMANAGED" }
```

**Expected:**
- [ ] Returns only topics annotated `strimzi.io/managed=false` (each has `managed`: false)
- [ ] Empty result when no unmanaged topics exist

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
- [ ] `topic_id` present and non-empty (UUID assigned by Kafka)
- [ ] `topic_name` present (equals `mcp-test-topic` unless a custom `spec.topicName` is set)
- [ ] `replicas_change` is null/absent (no RF change in progress)
- [ ] `conditions` list present with at least one Ready condition
- [ ] `reconciliation.up_to_date` is `true` (generation == observed_generation)

### T3.5 - Get Topic (nonexistent)

```
Tool: get_kafka_topic
Parameters: { "clusterName": "mcp-cluster", "topicName": "nonexistent-topic-xyz" }
```

**Expected:**
- [ ] Returns error or empty response indicating topic not found

### T3.5a - Get Topic (Kafka name that is not a Kubernetes name)

```
Tool: get_kafka_topic
Parameters: { "clusterName": "mcp-cluster", "topicName": "__consumer_offsets" }
```

**Expected:**
- [ ] Not rejected as an invalid name; returns the KafkaTopic managing `__consumer_offsets` if one exists, otherwise "not found"
- [ ] A topic whose `spec.topicName` contains `_` or uppercase letters is returned when queried by that Kafka name
- [ ] A topic that exists in two namespaces queried without `namespace` returns an ambiguity error listing both namespaces

### T3.5b - Get Topic (wrong cluster)

> Requires: `--test-clients`

```
Tool: get_kafka_topic
Parameters: { "clusterName": "mcp-cluster-mirror", "topicName": "mcp-test-topic" }
```

**Expected:**
- [ ] Not-found error whose message contains `Only cluster 'mcp-cluster-mirror' was searched; the topic exists in cluster(s) 'mcp-cluster' (namespace ...)` with no topic details (partitions, config) in the error
- [ ] When the topic exists in more than five clusters, the message lists five and an `and N more` tail
- [ ] On an estate with more than 2000 KafkaTopics, the plain not-found error is returned without the cluster hint

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
- [ ] Each entry includes: `node_ids` (list of assigned node ID integers), `status_replicas` (actual running replicas), `status_roles` (roles from status)
- [ ] Each entry includes: `conditions` list and `ready` boolean
- [ ] Each entry includes: `reconciliation` (generation, observed_generation, up_to_date)

### T4.2 - Get Node Pool (controller)

```
Tool: get_kafka_node_pool
Parameters: { "clusterName": "mcp-cluster", "nodePoolName": "controller-np" }
```

**Expected:**
- [ ] Roles: `[controller]`
- [ ] Replicas: 3
- [ ] Storage: JBOD with 1 volume, 10Gi persistent-claim
- [ ] `node_ids` contains exactly 3 integers (controller node IDs)
- [ ] `status_replicas` is 3
- [ ] `status_roles` is `[controller]`
- [ ] `ready` is `true`
- [ ] `conditions` list present (Strimzi sets no Ready condition on KafkaNodePool, so it is empty or holds warnings only)
- [ ] `reconciliation.up_to_date` is `true`

### T4.3 - Get Node Pool (broker)

```
Tool: get_kafka_node_pool
Parameters: { "clusterName": "mcp-cluster", "nodePoolName": "broker-np1" }
```

**Expected:**
- [ ] Roles: `[broker]`
- [ ] Replicas: 3
- [ ] Storage: JBOD with 2 volumes, each 50Gi persistent-claim
- [ ] `node_ids` contains exactly 3 integers (broker node IDs, distinct from `controller-np`)
- [ ] `status_replicas` is 3
- [ ] `status_roles` is `[broker]`
- [ ] `ready` is `true`
- [ ] `conditions` list present (empty or warnings only, as for T4.2)
- [ ] `reconciliation.up_to_date` is `true`

### T4.4 - Get Node Pool Pods

```
Tool: get_kafka_node_pool_pods
Parameters: { "clusterName": "mcp-cluster", "nodePoolName": "broker-np1" }
```

**Expected:**
- [ ] Returns exactly 3 pods
- [ ] All pods are Running and Ready
- [ ] Pod names contain `broker-np1`
- [ ] Each pod's `annotations` map includes `strimzi.io/revision`, `strimzi.io/cluster-ca-cert-generation`, `strimzi.io/clients-ca-cert-generation` and `strimzi.io/server-cert-hash` (all non-null, the hash not `[REDACTED]`; these are Kafka broker pods)

### T4.5 - Get Node Pool (nonexistent)

```
Tool: get_kafka_node_pool
Parameters: { "clusterName": "mcp-cluster", "nodePoolName": "nonexistent-pool" }
```

**Expected:**
- [ ] Returns error indicating node pool not found

### T4.6 - Diagnose Node Pool

```
Tool: diagnose_kafka_node_pool
Parameters: { "nodePoolName": "broker-np1", "clusterName": "mcp-cluster" }
```

**Expected:**
- [ ] `node_pool.name` is `broker-np1`, `node_pool.cluster` is `mcp-cluster`
- [ ] `pods` section present with pod health for the pool
- [ ] `pvcs` is an array (PVC storage binding status)
- [ ] `steps_completed` contains `node_pool` and `pods`
- [ ] Phase 2 areas (`pvcs`, `events`, `logs`) appear in `steps_completed` or `steps_failed`
- [ ] With a Sampling-capable client: `analysis` is present; without one it is omitted
- [ ] `timestamp` and `message` are present

### T4.7 - Diagnose Node Pool (not found)

```
Tool: diagnose_kafka_node_pool
Parameters: { "nodePoolName": "nonexistent-pool" }
```

**Expected:**
- [ ] JSON-RPC error `-32002` with `error.data.category` `RESOURCE_NOT_FOUND`
- [ ] Message names `nonexistent-pool`, no stack trace

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

### T5.7 - Get User ACLs Matrix (topics, default)

```
Tool: get_kafka_user_acls_matrix
Parameters: { "clusterName": "mcp-cluster" }
```

**Expected:**
- [ ] `resource_type` defaults to `topic`
- [ ] `matrix` has a `*` entry aggregating the admin and TLS users' wildcard topic grants (plus `mm2-user` with `All` when `--mirror-maker` is deployed)
- [ ] `matrix` has a `test-`/`orders-`-prefixed entry rendered with a trailing `*` (prefix pattern)
- [ ] `broad_grants` flags the admin user's `*` grant as `"wildcard resource, All operations"` and the TLS user's `*` grant as `"wildcard resource"`; with `--mirror-maker`, `mm2-user`'s `*` grant is also flagged as `"wildcard resource, All operations"`
- [ ] `resource_count`, `principal_count`, and `message` are consistent with the matrix contents

### T5.8 - Get User ACLs Matrix (invalid resourceType)

```
Tool: get_kafka_user_acls_matrix
Parameters: { "clusterName": "mcp-cluster", "resourceType": "bogus" }
```

**Expected:**
- [ ] Returns a tool error -- `resourceType` is a schema enum (`AclResourceType`), so the framework rejects an unknown value before the tool runs
- [ ] The error response contains no stack trace

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

### T6.5a - Get Operator Logs (single container)

```
Tool: get_strimzi_operator_logs
Parameters: { "container": "strimzi-cluster-operator", "tailLines": 50 }
```

**Expected:**
- [ ] Returns log lines only from the 'strimzi-cluster-operator' container
- [ ] At most 50 lines returned

### T6.6 - Get Operator Pod

> Note: `get_strimzi_operator` (T6.2) does not return the pod name. Take it from `operator_pods` in the
> `get_strimzi_operator_logs` result (T6.4), or from `kubectl get pods -n strimzi`
> (e.g., `strimzi-cluster-operator-<suffix>`).

```
Tool: get_strimzi_operator_pod
Parameters: { "namespace": "strimzi", "podName": "<operator-pod-name>", "sections": "full" }
```

**Expected:**
- [ ] Returns detailed pod info: containers, volumes, conditions (without `sections` the tool returns a summary only)

---

### T6.7 - Get Operator Config

```
Tool: get_strimzi_operator_config
Parameters: { "operatorName": "strimzi-cluster-operator", "namespace": "strimzi" }
```

**Expected:**
- [ ] Returns `watched_namespaces` / `watches_all_namespaces`, `operation_timeout_ms`, `full_reconciliation_interval_ms`, `leader_election_enabled`
- [ ] `feature_gates` is present only when `STRIMZI_FEATURE_GATES` is set (empty on the dev operator, so absent)
- [ ] `supported_kafka_versions` is a non-empty list parsed from `STRIMZI_KAFKA_IMAGES`
- [ ] `config` contains operator environment variables, with `valueFrom` entries encoded as descriptive reference strings
- [ ] Omitting `operatorName` auto-discovers the operator and returns the same result

---

### T6.8 - Get Operator Config (not found)

```
Tool: get_strimzi_operator_config
Parameters: { "operatorName": "non-existent-operator", "namespace": "strimzi" }
```

**Expected:**
- [ ] Protocol error `-32002` with `category: RESOURCE_NOT_FOUND` naming the operator

---

## Phase 7: Strimzi Events

### T7.1 - Get Events for Kafka Cluster

```
Tool: get_strimzi_events
Parameters: { "resourceName": "mcp-cluster", "resourceKind": "Kafka" }
```

**Expected:**
- [ ] Returns Kubernetes events related to `mcp-cluster`
- [ ] Events grouped by resource: pods and PVCs (a Kafka CR group appears only if the CR itself has events; Strimzi usually emits none)
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

### T8.4 - Diagnose Kafka Rebalance (nonexistent)

```
Tool: diagnose_kafka_rebalance
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
- [ ] *(Only with a Drain Cleaner that sets env via `valueFrom`/`envFrom`; the dev manifests use literal values, so SKIP otherwise)* A setting supplied through `valueFrom` or a matching `envFrom` ConfigMap/Secret shows `unknown (...)` naming the source, not a value

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
- [ ] *(Only with `valueFrom`/`envFrom` env, SKIP on the dev manifests)* `STRIMZI_DENY_EVICTION` set through `valueFrom` or a matching `envFrom` source shows `unknown (...)` naming the source; a literal `env` entry wins over `envFrom`

### T9.3 - Check Drain Cleaner Readiness

```
Tool: check_drain_cleaner_readiness
Parameters: {}
```

> Requires the sensitive RBAC prerequisite in `strimzi-drain-cleaner` (the TLS check reads the `strimzi-drain-cleaner` Secret).

**Expected:**
- [ ] Deployment: healthy
- [ ] Webhook: configured
- [ ] Certificates: valid
- [ ] Overall verdict: ready
- [ ] *(Only with `valueFrom`/`envFrom` env, SKIP on the dev manifests)* When `STRIMZI_DENY_EVICTION` comes from `valueFrom` or a matching `envFrom` source, the Operating mode check fails with a recommendation naming the source; the `envFrom` case also notes that an unset variable means the default (standard mode)

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
- [ ] Bootstrap on the TLS port (9093). The response has no authentication/TLS fields; the spec's `authentication.type: tls` is not surfaced
- [ ] Build plugins include: `camel-timer-source` (v4.18.0)
- [ ] Status conditions present
- [ ] `build` object present (dev cluster uses `spec.build`):
  - [ ] `build.output_image` is present and non-empty
  - [ ] `build.output_type` is `docker` or `imagestream`
  - [ ] `build.build_mechanism` is `KubernetesPod` or `OpenShiftBuild`
  - [ ] `build.plugins` array contains at least one plugin with `name` and `artifacts`
  - [ ] `build.plugins[0].artifacts` contains at least one artifact with `type` and `source`; `source` is the full Maven URL, unaltered (`https://repo.maven.apache.org/maven2/org/apache/camel/kafkaconnector/...`)
  - [ ] `build.build_logs` is null/absent (dev cluster build is healthy)
  - [ ] `build.builder` is absent on Kubernetes once the operator deletes the builder pod; on OpenShift it may be present with `phase=Complete` (the Build object persists)
  - [ ] `build.message` indicates build state (e.g., "Build completed" or "Build configured")

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
- [ ] Should show connector activity. The tail is mostly REST access logs, so confirm with
  `{ "connectName": "mcp-connect", "keywords": ["WorkerSourceTask"], "tailLines": 5 }`, which shows
  `WorkerSourceTask{id=mcp-timer-source-0} Committing offsets ...`

### T10.4a - Get Connect Logs (single container)

```
Tool: get_kafka_connect_logs
Parameters: { "connectName": "mcp-connect", "container": "mcp-connect-connect", "tailLines": 50 }
```

**Expected:**
- [ ] Returns log lines only from the `mcp-connect-connect` container (Strimzi names the container `<connect-name>-connect`)
- [ ] At most 50 lines returned

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
- [ ] `offsets.config_map_name`: `mcp-timer-source-offsets`
- [ ] Non-secret config keys (e.g. `topics`, `camel.kamelet.timer-source.period`) are returned unchanged
- [ ] *(Only when the connector config has secret-named keys, e.g. `*.password`, `*.secret`, `sasl.jaas.config`; the dev connector has none, so SKIP)* their values are `[REDACTED]`. Converter settings such as `key.converter` live in the KafkaConnect `spec.config`, not in the connector

### T11.4 - Get Connector Offsets

The dev connector declares `spec.listOffsets.toConfigMap: mcp-timer-source-offsets`, but the operator only
publishes offsets on request.

> On a re-run the ConfigMap from the previous run still exists, so the "before" step shows `available: true`.
> Delete it first (`kubectl delete configmap mcp-timer-source-offsets -n strimzi-kafka`) or skip the "before" checks.

```
Tool: get_kafka_connector
Parameters: { "connectorName": "mcp-timer-source" }
```

**Expected (before requesting offsets):**
- [ ] `offsets.config_map_name`: `mcp-timer-source-offsets`
- [ ] `offsets.available`: `false`
- [ ] `offsets.message` names the `strimzi.io/connector-offsets` annotation
- [ ] No `offsets.offsets` payload

Then request them and re-run the tool:

```bash
kubectl annotate -n strimzi-kafka kafkaconnector mcp-timer-source strimzi.io/connector-offsets=list
```

**Expected (after the operator writes the ConfigMap):**
- [ ] `offsets.available`: `true`
- [ ] `offsets.offsets` carries an `offsets` array
- [ ] No `offsets.message`

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

### T12.4a - Get Bridge Logs (single container)

```
Tool: get_kafka_bridge_logs
Parameters: { "bridgeName": "mcp-bridge", "container": "mcp-bridge-bridge", "tailLines": 50 }
```

**Expected:**
- [ ] Returns log lines only from the `mcp-bridge-bridge` container (Strimzi names the container `<bridge-name>-bridge`)
- [ ] At most 50 lines returned

### T12.5 - Get Events for KafkaBridge

```
Tool: get_strimzi_events
Parameters: { "resourceName": "mcp-bridge", "resourceKind": "KafkaBridge" }
```

**Expected:**
- [ ] Returns events for `mcp-bridge`

### T12.6 - Diagnose Bridge

```
Tool: diagnose_kafka_bridge
Parameters: { "bridgeName": "mcp-bridge" }
```

**Expected:**
- [ ] `bridge.name` is `mcp-bridge`, `bridge.readiness` is `Ready`
- [ ] `pods.pod_summary.total_pods` is 1
- [ ] `steps_completed` contains `bridge_status` and `bridge_pods`
- [ ] Phase 2 areas (`bridge_logs`, `events`, `metrics`) appear in `steps_completed` or `steps_failed`
- [ ] With a Sampling-capable client: `analysis` is present; without one it is omitted
- [ ] `timestamp` and `message` are present

### T12.7 - Diagnose Bridge (not found)

```
Tool: diagnose_kafka_bridge
Parameters: { "bridgeName": "no-such-bridge" }
```

**Expected:**
- [ ] JSON-RPC error `-32002` with `error.data.category` `RESOURCE_NOT_FOUND`
- [ ] Message names `no-such-bridge`, no stack trace

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
- [ ] Version: 4.3.1, replicas: 1
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

### T13.4a - Get MirrorMaker Logs (single container)

```
Tool: get_kafka_mirror_maker_logs
Parameters: { "mirrorMakerName": "mcp-mirror-maker", "container": "mcp-mirror-maker-mirrormaker2", "tailLines": 50 }
```

**Expected:**
- [ ] Returns log lines only from the `mcp-mirror-maker-mirrormaker2` container (Strimzi names the container `<mm2-name>-mirrormaker2`)
- [ ] At most 50 lines returned

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
- [ ] Version: 4.3.1
- [ ] Only 1 listener: `plain`, bootstrap address `mcp-cluster-mirror-kafka-bootstrap.strimzi-kafka-mirror.svc:9092` (TLS details are in `get_kafka_cluster_config`)
- [ ] No authorization configured (`authorization_enabled: false`)
- [ ] `running_kafka_version` present
- [ ] `cluster_id` present and non-empty
- [ ] `reconciliation.up_to_date` is `true`
- [ ] `cluster_security` present: `encryption` `strimzi-tls`, `authentication` `strimzi-mtls` (this is Strimzi's internal inter-component security from `status.clusterSecurity`, not the plain client listener)

### T13.7 - List Mirror Cluster Node Pools

```
Tool: list_kafka_node_pools
Parameters: { "clusterName": "mcp-cluster-mirror" }
```

**Expected:**
- [ ] Returns 1 pool: `mirror-combined`
- [ ] Roles: `[controller, broker]` (combined)
- [ ] Replicas: 1
- [ ] `node_ids` contains exactly 1 integer
- [ ] `status_replicas` is 1
- [ ] `status_roles` contains both `controller` and `broker`
- [ ] `ready` is `true`
- [ ] `reconciliation.up_to_date` is `true`

---

## Phase 14: Metrics Tools

> Metrics require either Prometheus (`--prometheus`) or pod-scraping (default).
> Pod-scraping works without Prometheus but returns point-in-time values only.

### T14.1 - Get Kafka Metrics (default category, then each category)

```
Tool: get_kafka_metrics
Parameters: { "clusterName": "mcp-cluster" }
```

**Expected:**
- [ ] Returns broker metrics from `mcp-cluster`; without `category` the tool returns the `replication` category only (there is no "all categories" mode)
- [ ] Repeating the call with `"category"` set to `throughput`, `performance` and `resources` returns data for each
- [ ] Each metric has interpretation hints

### T14.2 - Get Kafka Metrics (replication)

```
Tool: get_kafka_metrics
Parameters: { "clusterName": "mcp-cluster", "category": "replication" }
```

**Expected:**
- [ ] Returns replication metrics only
- [ ] Includes: `kafka_controller_kafkacontroller_offlinepartitionscount` (should be 0 on healthy cluster — controller pods now retained after B2a filter fix), `kafka_server_replicamanager_underreplicatedpartitions`, `kafka_server_replicamanager_leadercount`

### T14.3 - Get Kafka Metrics (performance — idle ratio, min aggregation)

The dev Kafka cluster exports metrics with the Strimzi Metrics Reporter (`strimziMetricsReporter` in
`010-Kafka.yaml`). On SMR, `kafka_server_kafkarequesthandlerpool_requesthandleravgidle_percent` is unmapped
by design (`KafkaMetricCategories`), so this test uses the network processor idle ratio instead. Pass
`metricNames` without `category`.

```
Tool: get_kafka_metrics
Parameters: {
  "clusterName": "mcp-cluster",
  "metricNames": "kafka_network_socketserver_networkprocessoravgidle_percent"
}
```

**Expected:**
- [ ] The network processor idle ratio returns data (one value close to 1.0 on an idle dev cluster)
- [ ] At the default level the series carries `aggregation_fn` = `min` (the most saturated broker, not the mean across brokers)
- [ ] Repeating the call with `"aggregation": "BROKER"` returns one series per broker pod, and the default value equals the lowest of them
- [ ] *(JMX exporter clusters only)* the same checks pass for `kafka_server_kafkarequesthandlerpool_requesthandleravgidle_percent`
- [ ] `{ "clusterName": "mcp-cluster", "category": "performance" }` returns the request metrics (`requestqueuetimems`, `totaltimems`, ... with p50 and p99) **and** the network processor idle ratio, and is not truncated

### T14.3b - Get Kafka Metrics (time range)

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
- [ ] Default category (`consumer_lag`) returns consumer group lag
- [ ] `"category": "partitions"` with `"aggregation": "TOPIC"` returns topic-level stats (the default level for `partitions` is per partition and very large)
- [ ] `"category": "resources"` returns Go process metrics from the Kafka Exporter pod only: `process_cpu_seconds_rate_per_second` (the Prometheus provider converts `process_cpu_seconds_total` to a rate), `process_resident_memory_bytes`, `process_open_fds`, `go_goroutines` (kafka_exporter is a Go binary — no `jvm_*` metrics)

### T14.5 - Get Bridge Metrics

> Requires: `--bridge`

```
Tool: get_kafka_bridge_metrics
Parameters: { "bridgeName": "mcp-bridge" }
```

**Expected:**
- [ ] Returns bridge metrics (Micrometer metrics from the JVM; JMX exporter is NOT used on the Bridge)
- [ ] Resources category returns Micrometer JVM metrics: `jvm_gc_pause_seconds_count`, `jvm_gc_pause_seconds_sum`, `process_cpu_usage`, `jvm_threads_live_threads` (not the JMX-exporter names `jvm_gc_collection_seconds_*` / `jvm_threads_current` / `process_cpu_seconds_total`)

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
- [ ] Returns Strimzi operator metrics (Micrometer binders; JMX exporter is NOT used on the Operator)
- [ ] Includes: reconciliation counts, durations, managed resource counts
- [ ] JVM category returns Micrometer names: `jvm_gc_pause_seconds_count`, `jvm_gc_pause_seconds_sum`, `process_cpu_usage`, `jvm_threads_live_threads`
- [ ] Resources category includes `strimzi_certificate_expiration_timestamp_ms` (pairing with `get_kafka_cluster_certificates`)

### T14.8 - Get Operator Metrics (specific cluster)

```
Tool: get_strimzi_operator_metrics
Parameters: { "clusterName": "mcp-cluster", "category": "reconciliation" }
```

**Expected:**
- [ ] Returns cluster operator reconciliation metrics (these cover every namespace the operator watches, not only `mcp-cluster`) plus entity operator metrics for `mcp-cluster` (`kind` = `KafkaTopic` / `KafkaUser`)
- [ ] Includes: `strimzi_reconciliations`, `strimzi_reconciliations_failed` (the Prometheus provider returns counters as `<name>_rate_per_second`, e.g. `strimzi_reconciliations_rate_per_second`)
- [ ] Includes: `strimzi_reconciliations_locked` (lock contention), `strimzi_reconciliations_periodical` (timer-triggered), with the same `_rate_per_second` suffix
- [ ] Entity operator series are present (requires the entity-operator PodMonitor to scrape the `healthcheck-to` / `healthcheck-uo` ports)

### T14.9 - Get Cruise Control Metrics

```
Tool: get_cruise_control_metrics
Parameters: { "clusterName": "mcp-cluster", "category": "sampling" }
```

**Expected:**
- [ ] Returns Cruise Control partition monitoring and sample status metrics: `kafka_cruisecontrol_loadmonitor_monitored_partitions_percentage_value`, `kafka_cruisecontrol_loadmonitor_valid_windows_value`, `kafka_cruisecontrol_loadmonitor_total_monitored_windows_value`
- [ ] Sample fetch failure metrics included
- [ ] Category `anomaly` returns balancedness score and goal violation metrics

### T14.10 - Get Metrics (nonexistent cluster)

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
- [ ] `steps_completed` present (`steps_failed` may be omitted when no step failed)
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
- [ ] With a Sampling-capable client: the triage narrows the gathered areas to latency-related metrics and logs
- [ ] Without Sampling: the symptom is accepted, all areas are gathered and metrics use the `replication` category (for latency metrics use `diagnose_kafka_metrics` with `concern`)
- [ ] Time window matches `sinceMinutes: 60` (metrics range 3600 s, logs and events from the last 60 minutes)

### T15.2b - Diagnose Kafka Storage

```
Tool: diagnose_kafka_storage
Parameters: {
  "clusterName": "mcp-cluster",
  "symptom": "disk pressure"
}
```

**Expected:**
- [ ] Report covers PVCs, storage capacity, volume expansion capability, node pool storage specs, pod health, storage metrics, and events
- [ ] Report completed steps include `cluster_status`, `node_pools`, `pvcs`, `pods`, `storage_metrics`, `events`

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
- [ ] With `"symptom": "consumers are lagging"` on a Ready topic and cluster, `steps_completed` still contains operator logs, events and exporter metrics

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
- [ ] Returns both cluster configs side by side, showing the differences:
  - `mcp-cluster`: 6 brokers + 3 controllers, 4 listeners, TLS, authorization, RF=3
  - `mcp-cluster-mirror`: 1 combined node, 1 listener (plain), no auth, RF=1
- [ ] Listener configuration differences visible in the data
- [ ] Replication factor / min ISR values visible (3 / 2 vs 1 / 1)
- [ ] With a Sampling-capable client: `analysis` highlights the significant differences (there is no server-side diff without Sampling)

### T15.12 - Assess Upgrade Readiness

```
Tool: assess_upgrade_readiness
Parameters: { "clusterName": "mcp-cluster" }
```

**Expected:**
- [ ] Returns upgrade readiness report
- [ ] Checks: cluster health, replication, operator readiness (RF=3 / min ISR=2 topics are checked inside `preflight_checks`, which reports nothing when they are fine: expect no `topic_durability` finding for them)
- [ ] The replication check gathers metrics over the last 15 minutes (`rangeMinutes=15`) so lag is judged as a trend; without Prometheus the pod-scraping provider returns a single point instead
- [ ] Checks: certificate validity (requires the sensitive RBAC prerequisite)
- [ ] If `--drain-cleaner`: Drain Cleaner status included
- [ ] With a Sampling-capable client: GO / NO-GO verdict present in `analysis`; without Sampling the raw data and `preflight_findings` are returned and there is no verdict
- [ ] `certificates` and `drain_cleaner` are in `steps_completed` (or `steps_failed`), even when the cluster is Ready
- [ ] `preflight_checks` is in `steps_completed` (or `steps_failed`); a fully successful run completes 11 steps
- [ ] With `"targetVersion": "Kafka 9.9.9"` a `preflight_findings` entry with severity `BLOCKER` and check `version_path` reports the version as unsupported by the operator
- [ ] *(Optional, changes the cluster)* A topic with `min.insync.replicas` equal to its replica count (create one with `min.insync.replicas=3`, `replicas=3`, delete it afterwards) yields a `BLOCKER` `topic_durability` finding naming that topic

### T15.12b - Audit Kafka Security

```
Tool: audit_kafka_security
Parameters: { "clusterName": "mcp-cluster" }
```

**Expected:**
- [ ] Returns security audit report
- [ ] Report includes: `cluster` (listeners, authentication), `authorization_type`, `certificates` (CA and client cert expiry), `users` (KafkaUser inventory)
- [ ] Report includes: `acl_matrices`, one per resource type that has at least one ACL (topic, group and cluster on the dev cluster; transactionalId only if such ACLs exist), with `broad_grants` flagged (wildcard resource, `All` operation, cluster-level access: on the dev cluster `mm2-user`'s `Alter`/`Create`/`Describe` on the cluster is flagged)
- [ ] `certificates` lists the cluster and clients CA (requires the sensitive RBAC prerequisite)
- [ ] `steps_completed` includes: `cluster_security`, `certificates`, `users`, `acls`
- [ ] If Sampling supported: `analysis` contains structured findings (HIGH/MEDIUM/INFORMATIONAL) and recommendations
- [ ] If Sampling not supported: `analysis` is absent, raw data returned
- [ ] If no KafkaUsers: ACL audit skipped, report still completes with `users: []`
- [ ] `message` and `timestamp` present

### T15.13 - Diagnose Cluster (nonexistent)

```
Tool: diagnose_kafka_cluster
Parameters: { "clusterName": "nonexistent-cluster-xyz" }
```

**Expected:**
- [ ] Returns error or report indicating cluster not found
- [ ] Does not hang or timeout

### T15.14 - Stateless Client MRTR - Diagnose Cluster

> **Note:** This test requires a stateless MCP client (streamable HTTP, protocol `2026-07-28`) **that advertises
> Sampling**. A stateless client without Sampling (e.g. Claude Code) gets the full raw report with no
> `input_required`; record SKIP in that case.

```
Tool: diagnose_kafka_cluster
Parameters: { "clusterName": "mcp-cluster" }
```

**Expected (first call):**
- [ ] Returns `input_required` response requesting LLM analysis sampling
- [ ] Includes system prompt and data payload for analysis
- [ ] Includes `requestState` with resolved namespace

**Expected (retry with sampling result):**
- [ ] Returns full diagnostic report with LLM root cause analysis
- [ ] Analysis section populated (not "N/A")
- [ ] Triage section is skipped (stateless clients do not receive triage)

### T15.15 - Stateless Client MRTR - Namespace Disambiguation

> **Note:** This test requires a stateless MCP client and two clusters with the same name in different namespaces (e.g., deploy a second `mcp-cluster` in a separate namespace).

```
Tool: diagnose_kafka_cluster
Parameters: { "clusterName": "mcp-cluster" }
```

**Expected (first call):**
- [ ] Returns `input_required` response requesting namespace selection
- [ ] Lists candidate namespaces (at least 2)
- [ ] Includes elicitation prompt describing the ambiguity

**Expected (retry with namespace selection):**
- [ ] Proceeds to gather data from the selected namespace
- [ ] May return another `input_required` for analysis sampling
- [ ] Final report matches the selected cluster

### T15.16 - Stateless Client MRTR - Compare Clusters (structured error fallback)

> **Note:** This test requires a stateless MCP client. For `compare_kafka_clusters`, namespace ambiguity is handled via structured error (not MRTR elicitation).

```
Tool: compare_kafka_clusters
Parameters: {
  "clusterName1": "mcp-cluster",
  "clusterName2": "mcp-cluster-mirror"
}
```

**Expected (if either cluster name is ambiguous):**
- [ ] Returns structured error with category `AMBIGUOUS`
- [ ] Error data includes candidate namespaces for the ambiguous cluster
- [ ] Client should re-call with explicit `namespace1` and/or `namespace2`

**Expected (after providing explicit namespaces):**
- [ ] Returns `input_required` response requesting analysis sampling (first call)
- [ ] Returns comparison report with LLM analysis (retry with sampling result)

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
- [ ] When truncated, `logs` still holds content close to the size limit (whole log lines, not a ~100-character stub), and the response says it was truncated
- [ ] Asking for more lines never returns less log content than asking for fewer (compare `tailLines` 100, 200 and 1000)
- [ ] Sensitive data is redacted if `mcp.guardrail.log-redaction.enabled` is true
- [ ] Values stored under secret-named JSON fields or config keys are redacted even when the value has no `key=value` shape
- [ ] Container image references are not altered: the digest (`sha256:...`) **and** the image name before it are kept (check a digest-pinned image such as the OpenShift-built Connect image)
- [ ] Log messages containing `@` (e.g. Java `Object@1a2b3c`) keep their text

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
3. `get_kafka_cluster_certificates` for `mcp-cluster` - note CA expiry and listener authentication (requires the sensitive RBAC prerequisite)
4. `list_kafka_users` for `mcp-cluster` - note auth types (SCRAM + TLS users)
5. `diagnose_kafka_connectivity` for `mcp-cluster` - verify all above data referenced

**Expected:**
- [ ] Bootstrap addresses match listener config (4 listeners, 4 bootstrap entries)
- [ ] Cluster CA and clients CA certificates present; `listener_authentication` shows `tls`, `route`, `console` with TLS enabled
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
2. `get_kafka_connect` for `mcp-connect` - verify 1 replica and the bootstrap address on the TLS port 9093 (the response has no authentication field)
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

1. `get_kafka_cluster` for `mcp-cluster` - note version 4.3.1
2. `get_kafka_cluster_pods` for `mcp-cluster` - verify all 12 pods healthy
3. `get_kafka_metrics` with category `replication` - verify 0 offline partitions
4. If `--drain-cleaner`: `check_drain_cleaner_readiness` - verify ready (requires the sensitive RBAC prerequisite)
5. `get_kafka_cluster_certificates` for `mcp-cluster` - verify certs not expired (requires the sensitive RBAC prerequisite)
6. `assess_upgrade_readiness` for `mcp-cluster` - comprehensive assessment

**Expected:**
- [ ] All individual checks pass on a healthy dev cluster
- [ ] With a Sampling-capable client: assessment gives a GO verdict; without Sampling: no `preflight_findings` with severity `BLOCKER` and no failed checks in the raw data
- [ ] All data points from individual tools are referenced in assessment

### E2E-7: Cross-Cluster Comparison (main vs mirror)

> Requires: `--mirror-maker`

1. `get_kafka_cluster` for `mcp-cluster` - note: 4 listeners, auth, RF=3
2. `get_kafka_cluster` for `mcp-cluster-mirror` - note: 1 listener, no auth, RF=1
3. `get_kafka_cluster_config` for `mcp-cluster` - effective config
4. `get_kafka_cluster_config` for `mcp-cluster-mirror` - effective config
5. `compare_kafka_clusters` - verify differences match

**Expected:**
- [ ] The configs returned by `compare_kafka_clusters` match `get_kafka_cluster_config` for both clusters
- [ ] Config differences (replication.factor, min.insync.replicas, listeners) are visible in the data; with a Sampling-capable client, `analysis` highlights and flags them all
- [ ] No false equivalences reported

---

## Phase 18: Namespace-Scoped RBAC (App Developer)

**Requires:** Two Kafka clusters in different namespaces, MCP deployed with namespace-scoped RoleBindings (not ClusterRoleBinding).

**System test class:** `NamespaceScopedRbacST`

**Setup:** MCP has RoleBinding access only to `strimzi-kafka` and `strimzi` namespaces. A second cluster exists in `strimzi-kafka-2` (inaccessible).

This phase tests all three server-side error handling patterns under RBAC restriction:
1. **Direct tools** — 403 propagated as a failed tool response
2. **Fleet overview** — graceful degradation
3. **Diagnostic tools** — partial access

> **Note (structured errors, #229):** RBAC/403 failures remain **failed tool responses** (`isError=true`) —
> `KubernetesResourceService` wraps them with a contextual message ("Failed to query/get … (403 Forbidden)"),
> which several tools rely on for graceful degradation, so they are not JSON-RPC protocol errors.
> By contrast, resource-not-found is now a JSON-RPC error (code `-32002`, `error.data.category = "RESOURCE_NOT_FOUND"`),
> invalid-params is `-32602` (`INVALID_PARAMS`), and ambiguity is `-32602` (`AMBIGUOUS`, with
> `error.data.candidates` listing the namespaces). Rate-limit and cancellation also remain failed tool responses.

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
- **Expected:** JSON-RPC error, code `-32602`, `error.data.category = "AMBIGUOUS"`, `error.data.candidates` lists both namespaces; message contains "Multiple" and both namespace names

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
| 3     | T3.3a   | list_kafka_topics (name filter) | core | | |
| 3     | T3.3b   | list_kafka_topics (status filter) | core | | |
| 3     | T3.3c   | list_kafka_topics (unmanaged filter) | core | | |
| 3     | T3.4    | get_kafka_topic | --test-clients | | |
| 3     | T3.5    | get_kafka_topic (nonexistent) | core | | |
| 4     | T4.1    | list_kafka_node_pools | core | | |
| 4     | T4.2    | get_kafka_node_pool (controller) | core | | |
| 4     | T4.3    | get_kafka_node_pool (broker) | core | | |
| 4     | T4.4    | get_kafka_node_pool_pods | core | | |
| 4     | T4.5    | get_kafka_node_pool (nonexistent) | core | | |
| 4     | T4.6    | diagnose_kafka_node_pool | core | | |
| 4     | T4.7    | diagnose_kafka_node_pool (not found) | core | | |
| 5     | T5.1    | list_kafka_users | core | | |
| 5     | T5.2    | list_kafka_users (cluster) | core | | |
| 5     | T5.3    | get_kafka_user (SCRAM+quotas) | core | | |
| 5     | T5.4    | get_kafka_user (TLS) | core | | |
| 5     | T5.5    | get_kafka_user (admin) | core | | |
| 5     | T5.6    | get_kafka_user (nonexistent) | core | | |
| 5     | T5.7    | get_kafka_user_acls_matrix (topics) | core | | |
| 5     | T5.8    | get_kafka_user_acls_matrix (invalid resourceType) | core | | |
| 6     | T6.1    | list_strimzi_operators | core | | |
| 6     | T6.2    | get_strimzi_operator | core | | |
| 6     | T6.3    | get_strimzi_operator (ns) | core | | |
| 6     | T6.4    | get_strimzi_operator_logs | core | | |
| 6     | T6.5    | get_strimzi_operator_logs (filter) | core | | |
| 6     | T6.6    | get_strimzi_operator_pod | core | | |
| 6     | T6.7    | get_strimzi_operator_config | core | | |
| 6     | T6.8    | get_strimzi_operator_config (not found) | core | | |
| 7     | T7.1    | get_strimzi_events (Kafka) | core | | |
| 7     | T7.2    | get_strimzi_events (time-scoped) | core | | |
| 7     | T7.3    | get_strimzi_events (Operator) | core | | |
| 7     | T7.4    | get_strimzi_events (nonexistent) | core | | |
| 7     | T7.5    | get_strimzi_events (invalid kind) | core | | |
| 8     | T8.1    | list_kafka_rebalances (empty) | core | | |
| 8     | T8.2    | list_kafka_rebalances (cluster) | core | | |
| 8     | T8.3    | get_kafka_rebalance (nonexistent) | core | | |
| 8     | T8.4    | diagnose_kafka_rebalance (nonexistent) | core | | |
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
| 11    | T11.4   | get_kafka_connector (offsets) | --connect | | |
| 12    | T12.1   | list_kafka_bridges | --bridge | | |
| 12    | T12.2   | get_kafka_bridge | --bridge | | |
| 12    | T12.3   | get_kafka_bridge_pods | --bridge | | |
| 12    | T12.4   | get_kafka_bridge_logs | --bridge | | |
| 12    | T12.5   | get_strimzi_events (KafkaBridge) | --bridge | | |
| 12    | T12.6   | diagnose_kafka_bridge | --bridge | | |
| 12    | T12.7   | diagnose_kafka_bridge (not found) | --bridge | | |
| 13    | T13.1   | list_kafka_mirror_makers | --mirror-maker | | |
| 13    | T13.2   | get_kafka_mirror_maker | --mirror-maker | | |
| 13    | T13.3   | get_kafka_mirror_maker_pods | --mirror-maker | | |
| 13    | T13.4   | get_kafka_mirror_maker_logs | --mirror-maker | | |
| 13    | T13.5   | get_strimzi_events (MM2) | --mirror-maker | | |
| 13    | T13.6   | get_kafka_cluster (mirror) | --mirror-maker | | |
| 13    | T13.7   | list_kafka_node_pools (mirror) | --mirror-maker | | |
| 14    | T14.1   | get_kafka_metrics | core | | |
| 14    | T14.2   | get_kafka_metrics (replication) | core | | |
| 14    | T14.3   | get_kafka_metrics (performance, min aggregation) | core | | |
| 14    | T14.3b  | get_kafka_metrics (time range) | core | | |
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
| 15    | T15.12b | audit_kafka_security | core | | |
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
