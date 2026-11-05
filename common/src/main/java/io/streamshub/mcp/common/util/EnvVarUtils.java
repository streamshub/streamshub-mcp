/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.util;

import io.fabric8.kubernetes.api.model.EnvVarSource;
import io.streamshub.mcp.common.config.KubernetesConstants;

/**
 * Helpers for container environment variables.
 */
public final class EnvVarUtils {

    private EnvVarUtils() {
    }

    /**
     * Describe the source of an environment variable set via {@code valueFrom}, without resolving
     * its value.
     *
     * @param source the {@code valueFrom} source, must not be null
     * @return a descriptive reference such as {@code secretKeyRef:name/key}, or
     *         {@link KubernetesConstants#UNKNOWN} when the source type is not recognised
     */
    public static String describeSource(final EnvVarSource source) {
        if (source.getFieldRef() != null) {
            return "fieldRef:" + source.getFieldRef().getFieldPath();
        } else if (source.getSecretKeyRef() != null) {
            return "secretKeyRef:" + source.getSecretKeyRef().getName()
                + "/" + source.getSecretKeyRef().getKey();
        } else if (source.getConfigMapKeyRef() != null) {
            return "configMapKeyRef:" + source.getConfigMapKeyRef().getName()
                + "/" + source.getConfigMapKeyRef().getKey();
        } else if (source.getResourceFieldRef() != null) {
            return "resourceFieldRef:" + source.getResourceFieldRef().getResource();
        }
        return KubernetesConstants.UNKNOWN;
    }
}
