/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafkaconnect;

import io.streamshub.mcp.common.dto.ImageBuildInspection;
import io.streamshub.mcp.common.dto.ImageBuildStatus;
import io.streamshub.mcp.common.service.ImageBuildInspectionService;
import io.streamshub.mcp.common.service.OpenShiftDetector;
import io.streamshub.mcp.common.util.ExceptionUtils;
import io.streamshub.mcp.strimzi.dto.kafkaconnect.KafkaConnectBuildInfo;
import io.streamshub.mcp.strimzi.dto.kafkaconnect.KafkaConnectBuildInfo.ArtifactInfo;
import io.streamshub.mcp.strimzi.dto.kafkaconnect.KafkaConnectBuildInfo.PluginInfo;
import io.strimzi.api.kafka.model.connect.KafkaConnect;
import io.strimzi.api.kafka.model.connect.KafkaConnectResources;
import io.strimzi.api.kafka.model.connect.build.Artifact;
import io.strimzi.api.kafka.model.connect.build.Build;
import io.strimzi.api.kafka.model.connect.build.DockerOutput;
import io.strimzi.api.kafka.model.connect.build.DownloadableArtifact;
import io.strimzi.api.kafka.model.connect.build.MavenArtifact;
import io.strimzi.api.kafka.model.connect.build.Output;
import io.strimzi.api.kafka.model.connect.build.Plugin;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Maps a KafkaConnect {@code spec.build} into a {@link KafkaConnectBuildInfo}
 * response. Strimzi-specific concerns live here: reading the build spec, mapping
 * plugins/artifacts, and deriving the builder resource names. The generic
 * builder-pod / OpenShift Build introspection is delegated to
 * {@link ImageBuildInspectionService} in the common module.
 */
@ApplicationScoped
public class KafkaConnectBuildService {

    private static final Logger LOG = Logger.getLogger(KafkaConnectBuildService.class);

    @Inject
    OpenShiftDetector openShiftDetector;

    @Inject
    ImageBuildInspectionService buildInspectionService;

    /**
     * Constructor.
     */
    KafkaConnectBuildService() {
    }

    /**
     * Describes the build configuration and status for a KafkaConnect resource.
     *
     * @param connect the KafkaConnect resource
     * @return the build information, or null if no build spec is present
     */
    public KafkaConnectBuildInfo describeBuild(final KafkaConnect connect) {
        if (connect.getSpec() == null || connect.getSpec().getBuild() == null) {
            return null;
        }

        try {
            Build build = connect.getSpec().getBuild();
            Output output = build.getOutput();

            String outputImage = output != null ? output.getImage() : null;
            String outputType = output != null ? output.getType() : null;
            String pushSecret = output instanceof DockerOutput d ? d.getPushSecret() : null;

            boolean isOpenShift = openShiftDetector.isOpenShift();
            String buildMechanism = isOpenShift
                ? KafkaConnectBuildInfo.MECHANISM_OPENSHIFT_BUILD
                : KafkaConnectBuildInfo.MECHANISM_KUBERNETES_POD;
            List<PluginInfo> plugins = mapPlugins(build.getPlugins());

            String namespace = connect.getMetadata().getNamespace();
            String clusterName = connect.getMetadata().getName();

            ImageBuildStatus builder = null;
            String buildLogs = null;
            String message;

            try {
                ImageBuildInspection inspection = isOpenShift
                    ? buildInspectionService.inspectOpenShiftBuild(namespace,
                        KafkaConnectResources.buildConfigName(clusterName))
                    : buildInspectionService.inspectBuilderPod(namespace,
                        KafkaConnectResources.buildPodName(clusterName));

                if (inspection == null) {
                    message = isOpenShift
                        ? "No OpenShift build found"
                        : "Build completed or not started (no active builder pod)";
                } else {
                    builder = inspection.status();
                    buildLogs = inspection.logs();
                    message = builder.message();
                }
            } catch (Exception e) {
                // Deliberate degradation: the builder state is an optional enrichment of
                // get_kafka_connect, so a failure here (missing RBAC for builds, transient API
                // error) must not fail the whole call. Keep the spec-derived fields and surface
                // the problem through the message rather than letting the exception propagate.
                LOG.warnf(e, "Failed to introspect build for %s/%s", namespace, clusterName);
                message = isOpenShift
                    ? "Build configured; OpenShift Build introspection failed: " + ExceptionUtils.rootCauseMessage(e)
                    : "Build configured; builder introspection failed: " + ExceptionUtils.rootCauseMessage(e);
            }

            return KafkaConnectBuildInfo.of(
                outputImage,
                outputType,
                pushSecret,
                buildMechanism,
                plugins,
                builder,
                buildLogs,
                message
            );
        } catch (Exception e) {
            // Deliberate degradation (same rationale as above): build info is supplementary, so
            // an unexpected failure mapping the spec yields an "unavailable" marker rather than
            // failing get_kafka_connect.
            LOG.warnf(e, "Failed to describe build for KafkaConnect %s",
                connect.getMetadata() != null ? connect.getMetadata().getName() : "<unknown>");
            return KafkaConnectBuildInfo.unavailable(
                "Build configured; details unavailable: " + ExceptionUtils.rootCauseMessage(e));
        }
    }

    /**
     * Maps a list of plugins to PluginInfo objects.
     *
     * @param plugins the list of plugins
     * @return the list of PluginInfo objects
     */
    private List<PluginInfo> mapPlugins(final List<Plugin> plugins) {
        if (plugins == null) {
            return List.of();
        }

        return plugins.stream()
            .map(plugin -> PluginInfo.of(
                plugin.getName(),
                mapArtifacts(plugin.getArtifacts())
            ))
            .toList();
    }

    /**
     * Maps a list of artifacts to ArtifactInfo objects.
     *
     * @param artifacts the list of artifacts
     * @return the list of ArtifactInfo objects
     */
    private List<ArtifactInfo> mapArtifacts(final List<Artifact> artifacts) {
        if (artifacts == null) {
            return List.of();
        }

        return artifacts.stream()
            .map(artifact -> ArtifactInfo.of(
                artifact.getType(),
                artifactSource(artifact)
            ))
            .toList();
    }

    /**
     * Determines the source string for an artifact.
     *
     * @param artifact the artifact
     * @return the source string (URL or Maven coordinates), or null
     */
    private String artifactSource(final Artifact artifact) {
        // DownloadableArtifact is the base for the URL-bearing artifact types (jar, tgz, zip, other).
        if (artifact instanceof DownloadableArtifact downloadable) {
            return downloadable.getUrl();
        } else if (artifact instanceof MavenArtifact maven) {
            return maven.getGroup() + ":" + maven.getArtifact() + ":" + maven.getVersion();
        }
        return null;
    }
}
