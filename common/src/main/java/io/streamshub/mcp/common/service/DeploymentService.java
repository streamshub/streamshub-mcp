/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.streamshub.mcp.common.config.KubernetesConstants;
import jakarta.enterprise.context.ApplicationScoped;
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

    DeploymentService() {
    }


    /**
     * Extract version from deployment container image.
     *
     * @param deployment the deployment to extract version from
     * @return the version string or "unknown" if not determinable
     */
    public String extractVersion(Deployment deployment) {
        String image = extractImage(deployment);
        if (image != null && image.contains(":")) {
            return image.substring(image.lastIndexOf(":") + 1);
        }
        return KubernetesConstants.UNKNOWN;
    }

    /**
     * Extract full image name from deployment.
     *
     * @param deployment the deployment to extract image from
     * @return the full image name or null if not determinable
     */
    public String extractImage(Deployment deployment) {
        return deployment.getSpec().getTemplate().getSpec()
            .getContainers().getFirst().getImage();
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
                    deployment.getMetadata().getName() : KubernetesConstants.UNKNOWN, e.getMessage());
        }
        return null;
    }

}
