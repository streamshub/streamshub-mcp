/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest.templates.strimzi;

import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.user.KafkaUserBuilder;

import java.util.Map;

/**
 * Template builders for KafkaUser custom resources.
 */
public final class KafkaUserTemplates {

    /** Default user name for SCRAM-SHA-512 test user. */
    public static final String SCRAM_USER_NAME = "mcp-scram-user";

    /** Default user name for TLS test user. */
    public static final String TLS_USER_NAME = "mcp-tls-user";

    private KafkaUserTemplates() {
    }

    /**
     * Create a SCRAM-SHA-512 KafkaUser with topic ACLs and quotas.
     *
     * @param namespace   the namespace
     * @param userName    the user name
     * @param clusterName the Kafka cluster name
     * @return a pre-configured KafkaUserBuilder
     */
    public static KafkaUserBuilder scramUserWithAcls(final String namespace,
                                                      final String userName,
                                                      final String clusterName) {
        return new KafkaUserBuilder()
            .withNewMetadata()
                .withName(userName)
                .withNamespace(namespace)
                .withLabels(Map.of(ResourceLabels.STRIMZI_CLUSTER_LABEL, clusterName))
            .endMetadata()
            .withNewSpec()
                .withNewKafkaUserScramSha512ClientAuthentication()
                .endKafkaUserScramSha512ClientAuthentication()
                .withNewKafkaUserAuthorizationSimple()
                    .addNewAcl()
                        .withNewAclRuleTopicResource()
                            .withName("test-")
                            .withPatternType(io.strimzi.api.kafka.model.user.acl.AclResourcePatternType.PREFIX)
                        .endAclRuleTopicResource()
                        .withOperations(
                            io.strimzi.api.kafka.model.user.acl.AclOperation.READ,
                            io.strimzi.api.kafka.model.user.acl.AclOperation.WRITE,
                            io.strimzi.api.kafka.model.user.acl.AclOperation.DESCRIBE)
                    .endAcl()
                    .addNewAcl()
                        .withNewAclRuleGroupResource()
                            .withName("test-group")
                        .endAclRuleGroupResource()
                        .withOperations(io.strimzi.api.kafka.model.user.acl.AclOperation.READ)
                    .endAcl()
                .endKafkaUserAuthorizationSimple()
                .withNewQuotas()
                    .withProducerByteRate(1048576)
                    .withConsumerByteRate(2097152)
                    .withRequestPercentage(55)
                .endQuotas()
            .endSpec();
    }

    /**
     * Create a TLS KafkaUser with minimal ACLs and no quotas.
     *
     * @param namespace   the namespace
     * @param userName    the user name
     * @param clusterName the Kafka cluster name
     * @return a pre-configured KafkaUserBuilder
     */
    public static KafkaUserBuilder tlsUser(final String namespace,
                                            final String userName,
                                            final String clusterName) {
        return new KafkaUserBuilder()
            .withNewMetadata()
                .withName(userName)
                .withNamespace(namespace)
                .withLabels(Map.of(ResourceLabels.STRIMZI_CLUSTER_LABEL, clusterName))
            .endMetadata()
            .withNewSpec()
                .withNewKafkaUserTlsClientAuthentication()
                .endKafkaUserTlsClientAuthentication()
                .withNewKafkaUserAuthorizationSimple()
                    .addNewAcl()
                        .withNewAclRuleTopicResource()
                            .withName("*")
                        .endAclRuleTopicResource()
                        .withOperations(io.strimzi.api.kafka.model.user.acl.AclOperation.READ)
                    .endAcl()
                .endKafkaUserAuthorizationSimple()
            .endSpec();
    }
}
