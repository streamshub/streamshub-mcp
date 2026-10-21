/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.dto;

/**
 * Result of inspecting a container-image build: the builder status plus an
 * optional log excerpt that is populated only on the failure path.
 * <p>
 * This is an internal carrier passed between services and unpacked by callers
 * into their own domain response; it is never serialized to the client, so it
 * carries no Jackson annotations (matching {@link PodLogsResult} and the other
 * carrier records in this package).
 *
 * @param status the builder status
 * @param logs a build-log excerpt, or {@code null} when the build is healthy
 */
public record ImageBuildInspection(ImageBuildStatus status, String logs) {

    /**
     * Creates an inspection result.
     *
     * @param status the builder status
     * @param logs a build-log excerpt, or {@code null} when the build is healthy
     * @return the inspection result
     */
    public static ImageBuildInspection of(final ImageBuildStatus status, final String logs) {
        return new ImageBuildInspection(status, logs);
    }
}
