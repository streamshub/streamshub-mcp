/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafkaconnect;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.common.dto.ImageBuildInspection;
import io.streamshub.mcp.common.dto.ImageBuildStatus;
import io.streamshub.mcp.common.service.ImageBuildInspectionService;
import io.streamshub.mcp.common.service.OpenShiftDetector;
import io.streamshub.mcp.strimzi.dto.kafkaconnect.KafkaConnectBuildInfo;
import io.strimzi.api.kafka.model.connect.KafkaConnect;
import io.strimzi.api.kafka.model.connect.KafkaConnectBuilder;
import io.strimzi.api.kafka.model.connect.build.JarArtifactBuilder;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link KafkaConnectBuildService}: Strimzi spec mapping and delegation
 * to {@link ImageBuildInspectionService}.
 */
@QuarkusTest
class KafkaConnectBuildServiceTest {

    @InjectMock
    OpenShiftDetector openShiftDetector;

    @InjectMock
    ImageBuildInspectionService buildInspectionService;

    @Inject
    KafkaConnectBuildService buildService;

    KafkaConnectBuildServiceTest() {
    }

    @BeforeEach
    void setUp() {
        when(openShiftDetector.isOpenShift()).thenReturn(false);
    }

    @Test
    void testDescribeBuildNullWhenNoBuildSpec() {
        KafkaConnect connect = new KafkaConnectBuilder()
            .withNewMetadata().withName("c").withNamespace("kafka").endMetadata()
            .withNewSpec().endSpec()
            .build();
        assertNull(buildService.describeBuild(connect));
    }

    @Test
    void testDescribeBuildSurfacesDockerOutputAndPluginsWhenNoBuilder() {
        when(buildInspectionService.inspectBuilderPod(eq("kafka"), anyString())).thenReturn(null);

        KafkaConnectBuildInfo build = buildService.describeBuild(connectWithBuild());

        assertEquals("my.registry/connect:latest", build.outputImage());
        assertEquals("docker", build.outputType());
        assertEquals("KubernetesPod", build.buildMechanism());
        assertEquals(1, build.plugins().size());
        assertEquals("debezium", build.plugins().get(0).name());
        assertEquals("jar", build.plugins().get(0).artifacts().get(0).type());
        assertNull(build.builder());
        assertNull(build.buildLogs());
        assertEquals("Build completed or not started (no active builder pod)", build.message());
    }

    @Test
    void testDescribeBuildDelegatesBuilderStatusAndLogs() {
        ImageBuildStatus status = ImageBuildStatus.ofPod("c-connect-build", "Pending",
            false, "ImagePullBackOff", "Builder pod issue: ImagePullBackOff", "quay.io/strimzi/buildah:1.3.0");
        when(buildInspectionService.inspectBuilderPod(eq("kafka"), anyString()))
            .thenReturn(ImageBuildInspection.of(status, "error: 404 Not Found"));

        KafkaConnectBuildInfo build = buildService.describeBuild(connectWithBuild());

        assertSame(status, build.builder());
        assertEquals("error: 404 Not Found", build.buildLogs());
        assertEquals("Builder pod issue: ImagePullBackOff", build.message());
    }

    @Test
    void testDescribeBuildDegradesGracefullyOnError() {
        when(buildInspectionService.inspectBuilderPod(eq("kafka"), anyString()))
            .thenThrow(new RuntimeException("boom"));

        KafkaConnectBuildInfo build = buildService.describeBuild(connectWithBuild());

        assertEquals("my.registry/connect:latest", build.outputImage());
        assertNull(build.builder());
        assertTrue(build.message().contains("introspection failed"));
        assertTrue(build.message().contains("boom"));
    }

    @Test
    void testDescribeBuildOpenShiftMechanismAndDelegation() {
        when(openShiftDetector.isOpenShift()).thenReturn(true);
        ImageBuildStatus status = ImageBuildStatus.ofBuild("c-connect-build-2", "Failed",
            "PushImageToRegistryFailed", "denied");
        when(buildInspectionService.inspectOpenShiftBuild(eq("kafka"), anyString()))
            .thenReturn(ImageBuildInspection.of(status, "error: denied"));

        KafkaConnectBuildInfo build = buildService.describeBuild(connectWithBuild());

        assertEquals("OpenShiftBuild", build.buildMechanism());
        assertSame(status, build.builder());
        assertEquals("error: denied", build.buildLogs());
        assertEquals("denied", build.message());
    }

    @Test
    void testDescribeBuildOpenShiftNoBuildFound() {
        when(openShiftDetector.isOpenShift()).thenReturn(true);
        when(buildInspectionService.inspectOpenShiftBuild(eq("kafka"), anyString())).thenReturn(null);

        KafkaConnectBuildInfo build = buildService.describeBuild(connectWithBuild());

        assertEquals("OpenShiftBuild", build.buildMechanism());
        assertNull(build.builder());
        assertTrue(build.message().contains("No OpenShift build found"));
    }

    private KafkaConnect connectWithBuild() {
        return new KafkaConnectBuilder()
            .withNewMetadata().withName("c").withNamespace("kafka").endMetadata()
            .withNewSpec()
                .withNewBuild()
                    .withNewDockerOutput()
                        .withImage("my.registry/connect:latest")
                    .endDockerOutput()
                    .addNewPlugin()
                        .withName("debezium")
                        .withArtifacts(new JarArtifactBuilder()
                            .withUrl("https://example.com/debezium.jar")
                            .build())
                    .endPlugin()
                .endBuild()
            .endSpec()
            .build();
    }
}
