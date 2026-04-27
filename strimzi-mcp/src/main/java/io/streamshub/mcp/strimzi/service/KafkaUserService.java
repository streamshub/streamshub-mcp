/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import io.quarkiverse.mcp.server.ToolCallException;
import io.streamshub.mcp.common.config.KubernetesConstants;
import io.streamshub.mcp.common.dto.ConditionInfo;
import io.streamshub.mcp.common.service.KubernetesResourceService;
import io.streamshub.mcp.common.util.InputUtils;
import io.streamshub.mcp.strimzi.dto.KafkaUserResponse;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.common.Condition;
import io.strimzi.api.kafka.model.user.KafkaUser;
import io.strimzi.api.kafka.model.user.KafkaUserAuthorizationSimple;
import io.strimzi.api.kafka.model.user.KafkaUserQuotas;
import io.strimzi.api.kafka.model.user.acl.AclOperation;
import io.strimzi.api.kafka.model.user.acl.AclResourcePatternType;
import io.strimzi.api.kafka.model.user.acl.AclRule;
import io.strimzi.api.kafka.model.user.acl.AclRuleGroupResource;
import io.strimzi.api.kafka.model.user.acl.AclRuleResource;
import io.strimzi.api.kafka.model.user.acl.AclRuleTopicResource;
import io.strimzi.api.kafka.model.user.acl.AclRuleTransactionalIdResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for KafkaUser operations.
 */
@ApplicationScoped
public class KafkaUserService {

    private static final Logger LOG = Logger.getLogger(KafkaUserService.class);

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
            throw new ToolCallException("User name is required");
        }

        LOG.infof("Getting KafkaUser name=%s (namespace=%s)", normalizedName, ns != null ? ns : "auto");

        KafkaUser user;
        if (ns != null) {
            user = k8sService.getResource(KafkaUser.class, ns, normalizedName);
        } else {
            user = findUserInAllNamespaces(normalizedName);
        }

        if (user == null) {
            if (ns != null) {
                throw new ToolCallException(
                    "KafkaUser '" + normalizedName + "' not found in namespace " + ns);
            } else {
                throw new ToolCallException(
                    "KafkaUser '" + normalizedName + "' not found in any namespace");
            }
        }

        return createUserDetail(user);
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
            String namespaces = matching.stream()
                .map(u -> u.getMetadata().getNamespace())
                .distinct()
                .collect(Collectors.joining(", "));
            throw new ToolCallException("Multiple KafkaUsers named '" + userName
                + "' found in namespaces: " + namespaces + ". Please specify namespace.");
        }

        LOG.debugf("Discovered KafkaUser %s in namespace %s",
            userName, matching.getFirst().getMetadata().getNamespace());
        return matching.getFirst();
    }

    private KafkaUserResponse createUserSummary(final KafkaUser user) {
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
            extractConditions(user));
    }

    private KafkaUserResponse createUserDetail(final KafkaUser user) {
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
            extractConditions(user));
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

    @SuppressWarnings("deprecation")
    private List<String> extractOperations(final AclRule rule) {
        List<AclOperation> ops = rule.getOperations();
        if (ops != null && !ops.isEmpty()) {
            return ops.stream()
                .map(AclOperation::toValue)
                .toList();
        }
        AclOperation singleOp = rule.getOperation();
        if (singleOp != null) {
            return List.of(singleOp.toValue());
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
