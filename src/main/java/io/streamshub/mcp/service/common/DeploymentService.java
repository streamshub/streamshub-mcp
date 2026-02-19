/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.service.common;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Service for deployment-specific operations across the application.
 * Provides utility methods for analyzing deployment metadata, versions, and status.
 * For querying deployments, use KubernetesResourceService.
 */
@ApplicationScoped
public class DeploymentService {

    private static final Logger LOG = Logger.getLogger(DeploymentService.class);

    @Inject
    KubernetesClient kubernetesClient;

    DeploymentService() {
    }


    /**
     * Extract version from deployment container image.
     * Common pattern used for determining Strimzi operator version.
     *
     * @param deployment the deployment to extract version from
     * @return the version string or "unknown" if not determinable
     */
    public String extractVersion(Deployment deployment) {
        if (isNullDeployment(deployment)) {
            return "unknown";
        }

        String image = deployment.getSpec().getTemplate().getSpec().getContainers().getFirst().getImage();
        if (image != null && image.contains(":")) {
            return image.substring(image.lastIndexOf(":") + 1);
        }
        return "unknown";
    }

    /**
     * Extract full image name from deployment.
     * Common pattern used for deployment introspection.
     *
     * @param deployment the deployment to extract image from
     * @return the full image name or "unknown" if not determinable
     */
    public String extractImage(Deployment deployment) {
        if (isNullDeployment(deployment)) {
            return "unknown";
        }

        return deployment.getSpec().getTemplate().getSpec().getContainers().getFirst().getImage();
    }

    private boolean isNullDeployment(Deployment deployment) {
        return deployment == null || deployment.getSpec() == null ||
            deployment.getSpec().getTemplate() == null ||
            deployment.getSpec().getTemplate().getSpec() == null ||
            deployment.getSpec().getTemplate().getSpec().getContainers() == null ||
            deployment.getSpec().getTemplate().getSpec().getContainers().isEmpty();
    }

    /**
     * Calculate deployment uptime in minutes based on creation timestamp.
     * Common pattern used for deployment status reporting.
     *
     * @param deployment the deployment to calculate uptime for
     * @return uptime in minutes, or null if it cannot be calculated
     */
    public Long calculateUptimeMinutes(Deployment deployment) {
        try {
            if (deployment != null && deployment.getMetadata() != null &&
                deployment.getMetadata().getCreationTimestamp() != null) {
                Instant created = Instant.parse(deployment.getMetadata().getCreationTimestamp());
                return ChronoUnit.MINUTES.between(created, Instant.now());
            }
        } catch (Exception e) {
            LOG.debugf("Could not calculate uptime for deployment %s: %s",
                deployment.getMetadata() != null ?
                    deployment.getMetadata().getName() : "unknown", e.getMessage());
        }
        return null;
    }

}