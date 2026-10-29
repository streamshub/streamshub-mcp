/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafkauser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests that {@link AclResourceType} maps each constant to the exact Strimzi
 * {@code AclRuleResource} type spelling the service matches against.
 */
class AclResourceTypeTest {

    AclResourceTypeTest() {
    }

    @Test
    void testTransactionalIdValueUsesStrimziCamelCaseSpelling() {
        // The service matches via equalsIgnoreCase against Strimzi's "transactionalId";
        // a plain name() of "TRANSACTIONAL_ID" would not match, so value() must carry the spelling.
        assertEquals("transactionalId", AclResourceType.TRANSACTIONAL_ID.value());
    }

    @Test
    void testAllValuesMatchStrimziAclResourceTypes() {
        assertEquals("topic", AclResourceType.TOPIC.value());
        assertEquals("group", AclResourceType.GROUP.value());
        assertEquals("cluster", AclResourceType.CLUSTER.value());
        assertEquals("transactionalId", AclResourceType.TRANSACTIONAL_ID.value());
    }
}
