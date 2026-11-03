/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.common.dto.metrics.AggregationLevel;
import io.streamshub.mcp.strimzi.dto.kafkatopic.TopicStatusFilter;
import io.streamshub.mcp.strimzi.dto.kafkauser.AclResourceType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Guards {@code quarkus.jackson.accept-case-insensitive-enums=true}.
 *
 * <p>Enum-typed {@code @ToolArg} parameters ({@link AggregationLevel}, {@link AclResourceType},
 * {@link TopicStatusFilter}) are coerced from the incoming JSON string by the MCP framework via the
 * default Quarkus {@link ObjectMapper} ({@code FeatureManagerBase#handleParam} calls
 * {@code mapper.convertValue(value, type)}) before the tool method runs. Jackson matches enum
 * constant names case-sensitively by default, so without the property a client sending {@code "broker"}
 * would be rejected before the tool executes -- a regression from when these arguments were plain
 * strings normalised with {@code toUpperCase}. This test injects the same CDI {@link ObjectMapper}
 * the framework uses and asserts case-insensitive conversion, so removing the property fails here.</p>
 */
@QuarkusTest
class EnumToolArgCaseInsensitivityTest {

    @Inject
    ObjectMapper mapper;

    EnumToolArgCaseInsensitivityTest() {
    }

    @Test
    void aggregationLevelAcceptsAnyCase() {
        assertEquals(AggregationLevel.BROKER, mapper.convertValue("broker", AggregationLevel.class));
        assertEquals(AggregationLevel.PARTITION, mapper.convertValue("partition", AggregationLevel.class));
        assertEquals(AggregationLevel.CLUSTER, mapper.convertValue("Cluster", AggregationLevel.class));
        // The canonical uppercase name still works.
        assertEquals(AggregationLevel.TOPIC, mapper.convertValue("TOPIC", AggregationLevel.class));
    }

    @Test
    void aclResourceTypeAcceptsAnyCase() {
        assertEquals(AclResourceType.TOPIC, mapper.convertValue("topic", AclResourceType.class));
        assertEquals(AclResourceType.GROUP, mapper.convertValue("group", AclResourceType.class));
        assertEquals(AclResourceType.CLUSTER, mapper.convertValue("cluster", AclResourceType.class));
        // Case-insensitive matching is on the enum NAME; transactional-id must be the underscore form.
        assertEquals(AclResourceType.TRANSACTIONAL_ID,
            mapper.convertValue("transactional_id", AclResourceType.class));
    }

    @Test
    void topicStatusFilterAcceptsAnyCase() {
        assertEquals(TopicStatusFilter.READY, mapper.convertValue("ready", TopicStatusFilter.class));
        assertEquals(TopicStatusFilter.NOT_READY, mapper.convertValue("not_ready", TopicStatusFilter.class));
        assertEquals(TopicStatusFilter.UNMANAGED, mapper.convertValue("Unmanaged", TopicStatusFilter.class));
    }

    @Test
    void valuesOutsideTheEnumAreStillRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> mapper.convertValue("not-a-level", AggregationLevel.class));
    }
}
