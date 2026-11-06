/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.util;

import io.fabric8.kubernetes.api.model.EnvVarSource;
import io.fabric8.kubernetes.api.model.EnvVarSourceBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link EnvVarUtils}.
 */
class EnvVarUtilsTest {

    EnvVarUtilsTest() {
    }

    @Test
    void testDescribesSecretKeyRef() {
        EnvVarSource source = new EnvVarSourceBuilder()
            .withNewSecretKeyRef("key", "my-secret", false).build();
        assertEquals("secretKeyRef:my-secret/key", EnvVarUtils.describeSource(source));
    }

    @Test
    void testDescribesConfigMapKeyRef() {
        EnvVarSource source = new EnvVarSourceBuilder()
            .withNewConfigMapKeyRef("key", "my-config", false).build();
        assertEquals("configMapKeyRef:my-config/key", EnvVarUtils.describeSource(source));
    }

    @Test
    void testDescribesFieldRef() {
        EnvVarSource source = new EnvVarSourceBuilder()
            .withNewFieldRef("v1", "metadata.name").build();
        assertEquals("fieldRef:metadata.name", EnvVarUtils.describeSource(source));
    }

    @Test
    void testDescribesResourceFieldRef() {
        EnvVarSource source = new EnvVarSourceBuilder()
            .withNewResourceFieldRef().withResource("limits.cpu").endResourceFieldRef().build();
        assertEquals("resourceFieldRef:limits.cpu", EnvVarUtils.describeSource(source));
    }

    @Test
    void testUnrecognisedSourceIsUnknown() {
        assertEquals("unknown", EnvVarUtils.describeSource(new EnvVarSource()));
    }
}
