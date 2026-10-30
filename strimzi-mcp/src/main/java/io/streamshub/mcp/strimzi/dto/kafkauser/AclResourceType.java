/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafkauser;

/**
 * Allowed values for the {@code get_kafka_user_acls_matrix} {@code resourceType} argument.
 *
 * <p>Rendered as a JSON-schema {@code enum} on the tool input. Each constant carries the exact
 * Strimzi {@code AclRuleResource} type spelling ({@code value()}) that the service matches against
 * case-insensitively, so the camelCase {@code transactionalId} is preserved rather than the
 * enum-name {@code TRANSACTIONAL_ID}.</p>
 */
public enum AclResourceType {

    /** Topic ACL resources. */
    TOPIC("topic"),
    /** Consumer-group ACL resources. */
    GROUP("group"),
    /** Transactional-id ACL resources. */
    TRANSACTIONAL_ID("transactionalId"),
    /** Cluster-level ACL resources. */
    CLUSTER("cluster");

    private final String value;

    AclResourceType(final String value) {
        this.value = value;
    }

    /**
     * The Strimzi {@code AclRuleResource} type spelling for this resource type.
     *
     * @return the Strimzi resource-type value (e.g. {@code transactionalId})
     */
    public String value() {
        return value;
    }
}
