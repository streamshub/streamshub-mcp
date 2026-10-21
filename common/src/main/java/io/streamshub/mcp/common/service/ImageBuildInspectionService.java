/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service;

import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Pod;
import io.streamshub.mcp.common.dto.ImageBuildInspection;
import io.streamshub.mcp.common.dto.ImageBuildStatus;
import io.streamshub.mcp.common.dto.LogCollectionParams;
import io.streamshub.mcp.common.dto.PodLogsResult;
import io.streamshub.mcp.common.service.log.LogCollectionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Inspects the state of a container-image build on Kubernetes or OpenShift.
 * <p>
 * Covers both build mechanisms: a Kubernetes builder pod (Buildah/Kaniko) read
 * by name, and an OpenShift {@code build.openshift.io/v1 Build} read through
 * generic resources (no {@code openshift-client} dependency). This is
 * cross-cutting Kubernetes/OpenShift inspection and carries no Strimzi-specific
 * knowledge; callers supply the resource names and map the result into their own
 * domain response. A build-log excerpt is collected only on the failure path.
 */
@ApplicationScoped
public class ImageBuildInspectionService {

    private static final Logger LOG = Logger.getLogger(ImageBuildInspectionService.class);

    private static final int BUILD_LOG_TAIL_LINES = 50;

    // Kubernetes builder pod phases and container reasons
    private static final String POD_PHASE_SUCCEEDED = "Succeeded";
    private static final String POD_PHASE_FAILED = "Failed";
    private static final String POD_PHASE_PENDING = "Pending";
    private static final String POD_PHASE_RUNNING = "Running";
    private static final String POD_REASON_COMPLETED = "Completed";
    private static final String POD_REASON_CONTAINER_CREATING = "ContainerCreating";
    private static final String POD_REASON_POD_INITIALIZING = "PodInitializing";
    /** Container waiting reasons that indicate normal startup, not a build failure. */
    private static final Set<String> BENIGN_WAITING_REASONS =
        Set.of(POD_REASON_CONTAINER_CREATING, POD_REASON_POD_INITIALIZING);

    // OpenShift Build phases
    private static final String BUILD_PHASE_NEW = "New";
    private static final String BUILD_PHASE_PENDING = "Pending";
    private static final String BUILD_PHASE_RUNNING = "Running";
    private static final String BUILD_PHASE_COMPLETE = "Complete";
    private static final String BUILD_PHASE_FAILED = "Failed";
    private static final String BUILD_PHASE_ERROR = "Error";
    private static final String BUILD_PHASE_CANCELLED = "Cancelled";

    private static final String BUILD_CONFIG_NAME_LABEL = "openshift.io/build-config.name";
    private static final String BUILD_NUMBER_ANNOTATION = "openshift.io/build.number";
    private static final String OPENSHIFT_BUILD_POD_SUFFIX = "-build";

    @Inject
    KubernetesResourceService k8sService;

    @Inject
    LogCollectionService logCollectionService;

    /**
     * Constructor.
     */
    ImageBuildInspectionService() {
    }

    /**
     * Inspects a Kubernetes builder pod (Buildah/Kaniko) by name.
     *
     * @param namespace the namespace
     * @param podName the builder pod name
     * @return the inspection result, or {@code null} if no builder pod exists
     */
    public ImageBuildInspection inspectBuilderPod(final String namespace, final String podName) {
        Pod pod = k8sService.getResource(Pod.class, namespace, podName);
        if (pod == null) {
            return null;
        }

        ImageBuildStatus status = extractBuilderPodStatus(pod);
        String logs = isPodBuildFailing(status) ? collectLogs(namespace, pod) : null;
        return ImageBuildInspection.of(status, logs);
    }

    /**
     * Inspects the latest OpenShift {@code Build} for a build config.
     *
     * @param namespace the namespace
     * @param buildConfigName the OpenShift build config name
     * @return the inspection result, or {@code null} if no Build exists
     */
    public ImageBuildInspection inspectOpenShiftBuild(final String namespace, final String buildConfigName) {
        List<GenericKubernetesResource> builds = k8sService.queryGenericResourcesByLabel(
            OpenShiftDetector.OPENSHIFT_BUILD_API_GROUP, OpenShiftDetector.OPENSHIFT_BUILD_KIND,
            namespace, BUILD_CONFIG_NAME_LABEL, buildConfigName);

        if (builds.isEmpty()) {
            return null;
        }

        // Pick the latest build by build.number annotation (highest wins, tie-break on creationTimestamp)
        GenericKubernetesResource latestBuild = builds.stream()
            .max(Comparator.comparingInt(this::extractBuildNumber)
                .thenComparing(b -> b.getMetadata().getCreationTimestamp(),
                    Comparator.nullsFirst(Comparator.naturalOrder())))
            .orElse(builds.get(0));

        ImageBuildStatus status = extractOpenShiftBuildStatus(latestBuild);
        String logs = null;

        // Attach logs only when the build failed
        if (isOpenShiftBuildFailing(status.phase())) {
            String buildPodName = latestBuild.getMetadata().getName() + OPENSHIFT_BUILD_POD_SUFFIX;
            Pod buildPod = k8sService.getResource(Pod.class, namespace, buildPodName);
            if (buildPod != null) {
                logs = collectLogs(namespace, buildPod);
            }
        }

        return ImageBuildInspection.of(status, logs);
    }

    /**
     * Extracts builder status from a Kubernetes builder pod.
     * <p>
     * The bare container reason is parsed by the shared
     * {@link PodsService#currentContainerStateReason} helper; the remaining
     * fields (container-level {@code ready} flag, status-preferred image) are
     * read directly because the pod-level {@code PodInfo} does not expose them.
     *
     * @param pod the builder pod
     * @return the builder status
     */
    private ImageBuildStatus extractBuilderPodStatus(final Pod pod) {
        String podName = pod.getMetadata().getName();
        String phase = null;
        Boolean ready = null;
        String reason = null;
        String builderImage = null;

        if (pod.getStatus() != null) {
            phase = pod.getStatus().getPhase();

            if (pod.getStatus().getContainerStatuses() != null
                    && !pod.getStatus().getContainerStatuses().isEmpty()) {
                ContainerStatus containerStatus = pod.getStatus().getContainerStatuses().get(0);
                ready = containerStatus.getReady();
                builderImage = containerStatus.getImage();
                reason = PodsService.currentContainerStateReason(containerStatus.getState());
            }
        }

        // Fall back to spec for builder image if not in status
        if (builderImage == null && pod.getSpec() != null
                && pod.getSpec().getContainers() != null
                && !pod.getSpec().getContainers().isEmpty()) {
            builderImage = pod.getSpec().getContainers().get(0).getImage();
        }

        return ImageBuildStatus.ofPod(
            podName,
            phase,
            ready,
            reason,
            podBuildMessage(phase, reason),
            builderImage
        );
    }

    /**
     * Determines whether a builder pod is failing. A pod that has terminated
     * successfully carries {@code phase=Succeeded} and container reason
     * {@code Completed}; that is not a failure and no log excerpt is collected
     * for it. A {@code Pending} pod is a failure only when its container reason
     * is a real failure (for example {@code ImagePullBackOff}): a pod that is
     * merely starting up ({@code ContainerCreating}/{@code PodInitializing}, or
     * no reason yet) is pending, not failing, so no log excerpt is collected.
     *
     * @param status the builder status
     * @return true if the builder pod is failing
     */
    private boolean isPodBuildFailing(final ImageBuildStatus status) {
        String phase = status.phase();
        if (POD_PHASE_SUCCEEDED.equals(phase)) {
            return false;
        }
        if (POD_PHASE_FAILED.equals(phase)) {
            return true;
        }
        return isFailureReason(status.reason());
    }

    /**
     * Determines whether a container reason indicates a problem. A {@code null}
     * reason (running or no reason reported), the normal {@code Completed}
     * termination reason, and benign startup reasons
     * ({@code ContainerCreating}/{@code PodInitializing}) are not failures.
     *
     * @param reason the container reason
     * @return true if the reason indicates a failure
     */
    private boolean isFailureReason(final String reason) {
        return reason != null
            && !POD_REASON_COMPLETED.equals(reason)
            && !BENIGN_WAITING_REASONS.contains(reason);
    }

    /**
     * Builds a human-readable message about a builder pod's state.
     *
     * @param phase the pod phase
     * @param reason the container reason
     * @return the message
     */
    private String podBuildMessage(final String phase, final String reason) {
        if (POD_PHASE_SUCCEEDED.equals(phase)) {
            return "Build completed successfully";
        }
        if (isFailureReason(reason)) {
            return "Builder pod issue: " + reason;
        }
        if (POD_PHASE_FAILED.equals(phase)) {
            return "Builder pod failed";
        }
        if (POD_PHASE_PENDING.equals(phase)) {
            return "Builder pod pending";
        }
        if (POD_PHASE_RUNNING.equals(phase)) {
            return "Build in progress";
        }
        return phase == null ? "Builder pod state unknown" : "Builder pod state: " + phase;
    }

    /**
     * Extracts builder status from an OpenShift Build. The OpenShift Build API
     * does not report a builder image, so {@code builderImage} is {@code null}.
     *
     * @param build the build resource
     * @return the builder status
     */
    private ImageBuildStatus extractOpenShiftBuildStatus(final GenericKubernetesResource build) {
        String buildName = build.getMetadata().getName();
        String phase = nestedString(build, "status", "phase");
        String reason = nestedString(build, "status", "reason");
        String message = nestedString(build, "status", "message");

        return ImageBuildStatus.ofBuild(
            buildName,
            phase,
            reason,
            message != null ? message : openShiftBuildMessage(phase, reason)
        );
    }

    /**
     * Reads a nested string value from a {@link GenericKubernetesResource}, returning
     * {@code null} when the path is absent or the value is not a string. This mirrors
     * the null-tolerance of hand-walking the resource map: a non-string value (which a
     * Build status never carries for these fields) yields {@code null} rather than a
     * {@link ClassCastException}.
     *
     * @param resource the resource
     * @param path the path to the value (for example {@code "status", "phase"})
     * @return the string value, or {@code null} if absent or not a string
     */
    private String nestedString(final GenericKubernetesResource resource, final String... path) {
        Object value = resource.get(path);
        return value instanceof String s ? s : null;
    }

    /**
     * Extracts the build number from an OpenShift Build's annotations.
     *
     * @param build the build resource
     * @return the build number, or 0 if not present or invalid
     */
    private int extractBuildNumber(final GenericKubernetesResource build) {
        if (build.getMetadata() == null || build.getMetadata().getAnnotations() == null) {
            return 0;
        }
        String buildNumber = build.getMetadata().getAnnotations().get(BUILD_NUMBER_ANNOTATION);
        if (buildNumber == null) {
            return 0;
        }
        try {
            return Integer.parseInt(buildNumber);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Determines whether an OpenShift Build phase indicates failure.
     *
     * @param phase the build phase
     * @return true if the build is failing
     */
    private boolean isOpenShiftBuildFailing(final String phase) {
        return BUILD_PHASE_FAILED.equals(phase)
            || BUILD_PHASE_ERROR.equals(phase)
            || BUILD_PHASE_CANCELLED.equals(phase);
    }

    /**
     * Builds a human-readable message about an OpenShift Build's state.
     *
     * @param phase the build phase
     * @param reason the build reason
     * @return the message
     */
    private String openShiftBuildMessage(final String phase, final String reason) {
        if (reason != null) {
            return "Build issue: " + reason;
        }
        if (BUILD_PHASE_FAILED.equals(phase)) {
            return "Build failed";
        }
        if (BUILD_PHASE_ERROR.equals(phase)) {
            return "Build error";
        }
        if (BUILD_PHASE_CANCELLED.equals(phase)) {
            return "Build cancelled";
        }
        if (BUILD_PHASE_RUNNING.equals(phase)) {
            return "Build in progress";
        }
        if (BUILD_PHASE_COMPLETE.equals(phase)) {
            return "Build completed successfully";
        }
        if (BUILD_PHASE_NEW.equals(phase) || BUILD_PHASE_PENDING.equals(phase)) {
            return "Build pending";
        }
        return phase == null ? "Build state unknown" : "Build state: " + phase;
    }

    /**
     * Collects a tail of logs from a build pod on a best-effort basis.
     * <p>
     * The log excerpt is a bonus; the builder status is the primary signal. When
     * the builder image itself cannot be pulled the container never starts and the
     * log fetch fails for every pod, so this returns {@code null} rather than
     * letting the failure discard the surrounding builder status.
     *
     * @param namespace the namespace
     * @param pod the pod
     * @return the logs, or {@code null} if they could not be collected
     */
    private String collectLogs(final String namespace, final Pod pod) {
        try {
            LogCollectionParams params = LogCollectionParams.builder(BUILD_LOG_TAIL_LINES).build();
            PodLogsResult result = logCollectionService.collectLogs(namespace, List.of(pod), params);
            return result.logs();
        } catch (Exception e) {
            LOG.warnf(e, "Could not collect build logs from pod %s/%s",
                namespace, pod.getMetadata().getName());
            return null;
        }
    }
}
