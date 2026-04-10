/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.readiness;

import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectAccessReview;
import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectAccessReviewBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;
import org.jboss.logging.Logger;

/**
 * Readiness health check that verifies Kubernetes API connectivity.
 *
 * <p>Uses {@code SelfSubjectAccessReview} as the connectivity probe.
 * The {@code SelfSubjectAccessReview} API is always permitted regardless of RBAC
 * configuration, so it works even with minimal namespace-scoped permissions
 * on hardened clusters. The health check reports UP if the API call succeeds
 * (API reachable, token valid), regardless of the permission result.</p>
 */
@Readiness
@ApplicationScoped
public class KubernetesConnectionReadinessCheck implements HealthCheck {

    private static final Logger LOG = Logger.getLogger(KubernetesConnectionReadinessCheck.class);

    @Inject
    KubernetesClient kubernetesClient;

    KubernetesConnectionReadinessCheck() {
    }

    /**
     * Check Kubernetes API server connectivity by performing a SelfSubjectAccessReview.
     * Reports UP if the API server responds (regardless of permission result),
     * DOWN if the API server is unreachable or the token is invalid.
     *
     * @return health check response with UP if API is reachable, DOWN otherwise
     */
    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named("kubernetes-api");
        try {
            SelfSubjectAccessReview review = new SelfSubjectAccessReviewBuilder()
                .withNewSpec()
                    .withNewResourceAttributes()
                        .withVerb("get")
                        .withResource("pods")
                    .endResourceAttributes()
                .endSpec()
                .build();

            // The call succeeding proves API connectivity and token validity.
            // The allowed/denied result is irrelevant for health checking.
            kubernetesClient.authorization().v1()
                .selfSubjectAccessReview()
                .create(review);

            return builder.up().build();
        } catch (Exception e) {
            LOG.debugf("Kubernetes API health check failed: %s", e.getMessage());
            return builder.down()
                .withData("error", "Kubernetes API unreachable")
                .build();
        }
    }
}
