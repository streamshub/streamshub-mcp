/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafkaconnect;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.streamshub.mcp.common.dto.ImageBuildStatus;

import java.util.List;

/**
 * Build information for a KafkaConnect resource.
 *
 * @param outputImage the output image name
 * @param outputType the output type (e.g., "docker", "imagestream")
 * @param pushSecret the push secret for docker output
 * @param buildMechanism the build mechanism (e.g., "KubernetesPod", "OpenShiftBuild")
 * @param plugins the list of plugins
 * @param builder the builder status
 * @param buildLogs the build logs
 * @param message a message about the build state
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaConnectBuildInfo(
    @JsonProperty("output_image") String outputImage,
    @JsonProperty("output_type") String outputType,
    @JsonProperty("push_secret") String pushSecret,
    @JsonProperty("build_mechanism") String buildMechanism,
    @JsonProperty("plugins") List<PluginInfo> plugins,
    @JsonProperty("builder") ImageBuildStatus builder,
    @JsonProperty("build_logs") String buildLogs,
    @JsonProperty("message") String message
) {

    /** Build mechanism: an OpenShift {@code build.openshift.io} Build. */
    public static final String MECHANISM_OPENSHIFT_BUILD = "OpenShiftBuild";
    /** Build mechanism: a Kubernetes builder pod (Buildah/Kaniko). */
    public static final String MECHANISM_KUBERNETES_POD = "KubernetesPod";

    /**
     * Creates build information for a KafkaConnect resource.
     *
     * @param outputImage the output image name
     * @param outputType the output type (e.g., "docker", "imagestream")
     * @param pushSecret the push secret for docker output
     * @param buildMechanism the build mechanism (e.g., "KubernetesPod", "OpenShiftBuild")
     * @param plugins the list of plugins
     * @param builder the builder status
     * @param buildLogs the build logs
     * @param message a message about the build state
     * @return the build information
     */
    public static KafkaConnectBuildInfo of(final String outputImage, final String outputType,
                                           final String pushSecret, final String buildMechanism,
                                           final List<PluginInfo> plugins, final ImageBuildStatus builder,
                                           final String buildLogs, final String message) {
        return new KafkaConnectBuildInfo(outputImage, outputType, pushSecret, buildMechanism,
            plugins, builder, buildLogs, message);
    }

    /**
     * Plugin information.
     *
     * @param name the plugin name
     * @param artifacts the list of artifacts
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PluginInfo(
        @JsonProperty("name") String name,
        @JsonProperty("artifacts") List<ArtifactInfo> artifacts
    ) {

        /**
         * Creates plugin information.
         *
         * @param name the plugin name
         * @param artifacts the list of artifacts
         * @return the plugin information
         */
        public static PluginInfo of(final String name, final List<ArtifactInfo> artifacts) {
            return new PluginInfo(name, artifacts);
        }
    }

    /**
     * Artifact information.
     *
     * @param type the artifact type (e.g., "jar", "mvn")
     * @param source the artifact source (URL or Maven coordinates)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ArtifactInfo(
        @JsonProperty("type") String type,
        @JsonProperty("source") String source
    ) {

        /**
         * Creates artifact information.
         *
         * @param type the artifact type (e.g., "jar", "mvn")
         * @param source the artifact source (URL or Maven coordinates)
         * @return the artifact information
         */
        public static ArtifactInfo of(final String type, final String source) {
            return new ArtifactInfo(type, source);
        }
    }
}
