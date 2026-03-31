/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.readiness;

import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectAccessReview;
import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectAccessReviewBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.V1AuthorizationAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.AuthorizationAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.InOutCreateable;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link KubernetesConnectionReadinessCheck}.
 */
@QuarkusTest
class KubernetesConnectionReadinessCheckTest {

    @InjectMock
    KubernetesClient kubernetesClient;

    private KubernetesConnectionReadinessCheck healthCheck;

    KubernetesConnectionReadinessCheckTest() {
    }

    @BeforeEach
    void setUp() {
        healthCheck = new KubernetesConnectionReadinessCheck();
        healthCheck.kubernetesClient = kubernetesClient;
    }

    @Test
    @SuppressWarnings("unchecked")
    void testHealthyWhenApiReachable() {
        SelfSubjectAccessReview review = new SelfSubjectAccessReviewBuilder()
            .withNewStatus()
                .withAllowed(true)
            .endStatus()
            .build();

        AuthorizationAPIGroupDSL authDsl = Mockito.mock(AuthorizationAPIGroupDSL.class);
        V1AuthorizationAPIGroupDSL v1Dsl = Mockito.mock(V1AuthorizationAPIGroupDSL.class);
        InOutCreateable<SelfSubjectAccessReview, SelfSubjectAccessReview> ssarDsl =
            Mockito.mock(InOutCreateable.class);

        when(kubernetesClient.authorization()).thenReturn(authDsl);
        when(authDsl.v1()).thenReturn(v1Dsl);
        when(v1Dsl.selfSubjectAccessReview()).thenReturn(ssarDsl);
        when(ssarDsl.create(any(SelfSubjectAccessReview.class))).thenReturn(review);

        HealthCheckResponse response = healthCheck.call();

        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
    }

    @Test
    void testUnhealthyWhenApiUnreachable() {
        when(kubernetesClient.authorization()).thenThrow(
            new RuntimeException("Connection refused"));

        HealthCheckResponse response = healthCheck.call();

        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
    }
}
