/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.storage.StorageClassBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.streamshub.mcp.common.service.KubernetesResourceServiceTestFactory;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaPvcInfo;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaPvcResponse;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.kafka.KafkaBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests {@link KafkaService#getClusterPvcs} against a real {@code KubernetesResourceService} and a real
 * fabric8 client talking to a CRUD mock API server. StorageClass is cluster-scoped: the real client
 * rejects a lookup with a null namespace ("namespace cannot be null") before sending any request, so
 * these tests fail if the StorageClass is read through the namespaced lookup.
 */
@EnableKubernetesMockClient(crud = true)
class KafkaServicePvcMockServerTest {

    private static final String NAMESPACE = "kafka";
    private static final String CLUSTER_NAME = "mcp-cluster";
    private static final String EXPANDABLE_CLASS = "rook-ceph-block";
    private static final String UNSET_CLASS = "local-path";
    private static final String MISSING_CLASS = "deleted-class";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    KubernetesClient client;

    private KafkaService kafkaService;

    KafkaServicePvcMockServerTest() {
    }

    @BeforeEach
    void setUp() {
        kafkaService = new KafkaService();
        kafkaService.k8sService = KubernetesResourceServiceTestFactory.forClient(client);

        client.resource(new KafkaBuilder()
            .withNewMetadata()
                .withName(CLUSTER_NAME)
                .withNamespace(NAMESPACE)
            .endMetadata()
            .withNewSpec()
                .withNewKafka()
                    .withVersion("4.3.1")
                .endKafka()
            .endSpec()
            .build()).create();

        client.resource(new StorageClassBuilder()
            .withNewMetadata().withName(EXPANDABLE_CLASS).endMetadata()
            .withProvisioner("rook-ceph.rbd.csi.ceph.com")
            .withAllowVolumeExpansion(true)
            .build()).create();
        client.resource(new StorageClassBuilder()
            .withNewMetadata().withName(UNSET_CLASS).endMetadata()
            .withProvisioner("rancher.io/local-path")
            .build()).create();
    }

    /**
     * A StorageClass with {@code allowVolumeExpansion: true} reports true.
     */
    @Test
    void testGetClusterPvcsReportsTrueWhenStorageClassAllowsExpansion() {
        createPvc("data-0-mcp-cluster-broker-0", EXPANDABLE_CLASS);

        Map<String, KafkaPvcInfo> pvcs = pvcsByName(kafkaService.getClusterPvcs(NAMESPACE, CLUSTER_NAME));

        assertEquals(1, pvcs.size());
        assertEquals(Boolean.TRUE, pvcs.get("data-0-mcp-cluster-broker-0").allowVolumeExpansion());
    }

    /**
     * A StorageClass that leaves {@code allowVolumeExpansion} unset reports false, because Kubernetes
     * treats an unset field as not allowed.
     */
    @Test
    void testGetClusterPvcsReportsFalseWhenStorageClassLeavesExpansionUnset() {
        createPvc("data-0-mcp-cluster-broker-0", UNSET_CLASS);

        Map<String, KafkaPvcInfo> pvcs = pvcsByName(kafkaService.getClusterPvcs(NAMESPACE, CLUSTER_NAME));

        assertEquals(1, pvcs.size());
        assertEquals(Boolean.FALSE, pvcs.get("data-0-mcp-cluster-broker-0").allowVolumeExpansion());
    }

    /**
     * A PVC whose StorageClass does not exist (the server answers 404) omits the field, while a PVC on a
     * readable StorageClass in the same call keeps its value.
     */
    @Test
    void testGetClusterPvcsOmitsExpansionOnlyForMissingStorageClass() {
        createPvc("data-0-mcp-cluster-broker-0", MISSING_CLASS);
        createPvc("data-0-mcp-cluster-broker-1", EXPANDABLE_CLASS);

        Map<String, KafkaPvcInfo> pvcs = pvcsByName(kafkaService.getClusterPvcs(NAMESPACE, CLUSTER_NAME));

        assertEquals(2, pvcs.size());
        KafkaPvcInfo missing = pvcs.get("data-0-mcp-cluster-broker-0");
        assertNull(missing.allowVolumeExpansion());
        JsonNode missingJson = MAPPER.valueToTree(missing);
        assertEquals(MISSING_CLASS, missingJson.path("storage_class").asText());
        assertFalse(missingJson.has("allow_volume_expansion"),
            "allow_volume_expansion should be omitted when the StorageClass does not exist");
        assertEquals(Boolean.TRUE, pvcs.get("data-0-mcp-cluster-broker-1").allowVolumeExpansion());
    }

    private void createPvc(final String name, final String storageClassName) {
        client.resource(new PersistentVolumeClaimBuilder()
            .withNewMetadata()
                .withName(name)
                .withNamespace(NAMESPACE)
                .addToLabels(ResourceLabels.STRIMZI_CLUSTER_LABEL, CLUSTER_NAME)
            .endMetadata()
            .withNewSpec()
                .withStorageClassName(storageClassName)
                .withAccessModes("ReadWriteOnce")
            .endSpec()
            .build()).create();
    }

    private static Map<String, KafkaPvcInfo> pvcsByName(final KafkaPvcResponse response) {
        return response.pvcs().stream()
            .collect(Collectors.toMap(KafkaPvcInfo::name, Function.identity()));
    }
}
