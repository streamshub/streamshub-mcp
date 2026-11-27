/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service;

import io.fabric8.kubernetes.api.model.storage.StorageClass;
import io.fabric8.kubernetes.api.model.storage.StorageClassBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link KubernetesResourceService} against a real fabric8 client talking to a mock API server,
 * so that API scoping and discovery behave as they do in production.
 */
@EnableKubernetesMockClient(crud = true)
class KubernetesResourceServiceMockServerTest {

    private static final String STORAGE_CLASS = "rook-ceph-block";

    KubernetesMockServer server;
    KubernetesClient client;

    private KubernetesResourceService k8s;

    KubernetesResourceServiceMockServerTest() {
    }

    @BeforeEach
    void setUp() {
        k8s = new KubernetesResourceService();
        k8s.kubernetesClient = client;
    }

    @Test
    void testGetClusterScopedResourceReadsStorageClass() {
        client.resource(new StorageClassBuilder()
            .withNewMetadata().withName(STORAGE_CLASS).endMetadata()
            .withProvisioner("rook-ceph.rbd.csi.ceph.com")
            .withAllowVolumeExpansion(true)
            .build()).create();

        StorageClass sc = k8s.getClusterScopedResource(StorageClass.class, STORAGE_CLASS);

        assertTrue(Boolean.TRUE.equals(sc.getAllowVolumeExpansion()));
    }

    @Test
    void testGetResourceWithNullNamespaceFailsForClusterScopedKind() {
        assertThrows(KubernetesQueryException.class,
            () -> k8s.getResource(StorageClass.class, null, STORAGE_CLASS));
    }
}
