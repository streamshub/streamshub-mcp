/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service;

import io.fabric8.kubernetes.api.model.ContainerState;
import io.fabric8.kubernetes.api.model.ContainerStateRunning;
import io.fabric8.kubernetes.api.model.ContainerStateTerminated;
import io.fabric8.kubernetes.api.model.ContainerStateWaiting;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodCondition;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.common.dto.ImageBuildInspection;
import io.streamshub.mcp.common.dto.LogCollectionParams;
import io.streamshub.mcp.common.dto.PodLogsResult;
import io.streamshub.mcp.common.service.log.LogCollectionService;
import io.streamshub.mcp.common.service.log.LogQueryException;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ImageBuildInspectionService} builder-pod and OpenShift Build introspection.
 */
@QuarkusTest
class ImageBuildInspectionServiceTest {

    @InjectMock
    KubernetesClient kubernetesClient;

    @InjectMock
    LogCollectionService logCollectionService;

    @Inject
    ImageBuildInspectionService buildInspectionService;

    ImageBuildInspectionServiceTest() {
    }

    @Test
    void testInspectBuilderPodNotFoundReturnsNull() {
        stubBuilderPod("kafka", "c-connect-build", null);
        assertNull(buildInspectionService.inspectBuilderPod("kafka", "c-connect-build"));
    }

    @Test
    void testBuilderPodFailureSurfacesStateAndLogs() {
        stubBuilderPod("kafka", "c-connect-build", pendingImagePullBackOffPod("quay.io/strimzi/buildah:1.3.0"));
        stubLogs("kafka", "error: failed to pull plugin: 404 Not Found");

        ImageBuildInspection inspection = buildInspectionService.inspectBuilderPod("kafka", "c-connect-build");

        assertEquals("Pod", inspection.status().kind());
        assertEquals("c-connect-build", inspection.status().name());
        assertEquals("Pending", inspection.status().phase());
        assertEquals("ImagePullBackOff", inspection.status().reason());
        assertTrue(inspection.status().builderImage().contains("buildah"));
        assertTrue(inspection.logs().contains("404"));
    }

    @Test
    void testBuilderPodRunningDoesNotAttachLogs() {
        stubBuilderPod("kafka", "c-connect-build", runningPod("quay.io/strimzi/buildah:1.3.0"));

        ImageBuildInspection inspection = buildInspectionService.inspectBuilderPod("kafka", "c-connect-build");

        assertEquals("Running", inspection.status().phase());
        assertNull(inspection.logs());
    }

    @Test
    void testBuilderPodFailureSurfacesStatusWhenLogFetchFails() {
        // When the builder image itself cannot be pulled the container never starts, so the
        // log fetch fails for every pod and LogCollectionService throws. The builder status
        // (phase + reason) is the headline diagnostic signal and must still come through.
        stubBuilderPod("kafka", "c-connect-build", pendingImagePullBackOffPod("quay.io/strimzi/buildah:1.3.0"));
        when(logCollectionService.collectLogs(eq("kafka"), anyList(), any(LogCollectionParams.class)))
            .thenThrow(new LogQueryException("Failed to retrieve logs from all 1 pods", null));

        ImageBuildInspection inspection = buildInspectionService.inspectBuilderPod("kafka", "c-connect-build");

        assertEquals("Pending", inspection.status().phase());
        assertEquals("ImagePullBackOff", inspection.status().reason());
        assertNull(inspection.logs());
    }

    @Test
    void testBuilderPodSucceededIsNotTreatedAsFailure() {
        // A successful builder pod lingers briefly (terminated reason "Completed", phase
        // "Succeeded") before the operator deletes it. It must not be reported as failing,
        // and no build logs should be attached.
        stubBuilderPod("kafka", "c-connect-build", succeededPod("quay.io/strimzi/buildah:1.3.0"));

        ImageBuildInspection inspection = buildInspectionService.inspectBuilderPod("kafka", "c-connect-build");

        assertEquals("Succeeded", inspection.status().phase());
        assertEquals("Build completed successfully", inspection.status().message());
        assertNull(inspection.logs());
        verify(logCollectionService, never()).collectLogs(any(), anyList(), any(LogCollectionParams.class));
    }

    @Test
    void testBuilderPodWithoutPhaseReportsUnknownState() {
        // A builder pod with no status yet (phase null) must produce a clean message,
        // not the literal "Builder pod state: null".
        stubBuilderPod("kafka", "c-connect-build", noStatusPod("quay.io/strimzi/buildah:1.3.0"));

        ImageBuildInspection inspection = buildInspectionService.inspectBuilderPod("kafka", "c-connect-build");

        assertNull(inspection.status().phase());
        assertEquals("Builder pod state unknown", inspection.status().message());
        assertNull(inspection.logs());
    }

    @Test
    void testBuilderPodPendingContainerCreatingNotTreatedAsFailure() {
        // A builder pod briefly Pending while the container is still being created is a normal
        // startup state, not a build failure: it must read as "pending" and attach no logs.
        stubBuilderPod("kafka", "c-connect-build",
            pendingPodWithWaitingReason("ContainerCreating", "quay.io/strimzi/buildah:1.3.0"));

        ImageBuildInspection inspection = buildInspectionService.inspectBuilderPod("kafka", "c-connect-build");

        assertEquals("Pending", inspection.status().phase());
        assertEquals("ContainerCreating", inspection.status().reason());
        assertEquals("Builder pod pending", inspection.status().message());
        assertNull(inspection.logs());
        verify(logCollectionService, never()).collectLogs(any(), anyList(), any(LogCollectionParams.class));
    }

    @Test
    void testBuilderPodPendingPodInitializingNotTreatedAsFailure() {
        stubBuilderPod("kafka", "c-connect-build",
            pendingPodWithWaitingReason("PodInitializing", "quay.io/strimzi/buildah:1.3.0"));

        ImageBuildInspection inspection = buildInspectionService.inspectBuilderPod("kafka", "c-connect-build");

        assertEquals("Builder pod pending", inspection.status().message());
        assertNull(inspection.logs());
        verify(logCollectionService, never()).collectLogs(any(), anyList(), any(LogCollectionParams.class));
    }

    @Test
    void testBuilderPodPendingWithoutReasonNotTreatedAsFailure() {
        stubBuilderPod("kafka", "c-connect-build",
            pendingPodWithWaitingReason(null, "quay.io/strimzi/buildah:1.3.0"));

        ImageBuildInspection inspection = buildInspectionService.inspectBuilderPod("kafka", "c-connect-build");

        assertEquals("Pending", inspection.status().phase());
        assertNull(inspection.status().reason());
        assertEquals("Builder pod pending", inspection.status().message());
        assertNull(inspection.logs());
        verify(logCollectionService, never()).collectLogs(any(), anyList(), any(LogCollectionParams.class));
    }

    @Test
    void testBuilderPodUnschedulableSurfacesSchedulingReason() {
        // A builder pod that cannot be scheduled (no node with enough CPU/memory, or a taint) stays
        // Pending with no container statuses, so there is no container-level reason. The cause lives
        // on the PodScheduled=False condition and must be surfaced instead of a bare
        // "Builder pod pending".
        stubBuilderPod("kafka", "c-connect-build",
            unschedulablePendingPod("Unschedulable",
                "0/3 nodes are available: 3 Insufficient cpu", "quay.io/strimzi/buildah:1.3.0"));
        when(logCollectionService.collectLogs(eq("kafka"), anyList(), any(LogCollectionParams.class)))
            .thenThrow(new LogQueryException("Failed to retrieve logs from all 1 pods", null));

        ImageBuildInspection inspection = buildInspectionService.inspectBuilderPod("kafka", "c-connect-build");

        assertEquals("Pending", inspection.status().phase());
        assertEquals("Unschedulable", inspection.status().reason());
        assertTrue(inspection.status().message().contains("Insufficient cpu"));
    }

    @Test
    void testBuilderPodEvictedSurfacesPodLevelReason() {
        // An evicted builder pod reports phase Failed with the cause on pod.status.reason/message and
        // no container statuses. The pod-level reason must be surfaced instead of a bare
        // "Builder pod failed".
        stubBuilderPod("kafka", "c-connect-build",
            evictedPod("Evicted", "Pod was evicted due to memory pressure",
                "quay.io/strimzi/buildah:1.3.0"));
        when(logCollectionService.collectLogs(eq("kafka"), anyList(), any(LogCollectionParams.class)))
            .thenThrow(new LogQueryException("Failed to retrieve logs from all 1 pods", null));

        ImageBuildInspection inspection = buildInspectionService.inspectBuilderPod("kafka", "c-connect-build");

        assertEquals("Failed", inspection.status().phase());
        assertEquals("Evicted", inspection.status().reason());
        assertTrue(inspection.status().message().contains("memory pressure"));
    }

    @Test
    void testInspectOpenShiftBuildPicksLatestAndSurfacesFailure() {
        stubOpenShiftBuilds("kafka", "c-connect-build",
            completeBuild("c-connect-build-1", 1),
            failedBuild("c-connect-build-2", 2, "PushImageToRegistryFailed", "denied"));
        stubBuilderPod("kafka", "c-connect-build-2-build", simplePod("c-connect-build-2-build"));
        stubLogs("kafka", "error: denied: requested access to the resource is denied");

        ImageBuildInspection inspection = buildInspectionService.inspectOpenShiftBuild("kafka", "c-connect-build");

        assertEquals("Build", inspection.status().kind());
        assertEquals("c-connect-build-2", inspection.status().name());
        assertEquals("Failed", inspection.status().phase());
        assertEquals("PushImageToRegistryFailed", inspection.status().reason());
        assertEquals("denied", inspection.status().message());
        assertNull(inspection.status().builderImage());
        assertTrue(inspection.logs().contains("denied"));
    }

    @Test
    void testInspectOpenShiftBuildCompleteDoesNotAttachLogs() {
        stubOpenShiftBuilds("kafka", "c-connect-build", completeBuild("c-connect-build-1", 1));

        ImageBuildInspection inspection = buildInspectionService.inspectOpenShiftBuild("kafka", "c-connect-build");

        assertEquals("Build", inspection.status().kind());
        assertEquals("c-connect-build-1", inspection.status().name());
        assertEquals("Complete", inspection.status().phase());
        assertNull(inspection.logs());
    }

    @Test
    void testOpenShiftBuildWithoutPhaseReportsUnknownState() {
        // An OpenShift Build with no phase reported must produce a clean message,
        // not the literal "Build state: null".
        stubOpenShiftBuilds("kafka", "c-connect-build", buildWithoutPhase("c-connect-build-1"));

        ImageBuildInspection inspection = buildInspectionService.inspectOpenShiftBuild("kafka", "c-connect-build");

        assertNull(inspection.status().phase());
        assertEquals("Build state unknown", inspection.status().message());
        assertNull(inspection.logs());
    }

    @Test
    void testInspectOpenShiftBuildNoneReturnsNull() {
        stubOpenShiftBuilds("kafka", "c-connect-build");  // empty list
        assertNull(buildInspectionService.inspectOpenShiftBuild("kafka", "c-connect-build"));
    }

    @Test
    void testInspectOpenShiftBuildFallsBackToCreationTimestamp() {
        stubOpenShiftBuilds("kafka", "c-connect-build",
            buildWithoutNumber("c-connect-build-old", "2026-10-21T09:00:00Z"),
            buildWithoutNumber("c-connect-build-new", "2026-10-21T10:00:00Z"));

        ImageBuildInspection inspection = buildInspectionService.inspectOpenShiftBuild("kafka", "c-connect-build");

        assertEquals("c-connect-build-new", inspection.status().name());
    }

    @SuppressWarnings("unchecked")
    private void stubBuilderPod(final String namespace, final String podName, final Pod pod) {
        MixedOperation podOp = mock(MixedOperation.class);
        NonNamespaceOperation nsOp = mock(NonNamespaceOperation.class);
        Resource resourceMock = mock(Resource.class);

        when(kubernetesClient.resources(Pod.class)).thenReturn(podOp);
        when(podOp.inNamespace(eq(namespace))).thenReturn(nsOp);
        when(nsOp.withName(eq(podName))).thenReturn(resourceMock);
        when(resourceMock.get()).thenReturn(pod);
    }

    private void stubLogs(final String namespace, final String logContent) {
        PodLogsResult logsResult = new PodLogsResult(
            List.of("build-pod"),
            logContent,
            0,
            1,
            1,
            false,
            0,
            List.of()
        );
        when(logCollectionService.collectLogs(eq(namespace), anyList(), any(LogCollectionParams.class)))
            .thenReturn(logsResult);
    }

    private Pod pendingImagePullBackOffPod(final String builderImage) {
        ContainerStateWaiting waiting = new ContainerStateWaiting();
        waiting.setReason("ImagePullBackOff");
        ContainerState waitingState = new ContainerState();
        waitingState.setWaiting(waiting);

        ContainerStatus containerStatus = new ContainerStatus();
        containerStatus.setImage(builderImage);
        containerStatus.setState(waitingState);
        containerStatus.setReady(false);

        PodStatus status = new PodStatus();
        status.setPhase("Pending");
        status.setContainerStatuses(List.of(containerStatus));

        return new PodBuilder()
            .withNewMetadata()
                .withName("c-connect-build")
                .withNamespace("kafka")
            .endMetadata()
            .withNewSpec()
                .addNewContainer()
                    .withName("builder")
                    .withImage(builderImage)
                .endContainer()
            .endSpec()
            .withStatus(status)
            .build();
    }

    private Pod pendingPodWithWaitingReason(final String reason, final String builderImage) {
        ContainerStateWaiting waiting = new ContainerStateWaiting();
        waiting.setReason(reason);
        ContainerState waitingState = new ContainerState();
        waitingState.setWaiting(waiting);

        ContainerStatus containerStatus = new ContainerStatus();
        containerStatus.setImage(builderImage);
        containerStatus.setState(waitingState);
        containerStatus.setReady(false);

        PodStatus status = new PodStatus();
        status.setPhase("Pending");
        status.setContainerStatuses(List.of(containerStatus));

        return new PodBuilder()
            .withNewMetadata()
                .withName("c-connect-build")
                .withNamespace("kafka")
            .endMetadata()
            .withNewSpec()
                .addNewContainer()
                    .withName("builder")
                    .withImage(builderImage)
                .endContainer()
            .endSpec()
            .withStatus(status)
            .build();
    }

    private Pod runningPod(final String builderImage) {
        ContainerState runningState = new ContainerState();
        runningState.setRunning(new ContainerStateRunning());

        ContainerStatus containerStatus = new ContainerStatus();
        containerStatus.setImage(builderImage);
        containerStatus.setState(runningState);
        containerStatus.setReady(true);

        PodStatus status = new PodStatus();
        status.setPhase("Running");
        status.setContainerStatuses(List.of(containerStatus));

        return new PodBuilder()
            .withNewMetadata()
                .withName("c-connect-build")
                .withNamespace("kafka")
            .endMetadata()
            .withNewSpec()
                .addNewContainer()
                    .withName("builder")
                    .withImage(builderImage)
                .endContainer()
            .endSpec()
            .withStatus(status)
            .build();
    }

    private Pod succeededPod(final String builderImage) {
        ContainerStateTerminated terminated = new ContainerStateTerminated();
        terminated.setReason("Completed");
        terminated.setExitCode(0);
        ContainerState terminatedState = new ContainerState();
        terminatedState.setTerminated(terminated);

        ContainerStatus containerStatus = new ContainerStatus();
        containerStatus.setImage(builderImage);
        containerStatus.setState(terminatedState);
        containerStatus.setReady(false);

        PodStatus status = new PodStatus();
        status.setPhase("Succeeded");
        status.setContainerStatuses(List.of(containerStatus));

        return new PodBuilder()
            .withNewMetadata()
                .withName("c-connect-build")
                .withNamespace("kafka")
            .endMetadata()
            .withNewSpec()
                .addNewContainer()
                    .withName("builder")
                    .withImage(builderImage)
                .endContainer()
            .endSpec()
            .withStatus(status)
            .build();
    }

    private Pod unschedulablePendingPod(final String reason, final String message, final String builderImage) {
        PodCondition scheduled = new PodCondition();
        scheduled.setType("PodScheduled");
        scheduled.setStatus("False");
        scheduled.setReason(reason);
        scheduled.setMessage(message);

        PodStatus status = new PodStatus();
        status.setPhase("Pending");
        status.setConditions(List.of(scheduled));
        // No container statuses: the pod was never scheduled, so no container exists yet.

        return new PodBuilder()
            .withNewMetadata()
                .withName("c-connect-build")
                .withNamespace("kafka")
            .endMetadata()
            .withNewSpec()
                .addNewContainer()
                    .withName("builder")
                    .withImage(builderImage)
                .endContainer()
            .endSpec()
            .withStatus(status)
            .build();
    }

    private Pod evictedPod(final String reason, final String message, final String builderImage) {
        PodStatus status = new PodStatus();
        status.setPhase("Failed");
        status.setReason(reason);
        status.setMessage(message);
        // No container statuses: the pod was evicted before its container reported status.

        return new PodBuilder()
            .withNewMetadata()
                .withName("c-connect-build")
                .withNamespace("kafka")
            .endMetadata()
            .withNewSpec()
                .addNewContainer()
                    .withName("builder")
                    .withImage(builderImage)
                .endContainer()
            .endSpec()
            .withStatus(status)
            .build();
    }

    private Pod noStatusPod(final String builderImage) {
        return new PodBuilder()
            .withNewMetadata()
                .withName("c-connect-build")
                .withNamespace("kafka")
            .endMetadata()
            .withNewSpec()
                .addNewContainer()
                    .withName("builder")
                    .withImage(builderImage)
                .endContainer()
            .endSpec()
            .build();
    }

    @SuppressWarnings("unchecked")
    private void stubOpenShiftBuilds(final String namespace, final String buildConfigName,
                                     final GenericKubernetesResource... builds) {
        MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList,
            Resource<GenericKubernetesResource>> resourceOp = mock(MixedOperation.class);
        NonNamespaceOperation<GenericKubernetesResource, GenericKubernetesResourceList,
            Resource<GenericKubernetesResource>> nsOp = mock(NonNamespaceOperation.class);
        NonNamespaceOperation<GenericKubernetesResource, GenericKubernetesResourceList,
            Resource<GenericKubernetesResource>> labeledOp = mock(NonNamespaceOperation.class);

        when(kubernetesClient.genericKubernetesResources("build.openshift.io/v1", "Build"))
            .thenReturn(resourceOp);
        when(resourceOp.inNamespace(eq(namespace))).thenReturn(nsOp);
        when(nsOp.withLabel(eq("openshift.io/build-config.name"), eq(buildConfigName))).thenReturn(labeledOp);

        GenericKubernetesResourceList buildList = new GenericKubernetesResourceList();
        buildList.setItems(List.of(builds));
        when(labeledOp.list()).thenReturn(buildList);
    }

    private GenericKubernetesResource completeBuild(final String name, final int buildNumber) {
        return new GenericKubernetesResourceBuilder()
            .withNewMetadata()
                .withName(name)
                .withNamespace("kafka")
                .withAnnotations(Map.of("openshift.io/build.number", String.valueOf(buildNumber)))
                .withCreationTimestamp("2026-10-21T10:00:00Z")
            .endMetadata()
            .withAdditionalProperties(Map.of(
                "status", Map.of(
                    "phase", "Complete"
                )
            ))
            .build();
    }

    private GenericKubernetesResource failedBuild(final String name, final int buildNumber,
                                                   final String reason, final String message) {
        return new GenericKubernetesResourceBuilder()
            .withNewMetadata()
                .withName(name)
                .withNamespace("kafka")
                .withAnnotations(Map.of("openshift.io/build.number", String.valueOf(buildNumber)))
                .withCreationTimestamp("2026-10-21T11:00:00Z")
            .endMetadata()
            .withAdditionalProperties(Map.of(
                "status", Map.of(
                    "phase", "Failed",
                    "reason", reason,
                    "message", message
                )
            ))
            .build();
    }

    private GenericKubernetesResource buildWithoutNumber(final String name, final String creationTimestamp) {
        return new GenericKubernetesResourceBuilder()
            .withNewMetadata()
                .withName(name)
                .withNamespace("kafka")
                .withCreationTimestamp(creationTimestamp)
            .endMetadata()
            .withAdditionalProperties(Map.of(
                "status", Map.of(
                    "phase", "Complete"
                )
            ))
            .build();
    }

    private GenericKubernetesResource buildWithoutPhase(final String name) {
        return new GenericKubernetesResourceBuilder()
            .withNewMetadata()
                .withName(name)
                .withNamespace("kafka")
                .withAnnotations(Map.of("openshift.io/build.number", "1"))
                .withCreationTimestamp("2026-10-21T10:00:00Z")
            .endMetadata()
            .withAdditionalProperties(Map.of(
                "status", Map.of()
            ))
            .build();
    }

    private Pod simplePod(final String name) {
        return new PodBuilder()
            .withNewMetadata()
                .withName(name)
                .withNamespace("kafka")
            .endMetadata()
            .build();
    }
}
