/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service;

import io.fabric8.kubernetes.api.model.APIResource;
import io.fabric8.kubernetes.api.model.APIResourceBuilder;
import io.fabric8.kubernetes.api.model.APIResourceList;
import io.fabric8.kubernetes.api.model.APIResourceListBuilder;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList;
import io.fabric8.kubernetes.api.model.Status;
import io.fabric8.kubernetes.api.model.StatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.streamshub.mcp.common.dto.ImageBuildInspection;
import io.streamshub.mcp.common.service.log.LogCollectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Tests {@link ImageBuildInspectionService#inspectOpenShiftBuild} against a real fabric8 client talking to a
 * mock API server that serves the OpenShift {@code build.openshift.io/v1} discovery document in its real order.
 * On OpenShift the {@code buildconfigs/webhooks} subresource also reports kind {@code Build} and is listed
 * before {@code builds}, so a kind-based lookup would resolve to the wrong resource. The 403, empty and 404
 * responses of the builds list are also served by the mock server, so their handling goes through the real client.
 */
@EnableKubernetesMockClient(crud = false)
class OpenShiftBuildDiscoveryTest {

    private static final String NAMESPACE = "kafka";
    private static final String BUILD_CONFIG_NAME = "my-connect-connect";
    private static final String BUILD_API = "/apis/build.openshift.io/v1";
    private static final String LABEL_SELECTOR =
        "?labelSelector=openshift.io%2Fbuild-config.name%3D" + BUILD_CONFIG_NAME;
    private static final String BUILDS_PATH = BUILD_API + "/namespaces/" + NAMESPACE + "/builds";
    private static final String WEBHOOKS_PATH = BUILD_API + "/namespaces/" + NAMESPACE + "/buildconfigs/webhooks";

    KubernetesMockServer server;
    KubernetesClient client;

    private ImageBuildInspectionService buildInspectionService;

    OpenShiftBuildDiscoveryTest() {
    }

    @BeforeEach
    void setUp() {
        KubernetesResourceService k8s = new KubernetesResourceService();
        k8s.kubernetesClient = client;
        buildInspectionService = new ImageBuildInspectionService();
        buildInspectionService.k8sService = k8s;
        buildInspectionService.logCollectionService = mock(LogCollectionService.class);
    }

    @Test
    void testInspectOpenShiftBuildQueriesBuildsNotWebhooksSubresource() throws InterruptedException {
        server.expect().get().withPath(BUILD_API).andReturn(200, openShiftBuildApiResources()).always();
        server.expect().get().withPath(WEBHOOKS_PATH + LABEL_SELECTOR).andReturn(403, forbidden()).always();
        server.expect().get().withPath(BUILDS_PATH + LABEL_SELECTOR).andReturn(200, buildList()).always();

        ImageBuildInspection inspection = buildInspectionService.inspectOpenShiftBuild(NAMESPACE, BUILD_CONFIG_NAME);

        assertNotNull(inspection);
        assertEquals("my-connect-connect-build-1", inspection.status().name());
        assertEquals("Complete", inspection.status().phase());
        String lastPath = server.getLastRequest().getPath();
        assertTrue(lastPath.startsWith(BUILDS_PATH + "?"), "Expected the builds list request, got " + lastPath);
    }

    /**
     * An RBAC denial on the builds list surfaces as a {@link KubernetesQueryException} whose message names
     * the query and the HTTP status only. The raw client error carries the request URL and the service-account
     * name; {@code KafkaConnectBuildService} passes this message to clients, so neither may leak into it.
     */
    @Test
    void testInspectOpenShiftBuildForbiddenBuildsListThrowsCleanMessage() {
        server.expect().get().withPath(BUILD_API).andReturn(200, openShiftBuildApiResources()).always();
        server.expect().get().withPath(BUILDS_PATH + LABEL_SELECTOR).andReturn(403, forbiddenBuilds()).always();

        KubernetesQueryException e = assertThrows(KubernetesQueryException.class,
            () -> buildInspectionService.inspectOpenShiftBuild(NAMESPACE, BUILD_CONFIG_NAME));

        String message = e.getMessage();
        assertTrue(message.startsWith("Failed to query builds.build.openshift.io by openshift.io/build-config.name="),
            "Unexpected message: " + message);
        assertTrue(message.endsWith("(403 Forbidden)"), "Unexpected message: " + message);
        assertFalse(message.contains("system:serviceaccount"), "Service-account name leaked: " + message);
        assertFalse(message.contains("https://"), "Request URL leaked: " + message);
        assertFalse(message.contains("/apis/"), "API path leaked: " + message);
        // The raw client error does carry both, so the checks above are not vacuous.
        assertTrue(e.getCause().getMessage().contains("system:serviceaccount"));
        assertTrue(e.getCause().getMessage().contains("https://"));
    }

    /**
     * A BuildConfig with no builds yet yields no inspection.
     */
    @Test
    void testInspectOpenShiftBuildReturnsNullForEmptyBuildsList() {
        server.expect().get().withPath(BUILD_API).andReturn(200, openShiftBuildApiResources()).always();
        server.expect().get().withPath(BUILDS_PATH + LABEL_SELECTOR).andReturn(200, buildList(List.of())).always();

        assertNull(buildInspectionService.inspectOpenShiftBuild(NAMESPACE, BUILD_CONFIG_NAME));
    }

    /**
     * A 404 on the builds path (the API server's {@code NotFound} status, as returned when the group or
     * resource is not served) is not treated as an empty list: the real client fails the list call and
     * {@code queryGenericResourcesByLabel} wraps it, with the status reason exactly as the server sent it.
     */
    @Test
    void testInspectOpenShiftBuildNotFoundBuildsPathThrows() {
        server.expect().get().withPath(BUILDS_PATH + LABEL_SELECTOR).andReturn(404, notFound()).always();

        KubernetesQueryException e = assertThrows(KubernetesQueryException.class,
            () -> buildInspectionService.inspectOpenShiftBuild(NAMESPACE, BUILD_CONFIG_NAME));

        String message = e.getMessage();
        assertTrue(message.startsWith("Failed to query builds.build.openshift.io by openshift.io/build-config.name="),
            "Unexpected message: " + message);
        assertTrue(message.endsWith("(404 NotFound)"), "Unexpected message: " + message);
    }

    /**
     * The {@code build.openshift.io/v1} discovery document in the order a live OpenShift API server returns it.
     *
     * @return the API resource list
     */
    private APIResourceList openShiftBuildApiResources() {
        return new APIResourceListBuilder()
            .withGroupVersion("build.openshift.io/v1")
            .withResources(
                apiResource("buildconfigs", "BuildConfig",
                    "create", "delete", "deletecollection", "get", "list", "patch", "update", "watch"),
                apiResource("buildconfigs/instantiate", "BuildRequest", "create"),
                apiResource("buildconfigs/instantiatebinary", "BinaryBuildRequest", "create"),
                apiResource("buildconfigs/webhooks", "Build", "create"),
                apiResource("builds", "Build", "get", "list", "watch", "create", "delete", "patch", "update"),
                apiResource("builds/clone", "BuildRequest", "create"),
                apiResource("builds/details", "Build", "update"),
                apiResource("builds/log", "BuildLog", "get"))
            .build();
    }

    private APIResource apiResource(final String name, final String kind, final String... verbs) {
        return new APIResourceBuilder()
            .withName(name)
            .withKind(kind)
            .withNamespaced(true)
            .withVerbs(verbs)
            .build();
    }

    private Status forbidden() {
        return new StatusBuilder()
            .withStatus("Failure")
            .withReason("Forbidden")
            .withCode(403)
            .withMessage("buildconfigs.build.openshift.io \"webhooks\" is forbidden: User "
                + "\"system:serviceaccount:streamshub-mcp:streamshub-mcp-strimzi\" cannot list resource "
                + "\"buildconfigs/webhooks\" in API group \"build.openshift.io\" in the namespace \"kafka\"")
            .build();
    }

    private Status forbiddenBuilds() {
        return new StatusBuilder()
            .withStatus("Failure")
            .withReason("Forbidden")
            .withCode(403)
            .withNewDetails().withGroup("build.openshift.io").withKind("builds").endDetails()
            .withMessage("builds.build.openshift.io is forbidden: User "
                + "\"system:serviceaccount:streamshub-mcp:streamshub-mcp-strimzi\" cannot list resource "
                + "\"builds\" in API group \"build.openshift.io\" in the namespace \"kafka\"")
            .build();
    }

    private Status notFound() {
        return new StatusBuilder()
            .withStatus("Failure")
            .withReason("NotFound")
            .withCode(404)
            .withMessage("the server could not find the requested resource")
            .build();
    }

    private GenericKubernetesResourceList buildList() {
        GenericKubernetesResource build = new GenericKubernetesResourceBuilder()
            .withApiVersion("build.openshift.io/v1")
            .withKind("Build")
            .withNewMetadata()
                .withName("my-connect-connect-build-1")
                .withNamespace(NAMESPACE)
                .withLabels(Map.of("openshift.io/build-config.name", BUILD_CONFIG_NAME))
                .withAnnotations(Map.of("openshift.io/build.number", "1"))
            .endMetadata()
            .withAdditionalProperties(Map.of("status", Map.of("phase", "Complete")))
            .build();
        return buildList(List.of(build));
    }

    private GenericKubernetesResourceList buildList(final List<GenericKubernetesResource> builds) {
        GenericKubernetesResourceList list = new GenericKubernetesResourceList();
        list.setApiVersion("build.openshift.io/v1");
        list.setKind("BuildList");
        list.setItems(builds);
        return list;
    }
}
