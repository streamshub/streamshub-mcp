/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.util;

import io.fabric8.kubernetes.api.model.EnvFromSource;
import io.fabric8.kubernetes.api.model.EnvVarSource;
import io.streamshub.mcp.common.config.KubernetesConstants;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

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

    /**
     * Describe the {@code envFrom} sources that could supply a variable, without resolving them.
     * A source with a prefix can only supply variables whose name starts with that prefix.
     *
     * @param sources the container's {@code envFrom} sources (may be null)
     * @param varName the variable name
     * @return a comma-separated reference such as {@code configMapRef:name, secretRef:name},
     *         or null when no source could supply the variable; a source with neither a
     *         ConfigMap nor a Secret reference supplies nothing and is skipped
     */
    public static String describeEnvFrom(final List<EnvFromSource> sources, final String varName) {
        if (sources == null) {
            return null;
        }
        String described = sources.stream()
            .filter(s -> s.getPrefix() == null || varName.startsWith(s.getPrefix()))
            .map(EnvVarUtils::describeEnvFromSource)
            .filter(Objects::nonNull)
            .collect(Collectors.joining(", "));
        return described.isEmpty() ? null : described;
    }

    private static String describeEnvFromSource(final EnvFromSource source) {
        String ref;
        if (source.getConfigMapRef() != null) {
            ref = "configMapRef:" + source.getConfigMapRef().getName();
        } else if (source.getSecretRef() != null) {
            ref = "secretRef:" + source.getSecretRef().getName();
        } else {
            // A source with neither reference supplies nothing, so it is skipped rather than
            // rendered as a confusing "unknown" entry in the joined list.
            return null;
        }
        if (source.getPrefix() != null && !source.getPrefix().isEmpty()) {
            ref += " (prefix " + source.getPrefix() + ")";
        }
        return ref;
    }
}
