/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest.setup.mcp;

import com.marcnuri.helm.Helm;
import com.marcnuri.helm.InstallCommand;
import com.marcnuri.helm.Release;
import com.marcnuri.helm.UpgradeCommand;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.qameta.allure.Step;
import io.skodjob.kubetest4j.resources.KubeResourceManager;
import io.streamshub.mcp.systemtest.Constants;
import io.streamshub.mcp.systemtest.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Deploys the MCP server via Helm using the
 * <a href="https://github.com/manusa/helm-java">helm-java</a> library.
 *
 * <p>Usage:
 * <pre>{@code
 * HelmSetup.builder(namespace)
 *     .withSet("image.tag", "test")
 *     .install();
 *
 * HelmSetup.upgrade(Constants.HELM_RELEASE_NAME, namespace)
 *     .withSet("sensitive.namespaces[0]", "strimzi-kafka")
 *     .run();
 *
 * HelmSetup.uninstall(Constants.HELM_RELEASE_NAME, namespace);
 * }</pre>
 */
public final class HelmSetup {

    private static final Logger LOGGER = LoggerFactory.getLogger(HelmSetup.class);
    private static final Path CHART_PATH = Path.of(Constants.INSTALL_DIR_HELM);

    private HelmSetup() {
    }

    /**
     * Create an install builder for the given namespace.
     *
     * @param namespace the target namespace
     * @return a new builder
     */
    public static InstallBuilder builder(final String namespace) {
        return new InstallBuilder(namespace);
    }

    /**
     * Create an upgrade builder for an existing release.
     *
     * @param releaseName the Helm release name
     * @param namespace   the release namespace
     * @return a new upgrade builder
     */
    public static UpgradeBuilder upgrade(final String releaseName, final String namespace) {
        return new UpgradeBuilder(releaseName, namespace);
    }

    /**
     * Uninstall a Helm release.
     *
     * @param releaseName the Helm release name
     * @param namespace   the release namespace
     */
    @Step("Helm uninstall {releaseName} in namespace {namespace}")
    public static void uninstall(final String releaseName, final String namespace) {
        LOGGER.info("Helm uninstall {} in namespace {}", releaseName, namespace);
        Helm.uninstall(releaseName)
            .withNamespace(namespace)
            .call();
    }

    @Step("Wait for MCP deployment readiness in namespace {namespace}")
    static void waitForReady(final String namespace) {
        LOGGER.info("Waiting for MCP deployment to become ready in namespace {}", namespace);
        Deployment deployment = KubeResourceManager.get().kubeClient().getClient()
            .apps().deployments()
            .inNamespace(namespace)
            .withName(Constants.HELM_RELEASE_NAME)
            .waitUntilReady(Constants.MCP_READY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (deployment == null) {
            throw new IllegalStateException("MCP deployment did not become ready within timeout");
        }
        LOGGER.info("MCP deployment is ready");
    }

    /**
     * Builder for {@code helm install}.
     */
    public static final class InstallBuilder {

        private final String namespace;
        private String releaseName = Constants.HELM_RELEASE_NAME;
        private final Map<String, String> values = new LinkedHashMap<>();

        InstallBuilder(final String namespace) {
            this.namespace = namespace;
            String image = Environment.MCP_IMAGE;
            values.put("image.repository", image.split(":")[0]);
            values.put("image.tag", image.contains(":") ? image.split(":")[1] : "latest");
            values.put("image.pullPolicy", "IfNotPresent");
        }

        /**
         * Set the Helm release name.
         * @param name the release name
         * @return this builder
         */
        public InstallBuilder withReleaseName(final String name) {
            this.releaseName = name;
            return this;
        }

        /**
         * Add a {@code --set key=value} override.
         * @param key   the values path
         * @param value the value
         * @return this builder
         */
        public InstallBuilder withSet(final String key, final String value) {
            values.put(key, value);
            return this;
        }

        /**
         * Run {@code helm install}.
         */
        @Step("Helm install {releaseName} into namespace {namespace}")
        public void install() {
            LOGGER.info("Helm install {} into namespace {} with values {}", releaseName, namespace, values);
            InstallCommand cmd = new Helm(CHART_PATH).install()
                .withName(releaseName)
                .withNamespace(namespace)
                .createNamespace()
                .waitReady()
                .withTimeout((int) (Constants.MCP_READY_TIMEOUT_MS / 1000));
            values.forEach(cmd::set);
            Release release = cmd.call();
            LOGGER.info("Helm install complete: {} (status={})", release.getName(), release.getStatus());
            waitForReady(namespace);
        }
    }

    /**
     * Builder for {@code helm upgrade}.
     */
    public static final class UpgradeBuilder {

        private final String releaseName;
        private final String namespace;
        private final Map<String, String> values = new LinkedHashMap<>();

        UpgradeBuilder(final String releaseName, final String namespace) {
            this.releaseName = releaseName;
            this.namespace = namespace;
        }

        /**
         * Add a {@code --set key=value} override.
         * @param key   the values path
         * @param value the value
         * @return this builder
         */
        public UpgradeBuilder withSet(final String key, final String value) {
            values.put(key, value);
            return this;
        }

        /**
         * Run {@code helm upgrade}.
         */
        @Step("Helm upgrade {releaseName} in namespace {namespace}")
        public void run() {
            LOGGER.info("Helm upgrade {} in namespace {} with values {}", releaseName, namespace, values);
            UpgradeCommand cmd = new Helm(CHART_PATH).upgrade()
                .withName(releaseName)
                .withNamespace(namespace)
                .waitReady()
                .withTimeout((int) (Constants.MCP_READY_TIMEOUT_MS / 1000));
            values.forEach(cmd::set);
            Release release = cmd.call();
            LOGGER.info("Helm upgrade complete: {} (status={})", release.getName(), release.getStatus());
            waitForReady(namespace);
        }
    }
}
