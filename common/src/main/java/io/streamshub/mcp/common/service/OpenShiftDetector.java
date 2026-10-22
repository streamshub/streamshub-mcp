/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service;

import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Detects whether the current Kubernetes cluster is OpenShift by probing for the Build API.
 * The result is cached after the first probe to avoid repeated API calls. Uses the injected
 * KubernetesClient to query whether the cluster supports the build.openshift.io/v1 Build API.
 */
@ApplicationScoped
public class OpenShiftDetector {

    private static final Logger LOG = Logger.getLogger(OpenShiftDetector.class);

    /** API version of the OpenShift Build resource ({@code build.openshift.io/v1}). */
    public static final String OPENSHIFT_BUILD_API_GROUP = "build.openshift.io/v1";
    /** Kind of the OpenShift Build resource ({@code Build}). */
    public static final String OPENSHIFT_BUILD_KIND = "Build";

    @Inject
    KubernetesClient kubernetesClient;

    private volatile Boolean cached;

    OpenShiftDetector() {
    }

    /**
     * Determines whether the current Kubernetes cluster is OpenShift.
     * The detection is performed by checking if the cluster supports the
     * {@code build.openshift.io/v1 Build} API. A successful probe is memoized,
     * so later calls are served from the cache. A probe that fails (for example
     * a transient discovery error) is treated as {@code false} for that call but
     * is <em>not</em> memoized, so a later call retries rather than being stuck
     * on the wrong answer for the server's lifetime.
     *
     * @return {@code true} if the cluster is OpenShift, {@code false} otherwise
     */
    public boolean isOpenShift() {
        Boolean result = cached;
        if (result != null) {
            return result;
        }
        synchronized (this) {
            if (cached != null) {
                return cached;
            }
            Boolean probed = probeOpenShiftApi();
            if (probed != null) {
                cached = probed;
                return probed;
            }
            // Probe failed transiently: assume vanilla Kubernetes for this call but do not
            // memoize, so a later call retries once the discovery API recovers.
            return false;
        }
    }

    private Boolean probeOpenShiftApi() {
        try {
            return kubernetesClient.supports(OPENSHIFT_BUILD_API_GROUP, OPENSHIFT_BUILD_KIND);
        } catch (Exception e) {
            LOG.debugf(e, "Failed to probe OpenShift API, assuming vanilla Kubernetes for now");
            return null;
        }
    }

    /**
     * Reset the cached detection result. Package-private for testing.
     */
    void resetCache() {
        cached = null;
    }
}
