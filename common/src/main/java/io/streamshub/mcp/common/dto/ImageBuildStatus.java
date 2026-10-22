/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Status of a container-image build, as surfaced by either a Kubernetes builder
 * pod (Buildah/Kaniko) or an OpenShift {@code Build}. This is a generic
 * Kubernetes/OpenShift concept and carries no Strimzi-specific detail.
 *
 * @param kind the resource kind that produced the status ({@code "Pod"} or {@code "Build"})
 * @param name the resource name
 * @param phase the build phase
 * @param ready whether the builder is ready (container-level; {@code null} for OpenShift Builds)
 * @param reason the reason for the current state (for example {@code ImagePullBackOff})
 * @param message a human-readable message about the builder state
 * @param builderImage the builder image, when known (builder pod only)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImageBuildStatus(
    @JsonProperty("kind") String kind,
    @JsonProperty("name") String name,
    @JsonProperty("phase") String phase,
    @JsonProperty("ready") Boolean ready,
    @JsonProperty("reason") String reason,
    @JsonProperty("message") String message,
    @JsonProperty("builder_image") String builderImage
) {

    /** Resource kind for a Kubernetes builder pod. */
    public static final String KIND_POD = "Pod";
    /** Resource kind for an OpenShift Build. */
    public static final String KIND_BUILD = "Build";

    /**
     * Creates a status for a Kubernetes builder pod (Buildah/Kaniko).
     *
     * @param name the builder pod name
     * @param phase the pod phase
     * @param ready the container-level ready flag
     * @param reason the container state reason (for example {@code ImagePullBackOff})
     * @param message a human-readable message about the builder state
     * @param builderImage the builder image
     * @return the builder pod status
     */
    public static ImageBuildStatus ofPod(final String name, final String phase, final Boolean ready,
                                         final String reason, final String message, final String builderImage) {
        return new ImageBuildStatus(KIND_POD, name, phase, ready, reason, message, builderImage);
    }

    /**
     * Creates a status for an OpenShift {@code Build}. OpenShift Builds expose
     * neither a container-level ready flag nor a builder image, so both are
     * {@code null}.
     *
     * @param name the Build name
     * @param phase the Build phase
     * @param reason the Build reason
     * @param message a human-readable message about the Build state
     * @return the Build status
     */
    public static ImageBuildStatus ofBuild(final String name, final String phase,
                                           final String reason, final String message) {
        return new ImageBuildStatus(KIND_BUILD, name, phase, null, reason, message, null);
    }
}
