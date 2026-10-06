/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkiverse.mcp.server.McpException;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.strimzi.dto.kafkauser.KafkaUserAclMatrixResponse;
import io.streamshub.mcp.strimzi.dto.kafkauser.KafkaUserResponse;
import io.streamshub.mcp.strimzi.service.kafkauser.KafkaUserService;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.user.KafkaUser;
import io.strimzi.api.kafka.model.user.KafkaUserBuilder;
import io.strimzi.api.kafka.model.user.acl.AclResourcePatternType;
import io.strimzi.api.kafka.model.user.acl.AclRuleType;
import io.strimzi.api.kafka.model.user.acl.StrimziAclOperation;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
/**
 * Service-level tests for {@link KafkaUserService} with mocked Kubernetes.
 */
@QuarkusTest
class KafkaUserServiceTest {

    @InjectMock
    KubernetesClient kubernetesClient;

    @Inject
    KafkaUserService userService;

    KafkaUserServiceTest() {
    }

    @BeforeEach
    void setUp() {
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, KafkaUser.class);
    }

    /**
     * Verify list returns empty when no KafkaUsers exist.
     */
    @Test
    void testListUsersReturnsEmptyWhenNoneExist() {
        List<KafkaUserResponse> result = userService.listUsers("kafka", null);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    /**
     * Verify list returns empty when filtering by cluster and none match.
     */
    @Test
    void testListUsersFiltersByCluster() {
        List<KafkaUserResponse> result = userService.listUsers("kafka", "my-cluster");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    /**
     * Verify list returns empty when searching all namespaces without cluster filter.
     */
    @Test
    void testListUsersAllNamespacesNoCLuster() {
        List<KafkaUserResponse> result = userService.listUsers(null, null);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    /**
     * Verify list returns empty when searching all namespaces with cluster filter.
     */
    @Test
    void testListUsersAllNamespacesWithCluster() {
        List<KafkaUserResponse> result = userService.listUsers(null, "my-cluster");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    /**
     * Verify get throws when user name is null.
     */
    @Test
    void testGetUserThrowsWhenNameIsNull() {
        McpException ex = assertThrows(McpException.class,
            () -> userService.getUser("kafka", null));

        assertTrue(ex.getMessage().contains("required"));
    }

    /**
     * Verify get throws when user is not found in a specific namespace.
     */
    @Test
    void testGetUserThrowsWhenNotFoundInNamespace() {
        McpException ex = assertThrows(McpException.class,
            () -> userService.getUser("kafka", "nonexistent"));

        assertTrue(ex.getMessage().contains("not found"));
        assertTrue(ex.getMessage().contains("namespace"));
    }

    /**
     * Verify get throws when user is not found in any namespace.
     */
    @Test
    void testGetUserThrowsWhenNotFoundInAnyNamespace() {
        McpException ex = assertThrows(McpException.class,
            () -> userService.getUser(null, "nonexistent"));

        assertTrue(ex.getMessage().contains("not found"));
        assertTrue(ex.getMessage().contains("any namespace"));
    }

    /**
     * Verify the ACL matrix aggregates multiple principals onto one shared topic resource.
     */
    @Test
    void testAclMatrixAggregatesMultiplePrincipalsOntoOneTopic() {
        KafkaUser alice = topicAclUser("alice", "kafka", "my-cluster",
            "orders", AclResourcePatternType.LITERAL, AclRuleType.ALLOW, StrimziAclOperation.READ);
        KafkaUser bob = topicAclUser("bob", "kafka", "my-cluster",
            "orders", AclResourcePatternType.LITERAL, AclRuleType.ALLOW, StrimziAclOperation.WRITE);
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, KafkaUser.class, List.of(alice, bob));

        KafkaUserAclMatrixResponse response = userService.getAclMatrix("kafka", "my-cluster", "topic");

        Map<String, List<String>> orders = response.matrix().get("orders");
        assertNotNull(orders, "matrix should have an 'orders' entry");
        assertEquals(List.of("Read"), orders.get("alice"));
        assertEquals(List.of("Write"), orders.get("bob"));
        assertEquals(2, response.principalCount());
        assertEquals(1, response.resourceCount());
    }

    /**
     * Verify a prefix-pattern ACL rule keys the matrix entry with a trailing wildcard.
     */
    @Test
    void testAclMatrixPrefixPatternKeyRendersWithWildcard() {
        KafkaUser user = topicAclUser("alice", "kafka", "my-cluster",
            "orders-", AclResourcePatternType.PREFIX, AclRuleType.ALLOW, StrimziAclOperation.READ);
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, KafkaUser.class, List.of(user));

        KafkaUserAclMatrixResponse response = userService.getAclMatrix("kafka", "my-cluster", "topic");

        assertTrue(response.matrix().containsKey("orders-*"), "prefix rule should key as 'orders-*'");
    }

    /**
     * Verify deny rules land only in {@code denied}, never in {@code matrix}.
     */
    @Test
    void testAclMatrixDenyRulesLandOnlyInDenied() {
        KafkaUser user = topicAclUser("alice", "kafka", "my-cluster",
            "secret-topic", AclResourcePatternType.LITERAL, AclRuleType.DENY, StrimziAclOperation.READ);
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, KafkaUser.class, List.of(user));

        KafkaUserAclMatrixResponse response = userService.getAclMatrix("kafka", "my-cluster", "topic");

        assertTrue(response.matrix().isEmpty(), "deny-only rules must not appear in matrix");
        assertNotNull(response.denied(), "denied map should be present");
        assertEquals(List.of("Read"), response.denied().get("secret-topic").get("alice"));
    }

    /**
     * Verify a wildcard resource and an {@code All}-operations grant both produce broad_grants entries.
     */
    @Test
    void testAclMatrixFlagsBroadGrants() {
        KafkaUser wildcardUser = topicAclUser("alice", "kafka", "my-cluster",
            "*", AclResourcePatternType.LITERAL, AclRuleType.ALLOW, StrimziAclOperation.READ);
        KafkaUser allOpsUser = topicAclUser("bob", "kafka", "my-cluster",
            "orders", AclResourcePatternType.LITERAL, AclRuleType.ALLOW, StrimziAclOperation.ALL);
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, KafkaUser.class,
            List.of(wildcardUser, allOpsUser));

        KafkaUserAclMatrixResponse response = userService.getAclMatrix("kafka", "my-cluster", "topic");

        assertEquals(2, response.broadGrants().size());
        boolean hasWildcardReason = response.broadGrants().stream()
            .anyMatch(g -> "alice".equals(g.principal()) && "wildcard resource".equals(g.reason()));
        boolean hasAllOpsReason = response.broadGrants().stream()
            .anyMatch(g -> "bob".equals(g.principal()) && "All operations".equals(g.reason()));
        assertTrue(hasWildcardReason, "wildcard resource grant should be flagged");
        assertTrue(hasAllOpsReason, "All-operations grant should be flagged");
    }

    /**
     * Verify a bad {@code resourceType} throws {@link McpException}.
     */
    @Test
    void testAclMatrixThrowsForInvalidResourceType() {
        McpException ex = assertThrows(McpException.class,
            () -> userService.getAclMatrix("kafka", "my-cluster", "bogus"));

        assertTrue(ex.getMessage().contains("bogus"));
    }

    /**
     * Verify a cluster with no users returns empty maps and zero counts.
     */
    @Test
    void testAclMatrixReturnsEmptyForClusterWithNoUsers() {
        KafkaUserAclMatrixResponse response = userService.getAclMatrix("kafka", "my-cluster", null);

        assertTrue(response.matrix().isEmpty());
        assertNull(response.denied(), "denied should be null (not empty map) when there are no deny rules");
        assertTrue(response.broadGrants().isEmpty());
        assertEquals(0, response.resourceCount());
        assertEquals(0, response.principalCount());
        assertEquals("topic", response.resourceType(), "resourceType should default to 'topic'");
    }

    /**
     * Verify cluster-scoped ACL rules, which carry no resource name, key under a stable placeholder
     * rather than a null map key.
     */
    @Test
    void testAclMatrixKeysNamelessClusterResource() {
        KafkaUser admin = new KafkaUserBuilder()
            .withNewMetadata()
                .withName("admin")
                .withNamespace("kafka")
                .withLabels(Map.of(ResourceLabels.STRIMZI_CLUSTER_LABEL, "my-cluster"))
            .endMetadata()
            .withNewSpec()
                .withNewKafkaUserAuthorizationSimple()
                    .addNewAcl()
                        .withType(AclRuleType.ALLOW)
                        .withNewAclRuleClusterResource()
                        .endAclRuleClusterResource()
                        .withOperations(StrimziAclOperation.DESCRIBE)
                    .endAcl()
                .endKafkaUserAuthorizationSimple()
            .endSpec()
            .withNewStatus()
                .withUsername("admin")
            .endStatus()
            .build();
        KubernetesMockHelper.setupResourceQuery(kubernetesClient, KafkaUser.class, List.of(admin));

        KafkaUserAclMatrixResponse response = userService.getAclMatrix("kafka", "my-cluster", "cluster");

        assertEquals(List.of("kafka-cluster"), List.copyOf(response.matrix().keySet()));
        assertEquals(List.of("Describe"), response.matrix().get("kafka-cluster").get("admin"));
    }

    private static KafkaUser topicAclUser(final String name, final String namespace, final String cluster,
                                           final String topicName, final AclResourcePatternType patternType,
                                           final AclRuleType ruleType, final StrimziAclOperation operation) {
        return new KafkaUserBuilder()
            .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .withLabels(Map.of(ResourceLabels.STRIMZI_CLUSTER_LABEL, cluster))
            .endMetadata()
            .withNewSpec()
                .withNewKafkaUserAuthorizationSimple()
                    .addNewAcl()
                        .withType(ruleType)
                        .withNewAclRuleTopicResource()
                            .withName(topicName)
                            .withPatternType(patternType)
                        .endAclRuleTopicResource()
                        .withOperations(operation)
                    .endAcl()
                .endKafkaUserAuthorizationSimple()
            .endSpec()
            .withNewStatus()
                .withUsername(name)
            .endStatus()
            .build();
    }
}
