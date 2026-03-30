/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentSpec;
import io.streamshub.mcp.common.config.KubernetesConstants;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DeploymentService}.
 */
class DeploymentServiceTest {

    private final DeploymentService service = new DeploymentService();

    DeploymentServiceTest() {
    }

    @Test
    void testExtractVersionFromImageTag() {
        Deployment deployment = createDeployment("quay.io/strimzi/operator:0.51.0");
        assertEquals("0.51.0", service.extractVersion(deployment));
    }

    @Test
    void testExtractVersionWithDigest() {
        Deployment deployment = createDeployment("quay.io/strimzi/operator:latest");
        assertEquals("latest", service.extractVersion(deployment));
    }

    @Test
    void testExtractVersionNoTag() {
        Deployment deployment = createDeployment("quay.io/strimzi/operator");
        assertEquals(KubernetesConstants.UNKNOWN, service.extractVersion(deployment));
    }

    @Test
    void testExtractImage() {
        String image = "quay.io/strimzi/operator:0.51.0";
        Deployment deployment = createDeployment(image);
        assertEquals(image, service.extractImage(deployment));
    }

    @Test
    void testCalculateUptimeMinutes() {
        Deployment deployment = createDeployment("image:tag");
        String oneHourAgo = Instant.now().minus(60, ChronoUnit.MINUTES).toString();
        deployment.getMetadata().setCreationTimestamp(oneHourAgo);

        Long uptime = service.calculateUptimeMinutes(deployment);
        assertNotNull(uptime);
        assertTrue(uptime >= 59 && uptime <= 61, "Uptime should be approximately 60 minutes");
    }

    @Test
    void testCalculateUptimeNullDeployment() {
        assertNull(service.calculateUptimeMinutes(null));
    }

    @Test
    void testCalculateUptimeNoTimestamp() {
        Deployment deployment = createDeployment("image:tag");
        deployment.getMetadata().setCreationTimestamp(null);
        assertNull(service.calculateUptimeMinutes(deployment));
    }

    private Deployment createDeployment(final String image) {
        Deployment deployment = new Deployment();
        ObjectMeta metadata = new ObjectMeta();
        metadata.setName("test-deployment");
        deployment.setMetadata(metadata);

        Container container = new Container();
        container.setImage(image);

        PodSpec podSpec = new PodSpec();
        podSpec.setContainers(List.of(container));

        PodTemplateSpec template = new PodTemplateSpec();
        template.setSpec(podSpec);

        DeploymentSpec spec = new DeploymentSpec();
        spec.setTemplate(template);
        deployment.setSpec(spec);

        return deployment;
    }
}
