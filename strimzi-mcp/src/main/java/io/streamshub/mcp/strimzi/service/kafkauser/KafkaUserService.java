/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafkauser;

import io.streamshub.mcp.common.config.KubernetesConstants;
import io.streamshub.mcp.common.dto.ConditionInfo;
import io.streamshub.mcp.common.dto.ReconciliationInfo;
import io.streamshub.mcp.common.service.KubernetesResourceService;
import io.streamshub.mcp.common.util.InputUtils;
import io.streamshub.mcp.common.util.McpErrors;
import io.streamshub.mcp.strimzi.dto.kafkauser.KafkaUserAclMatrixResponse;
import io.streamshub.mcp.strimzi.dto.kafkauser.KafkaUserResponse;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.common.Condition;
import io.strimzi.api.kafka.model.user.KafkaUser;
import io.strimzi.api.kafka.model.user.KafkaUserAuthorizationSimple;
import io.strimzi.api.kafka.model.user.KafkaUserQuotas;
import io.strimzi.api.kafka.model.user.acl.AclResourcePatternType;
import io.strimzi.api.kafka.model.user.acl.AclRule;
import io.strimzi.api.kafka.model.user.acl.AclRuleGroupResource;
import io.strimzi.api.kafka.model.user.acl.AclRuleResource;
import io.strimzi.api.kafka.model.user.acl.AclRuleTopicResource;
import io.strimzi.api.kafka.model.user.acl.AclRuleTransactionalIdResource;
import io.strimzi.api.kafka.model.user.acl.StrimziAclOperation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
/**
 * Service for KafkaUser operations.
 */
@ApplicationScoped
public class KafkaUserService {

    private static final Logger LOG = Logger.getLogger(KafkaUserService.class);

    private static final List<String> ACL_MATRIX_RESOURCE_TYPES =
        List.of("topic", "group", "transactionalId", "cluster");

    private static final String DEFAULT_ACL_MATRIX_RESOURCE_TYPE = "topic";

    /**
     * Matrix key used for ACL rules on the {@code cluster} resource type, which has no name.
     */
    private static final String CLUSTER_RESOURCE_MATRIX_KEY = "kafka-cluster";

    private static final String ALLOW_RULE_TYPE = "allow";
    private static final String DENY_RULE_TYPE = "deny";
    private static final String ALL_OPERATIONS_VALUE = "All";
    private static final String WILDCARD_RESOURCE_NAME = "*";
    private static final String PREFIX_PATTERN_TYPE = "prefix";

    @Inject
    KubernetesResourceService k8sService;

    KafkaUserService() {
    }

    /**
     * List KafkaUsers, optionally filtered by namespace and Kafka cluster.
     *
     * @param namespace   the namespace, or null for all namespaces
     * @param clusterName the Kafka cluster name filter, or null for all clusters
     * @return list of user summary responses
     */
    public List<KafkaUserResponse> listUsers(final String namespace, final String clusterName) {
        String ns = InputUtils.normalizeInput(namespace);
        String cluster = InputUtils.normalizeInput(clusterName);

        LOG.infof("Listing KafkaUsers (namespace=%s, cluster=%s)",
            ns != null ? ns : "all", cluster != null ? cluster : "all");

        List<KafkaUser> users;
        if (cluster != null) {
            if (ns != null) {
                users = k8sService.queryResourcesByLabel(
                    KafkaUser.class, ns, ResourceLabels.STRIMZI_CLUSTER_LABEL, cluster);
            } else {
                users = k8sService.queryResourcesByLabelInAnyNamespace(
                    KafkaUser.class, ResourceLabels.STRIMZI_CLUSTER_LABEL, cluster);
            }
        } else {
            if (ns != null) {
                users = k8sService.queryResources(KafkaUser.class, ns);
            } else {
                users = k8sService.queryResourcesInAnyNamespace(KafkaUser.class);
            }
        }

        return users.stream()
            .map(this::createUserSummary)
            .toList();
    }

    /**
     * Get a specific KafkaUser by name.
     *
     * @param namespace the namespace, or null for auto-discovery
     * @param userName  the user name
     * @return the detailed user response
     */
    public KafkaUserResponse getUser(final String namespace, final String userName) {
        String ns = InputUtils.normalizeInput(namespace);
        String normalizedName = InputUtils.normalizeInput(userName);

        if (normalizedName == null) {
            throw McpErrors.invalidParams("User name is required");
        }
        InputUtils.validateK8sName(normalizedName, "user name");
        InputUtils.validateK8sName(ns, "namespace");

        LOG.infof("Getting KafkaUser name=%s (namespace=%s)", normalizedName, ns != null ? ns : "auto");

        KafkaUser user;
        if (ns != null) {
            user = k8sService.getResource(KafkaUser.class, ns, normalizedName);
        } else {
            user = findUserInAllNamespaces(normalizedName);
        }

        if (user == null) {
            throw McpErrors.notFound("KafkaUser", normalizedName, ns);
        }

        return createUserDetail(user);
    }

    /**
     * Build an ACL matrix (resource to principal to operations) aggregated across all
     * KafkaUsers labeled for a Kafka cluster, and flag over-broad allow grants.
     *
     * @param namespace    the namespace, or null for all namespaces
     * @param clusterName  the Kafka cluster name (required)
     * @param resourceType the ACL resource type to filter on (topic, group, transactionalId,
     *                     cluster); defaults to {@code topic} when null or blank
     * @return the ACL matrix response
     */
    public KafkaUserAclMatrixResponse getAclMatrix(final String namespace, final String clusterName,
                                                    final String resourceType) {
        String ns = InputUtils.normalizeInput(namespace);
        String cluster = InputUtils.normalizeInput(clusterName);

        if (cluster == null) {
            throw McpErrors.invalidParams("Cluster name is required");
        }
        InputUtils.validateK8sName(cluster, "cluster name");
        InputUtils.validateK8sName(ns, "namespace");

        String effectiveResourceType = resolveAclMatrixResourceType(resourceType);

        LOG.infof("Building KafkaUser ACL matrix (namespace=%s, cluster=%s, resourceType=%s)",
            ns != null ? ns : "all", cluster, effectiveResourceType);

        List<KafkaUser> users;
        if (ns != null) {
            users = k8sService.queryResourcesByLabel(
                KafkaUser.class, ns, ResourceLabels.STRIMZI_CLUSTER_LABEL, cluster);
        } else {
            users = k8sService.queryResourcesByLabelInAnyNamespace(
                KafkaUser.class, ResourceLabels.STRIMZI_CLUSTER_LABEL, cluster);
        }

        Map<String, Map<String, TreeSet<String>>> allowed = new TreeMap<>();
        Map<String, Map<String, TreeSet<String>>> denied = new TreeMap<>();
        List<KafkaUserAclMatrixResponse.BroadGrant> broadGrants = new ArrayList<>();

        for (KafkaUser user : users) {
            collectUserAcls(user, effectiveResourceType, allowed, denied, broadGrants);
        }

        Map<String, Map<String, List<String>>> matrix = toSortedListMatrix(allowed);
        Map<String, Map<String, List<String>>> deniedMatrix = denied.isEmpty() ? null : toSortedListMatrix(denied);

        return KafkaUserAclMatrixResponse.of(cluster, ns, effectiveResourceType, matrix, deniedMatrix, broadGrants);
    }

    private void collectUserAcls(final KafkaUser user, final String resourceType,
                                  final Map<String, Map<String, TreeSet<String>>> allowed,
                                  final Map<String, Map<String, TreeSet<String>>> denied,
                                  final List<KafkaUserAclMatrixResponse.BroadGrant> broadGrants) {
        List<KafkaUserResponse.AclRuleInfo> rules = extractAclRules(user);
        if (rules == null || rules.isEmpty()) {
            return;
        }

        String principal = extractUsername(user);
        if (principal == null) {
            principal = user.getMetadata().getName();
        }

        for (KafkaUserResponse.AclRuleInfo rule : rules) {
            if (!resourceType.equalsIgnoreCase(rule.resourceType())) {
                continue;
            }

            String resourceKey = aclMatrixResourceKey(rule);
            Map<String, Map<String, TreeSet<String>>> target =
                DENY_RULE_TYPE.equals(rule.type()) ? denied : allowed;
            target.computeIfAbsent(resourceKey, key -> new TreeMap<>())
                .computeIfAbsent(principal, key -> new TreeSet<>())
                .addAll(rule.operations() != null ? rule.operations() : List.of());

            if (ALLOW_RULE_TYPE.equals(rule.type())) {
                String reason = broadGrantReason(rule);
                if (reason != null) {
                    List<String> operations = rule.operations() != null
                        ? new TreeSet<>(rule.operations()).stream().toList() : List.of();
                    broadGrants.add(KafkaUserAclMatrixResponse.BroadGrant.of(
                        principal, resourceKey, operations, reason));
                }
            }
        }
    }

    private String aclMatrixResourceKey(final KafkaUserResponse.AclRuleInfo rule) {
        String resourceName = rule.resourceName();
        if (resourceName == null) {
            return CLUSTER_RESOURCE_MATRIX_KEY;
        }
        if (PREFIX_PATTERN_TYPE.equals(rule.patternType())) {
            return resourceName + WILDCARD_RESOURCE_NAME;
        }
        return resourceName;
    }

    private String broadGrantReason(final KafkaUserResponse.AclRuleInfo rule) {
        boolean wildcardResource = WILDCARD_RESOURCE_NAME.equals(rule.resourceName());
        boolean allOperations = rule.operations() != null && rule.operations().contains(ALL_OPERATIONS_VALUE);

        if (!wildcardResource && !allOperations) {
            return null;
        }
        if (wildcardResource && allOperations) {
            return "wildcard resource, All operations";
        }
        if (wildcardResource) {
            return "wildcard resource";
        }
        return "All operations";
    }

    private String resolveAclMatrixResourceType(final String resourceType) {
        if (resourceType == null || resourceType.isBlank()) {
            return DEFAULT_ACL_MATRIX_RESOURCE_TYPE;
        }
        String trimmed = resourceType.trim();
        for (String accepted : ACL_MATRIX_RESOURCE_TYPES) {
            if (accepted.toLowerCase(Locale.ROOT).equals(trimmed.toLowerCase(Locale.ROOT))) {
                return accepted;
            }
        }
        throw McpErrors.invalidParams("Invalid resourceType '" + trimmed
            + "': accepted values are " + String.join(", ", ACL_MATRIX_RESOURCE_TYPES));
    }

    private Map<String, Map<String, List<String>>> toSortedListMatrix(
            final Map<String, Map<String, TreeSet<String>>> source) {
        Map<String, Map<String, List<String>>> result = new TreeMap<>();
        for (Map.Entry<String, Map<String, TreeSet<String>>> resourceEntry : source.entrySet()) {
            Map<String, List<String>> byPrincipal = new TreeMap<>();
            for (Map.Entry<String, TreeSet<String>> principalEntry : resourceEntry.getValue().entrySet()) {
                byPrincipal.put(principalEntry.getKey(), List.copyOf(principalEntry.getValue()));
            }
            result.put(resourceEntry.getKey(), byPrincipal);
        }
        return result;
    }

    private KafkaUser findUserInAllNamespaces(final String userName) {
        List<KafkaUser> all = k8sService.queryResourcesInAnyNamespace(KafkaUser.class);
        List<KafkaUser> matching = all.stream()
            .filter(u -> userName.equals(u.getMetadata().getName()))
            .toList();

        if (matching.isEmpty()) {
            return null;
        }

        if (matching.size() > 1) {
            List<String> namespaces = matching.stream()
                .map(u -> u.getMetadata().getNamespace())
                .distinct()
                .toList();
            throw McpErrors.ambiguous("KafkaUser", userName, namespaces);
        }

        LOG.debugf("Discovered KafkaUser %s in namespace %s",
            userName, matching.getFirst().getMetadata().getNamespace());
        return matching.getFirst();
    }

    private KafkaUserResponse createUserSummary(final KafkaUser user) {
        ReconciliationInfo reconciliation = ReconciliationInfo.ofStatus(
            user.getMetadata().getGeneration(),
            user.getStatus() != null ? user.getStatus().getObservedGeneration() : 0L);

        return KafkaUserResponse.summary(
            user.getMetadata().getName(),
            user.getMetadata().getNamespace(),
            extractCluster(user),
            extractAuthenticationType(user),
            extractAuthorizationType(user),
            extractAclCount(user),
            extractUsername(user),
            extractSecretName(user),
            determineResourceStatus(user),
            extractConditions(user),
            reconciliation);
    }

    private KafkaUserResponse createUserDetail(final KafkaUser user) {
        ReconciliationInfo reconciliation = ReconciliationInfo.ofStatus(
            user.getMetadata().getGeneration(),
            user.getStatus() != null ? user.getStatus().getObservedGeneration() : 0L);

        return KafkaUserResponse.of(
            user.getMetadata().getName(),
            user.getMetadata().getNamespace(),
            extractCluster(user),
            extractAuthenticationType(user),
            extractAuthorizationType(user),
            extractAclCount(user),
            extractQuotas(user),
            extractAclRules(user),
            extractUsername(user),
            extractSecretName(user),
            determineResourceStatus(user),
            extractConditions(user),
            reconciliation);
    }

    private String extractCluster(final KafkaUser user) {
        Map<String, String> labels = user.getMetadata().getLabels();
        return labels != null ? labels.get(ResourceLabels.STRIMZI_CLUSTER_LABEL) : null;
    }

    private String extractAuthenticationType(final KafkaUser user) {
        if (user.getSpec() != null && user.getSpec().getAuthentication() != null) {
            return user.getSpec().getAuthentication().getType();
        }
        return null;
    }

    private String extractAuthorizationType(final KafkaUser user) {
        if (user.getSpec() != null && user.getSpec().getAuthorization() != null) {
            return user.getSpec().getAuthorization().getType();
        }
        return null;
    }

    private Integer extractAclCount(final KafkaUser user) {
        if (user.getSpec() == null || user.getSpec().getAuthorization() == null) {
            return null;
        }
        if (user.getSpec().getAuthorization() instanceof KafkaUserAuthorizationSimple simple) {
            List<AclRule> acls = simple.getAcls();
            return acls != null ? acls.size() : 0;
        }
        return null;
    }

    private List<KafkaUserResponse.AclRuleInfo> extractAclRules(final KafkaUser user) {
        if (user.getSpec() == null || user.getSpec().getAuthorization() == null) {
            return null;
        }
        if (!(user.getSpec().getAuthorization() instanceof KafkaUserAuthorizationSimple simple)) {
            return null;
        }

        List<AclRule> acls = simple.getAcls();
        if (acls == null || acls.isEmpty()) {
            return List.of();
        }

        return acls.stream()
            .map(this::mapAclRule)
            .toList();
    }

    private KafkaUserResponse.AclRuleInfo mapAclRule(final AclRule rule) {
        AclRuleResource resource = rule.getResource();
        String resourceType = resource != null ? resource.getType() : null;
        String resourceName = extractResourceName(resource);
        String patternType = extractPatternType(resource);
        String host = rule.getHost();
        String type = rule.getType() != null ? rule.getType().toValue() : "allow";

        List<String> operations = extractOperations(rule);

        return KafkaUserResponse.AclRuleInfo.of(type, resourceType, resourceName,
            patternType, host, operations);
    }

    private String extractResourceName(final AclRuleResource resource) {
        if (resource instanceof AclRuleTopicResource topic) {
            return topic.getName();
        } else if (resource instanceof AclRuleGroupResource group) {
            return group.getName();
        } else if (resource instanceof AclRuleTransactionalIdResource txn) {
            return txn.getName();
        }
        return null;
    }

    private String extractPatternType(final AclRuleResource resource) {
        AclResourcePatternType pt = null;
        if (resource instanceof AclRuleTopicResource topic) {
            pt = topic.getPatternType();
        } else if (resource instanceof AclRuleGroupResource group) {
            pt = group.getPatternType();
        } else if (resource instanceof AclRuleTransactionalIdResource txn) {
            pt = txn.getPatternType();
        }
        return pt != null ? pt.toValue() : null;
    }

    private List<String> extractOperations(final AclRule rule) {
        List<StrimziAclOperation> ops = rule.getOperations();
        if (ops != null && !ops.isEmpty()) {
            return ops.stream()
                .map(StrimziAclOperation::toValue)
                .toList();
        }
        return List.of();
    }

    private KafkaUserResponse.QuotaInfo extractQuotas(final KafkaUser user) {
        if (user.getSpec() == null || user.getSpec().getQuotas() == null) {
            return null;
        }
        KafkaUserQuotas quotas = user.getSpec().getQuotas();
        if (quotas.getProducerByteRate() == null && quotas.getConsumerByteRate() == null
            && quotas.getRequestPercentage() == null && quotas.getControllerMutationRate() == null) {
            return null;
        }
        return KafkaUserResponse.QuotaInfo.of(
            quotas.getProducerByteRate(),
            quotas.getConsumerByteRate(),
            quotas.getRequestPercentage(),
            quotas.getControllerMutationRate());
    }

    private String extractUsername(final KafkaUser user) {
        if (user.getStatus() != null) {
            return user.getStatus().getUsername();
        }
        return null;
    }

    private String extractSecretName(final KafkaUser user) {
        if (user.getStatus() != null) {
            return user.getStatus().getSecret();
        }
        return null;
    }

    private String determineResourceStatus(final KafkaUser user) {
        if (user.getStatus() == null || user.getStatus().getConditions() == null
            || user.getStatus().getConditions().isEmpty()) {
            return KubernetesConstants.ResourceStatus.UNKNOWN;
        }

        List<Condition> conditions = user.getStatus().getConditions();
        boolean ready = conditions.stream().anyMatch(c ->
            KubernetesConstants.Conditions.TYPE_READY.equals(c.getType())
                && KubernetesConstants.Conditions.STATUS_TRUE.equals(c.getStatus()));
        if (ready) {
            return KubernetesConstants.ResourceStatus.READY;
        }

        boolean hasError = conditions.stream().anyMatch(c ->
            KubernetesConstants.Conditions.TYPE_READY.equals(c.getType())
                && KubernetesConstants.Conditions.STATUS_FALSE.equals(c.getStatus()));
        return hasError ? KubernetesConstants.ResourceStatus.ERROR : KubernetesConstants.ResourceStatus.NOT_READY;
    }

    private List<ConditionInfo> extractConditions(final KafkaUser user) {
        if (user.getStatus() == null || user.getStatus().getConditions() == null
            || user.getStatus().getConditions().isEmpty()) {
            return null;
        }
        return user.getStatus().getConditions().stream()
            .map(c -> ConditionInfo.of(
                c.getType(), c.getStatus(), c.getReason(),
                c.getMessage(), c.getLastTransitionTime()))
            .toList();
    }
}
