/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service;

import io.fabric8.kubernetes.client.KubernetesClient;

/**
 * Test-only factory for a real {@link KubernetesResourceService} outside CDI.
 * <p>
 * The service's constructor and its {@code kubernetesClient} field are package-private, so plain
 * JUnit tests in other modules cannot build one. This class sits in the same package (in test
 * sources only) so those tests can run domain services against a real fabric8 client, for example
 * one connected to a {@code @EnableKubernetesMockClient} server, instead of a Mockito chain.
 * <p>
 * Because this package now exists in both modules, Quarkus test boots would warn about a split package;
 * {@code quarkus.arc.ignored-split-packages} in the strimzi-mcp test {@code application.properties} silences it.
 */
public final class KubernetesResourceServiceTestFactory {

    private KubernetesResourceServiceTestFactory() {
    }

    /**
     * Creates a {@link KubernetesResourceService} that uses the given client.
     *
     * @param client the Kubernetes client the service should call
     * @return a service backed by {@code client}
     */
    public static KubernetesResourceService forClient(final KubernetesClient client) {
        KubernetesResourceService service = new KubernetesResourceService();
        service.kubernetesClient = client;
        return service;
    }
}
