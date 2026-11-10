/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.util;

import io.fabric8.kubernetes.api.model.EnvFromSource;
import io.fabric8.kubernetes.api.model.EnvFromSourceBuilder;
import io.fabric8.kubernetes.api.model.EnvVarSource;
import io.fabric8.kubernetes.api.model.EnvVarSourceBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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

    private static EnvFromSource configMap(final String name, final String prefix) {
        return new EnvFromSourceBuilder().withPrefix(prefix).withNewConfigMapRef(name, false).build();
    }

    private static EnvFromSource secret(final String name) {
        return new EnvFromSourceBuilder().withNewSecretRef(name, false).build();
    }

    @Test
    void testDescribeEnvFromWithoutSourcesIsNull() {
        assertNull(EnvVarUtils.describeEnvFrom(null, "VAR"));
        assertNull(EnvVarUtils.describeEnvFrom(List.of(), "VAR"));
    }

    @Test
    void testDescribeEnvFromListsConfigMapsAndSecrets() {
        assertEquals("configMapRef:a, secretRef:b",
            EnvVarUtils.describeEnvFrom(List.of(configMap("a", null), secret("b")), "VAR"));
    }

    @Test
    void testDescribeEnvFromIncludesMatchingPrefix() {
        assertEquals("configMapRef:a (prefix X_)",
            EnvVarUtils.describeEnvFrom(List.of(configMap("a", "X_")), "X_VAR"));
    }

    @Test
    void testDescribeEnvFromSkipsNonMatchingPrefix() {
        assertNull(EnvVarUtils.describeEnvFrom(List.of(configMap("a", "OTHER_")), "VAR"));
    }

    @Test
    void testDescribeEnvFromSkipsSourceWithNoReference() {
        assertNull(EnvVarUtils.describeEnvFrom(List.of(new EnvFromSource()), "VAR"));
    }

    @Test
    void testDescribeEnvFromSkipsReferencelessSourceInMixedList() {
        assertEquals("configMapRef:a",
            EnvVarUtils.describeEnvFrom(List.of(new EnvFromSource(), configMap("a", null)), "VAR"));
    }
}
