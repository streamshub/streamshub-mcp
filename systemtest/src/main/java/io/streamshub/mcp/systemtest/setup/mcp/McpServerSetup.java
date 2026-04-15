/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest.setup.mcp;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.rbac.ClusterRole;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBinding;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBindingBuilder;
import io.qameta.allure.Step;
import io.skodjob.kubetest4j.resources.KubeResourceManager;
import io.skodjob.kubetest4j.utils.KubeTestUtils;
import io.streamshub.mcp.systemtest.Constants;
import io.streamshub.mcp.systemtest.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.function.Consumer;

/**
 * Deploys the MCP server by loading install YAMLs from
 * {@code strimzi-mcp/install/} and modifying them with Fabric8 builders.
 *
 * <p>Uses a builder pattern for composing deployment configuration:
 * <pre>{@code
 * // Default
 * McpServerSetup.deploy(namespace);
 *
 * // With custom env vars
 * McpServerSetup.builder(namespace)
 *     .withEnv("MCP_METRICS_PROVIDER", "streamshub-prometheus")
 *     .withEnv("QUARKUS_REST_CLIENT_PROMETHEUS_URL", url)
 *     .withEnvFromSecret("TOKEN", "my-secret", "token-key")
 *     .deploy();
 * }</pre>
 */
public final class McpServerSetup {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpServerSetup.class);

    private McpServerSetup() {
    }

    /**
     * Create a builder for MCP server deployment.
     *
     * @param namespace the target namespace
     * @return a new builder
     */
    public static Builder builder(final String namespace) {
        return new Builder(namespace);
    }

    /**
     * Deploy MCP server with default configuration.
     *
     * @param namespace the target namespace
     */
    @Step("Deploy MCP server into namespace {namespace}")
    public static void deploy(final String namespace) {
        builder(namespace).deploy();
    }

    /**
     * Builder for composing MCP server deployment configuration.
     */
    public static final class Builder {

        private final String namespace;
        private String image = Environment.MCP_IMAGE;
        private Consumer<DeploymentBuilder> modifier;

        /**
         * Create a new builder for the given namespace.
         *
         * @param namespace the target namespace
         */
        Builder(final String namespace) {
            this.namespace = namespace;
        }

        /**
         * Set a custom container image.
         *
         * @param image the container image
         * @return this builder
         */
        public Builder withImage(final String image) {
            this.image = image;
            return this;
        }

        /**
         * Add an environment variable with a plain value.
         *
         * @param name  the env var name
         * @param value the env var value
         * @return this builder
         */
        public Builder withEnv(final String name, final String value) {
            return addModifier(db -> db.editSpec().editTemplate().editSpec()
                .editFirstContainer()
                    .addToEnv(new EnvVarBuilder()
                        .withName(name).withValue(value).build())
                .endContainer()
                .endSpec().endTemplate().endSpec());
        }

        /**
         * Add an environment variable sourced from a Kubernetes Secret.
         *
         * @param envName    the env var name
         * @param secretName the Secret name
         * @param secretKey  the key within the Secret
         * @return this builder
         */
        public Builder withEnvFromSecret(final String envName, final String secretName,
                                          final String secretKey) {
            return addModifier(db -> db.editSpec().editTemplate().editSpec()
                .editFirstContainer()
                    .addToEnv(new EnvVarBuilder()
                        .withName(envName)
                        .withNewValueFrom()
                            .withNewSecretKeyRef(secretKey, secretName, false)
                        .endValueFrom()
                        .build())
                .endContainer()
                .endSpec().endTemplate().endSpec());
        }

        /**
         * Add a raw {@link EnvVar} to the container.
         *
         * @param envVar the environment variable
         * @return this builder
         */
        public Builder withEnv(final EnvVar envVar) {
            return addModifier(db -> db.editSpec().editTemplate().editSpec()
                .editFirstContainer()
                    .addToEnv(envVar)
                .endContainer()
                .endSpec().endTemplate().endSpec());
        }

        /**
         * Add a raw deployment modifier for full flexibility.
         *
         * @param mod the deployment modifier
         * @return this builder
         */
        public Builder withModifier(final Consumer<DeploymentBuilder> mod) {
            return addModifier(mod);
        }

        /**
         * Deploy the MCP server with the configured options.
         */
        @Step("Deploy MCP server into namespace {namespace}")
        public void deploy() {
            LOGGER.info("Deploying MCP server into namespace {}", namespace);
            deployRbac(namespace);
            deployServer(namespace, image, modifier);
        }

        /**
         * Redeploy the MCP server — deletes the existing Deployment first.
         */
        @Step("Redeploy MCP server in namespace {namespace}")
        public void redeploy() {
            LOGGER.info("Redeploying MCP server in namespace {}", namespace);
            Deployment current = KubeResourceManager.get().kubeClient().getClient()
                .apps().deployments()
                .inNamespace(namespace).withName(Constants.MCP_NAME).get();
            if (current != null) {
                KubeResourceManager.get().deleteResourceWithWait(current);
            }
            deployServer(namespace, image, modifier);
        }

        private Builder addModifier(final Consumer<DeploymentBuilder> extra) {
            modifier = modifier == null ? extra : modifier.andThen(extra);
            return this;
        }
    }

    // --- Internal deployment methods ---

    @Step("Deploy MCP server RBAC into namespace {namespace}")
    static void deployRbac(final String namespace) {
        ServiceAccount sa = KubeTestUtils.configFromYaml(
            installFile("002-ServiceAccount.yaml"), ServiceAccount.class);
        KubeResourceManager.get().createOrUpdateResourceWithoutWait(new ServiceAccountBuilder(sa)
            .editMetadata().withNamespace(namespace).endMetadata()
            .build());

        ClusterRole cr = KubeTestUtils.configFromYaml(
            installFile("003-ClusterRole.yaml"), ClusterRole.class);
        KubeResourceManager.get().createOrUpdateResourceWithoutWait(cr);

        ClusterRoleBinding crb = KubeTestUtils.configFromYaml(
            installFile("004-ClusterRoleBinding.yaml"), ClusterRoleBinding.class);
        KubeResourceManager.get().createOrUpdateResourceWithoutWait(new ClusterRoleBindingBuilder(crb)
            .editFirstSubject().withNamespace(namespace).endSubject()
            .build());
    }

    @Step("Deploy MCP server Deployment + Service into namespace {namespace}")
    static void deployServer(final String namespace, final String image,
                              final Consumer<DeploymentBuilder> modifier) {
        Deployment deployment = KubeTestUtils.configFromYaml(
            installFile("005-Deployment.yaml"), Deployment.class);

        DeploymentBuilder db = new DeploymentBuilder(deployment)
            .editMetadata().withNamespace(namespace).endMetadata()
            .editSpec().editTemplate().editSpec()
                .editFirstContainer()
                    .withImage(image)
                    .withImagePullPolicy("IfNotPresent")
                .endContainer()
            .endSpec().endTemplate().endSpec();

        if (modifier != null) {
            modifier.accept(db);
        }

        KubeResourceManager.get().createOrUpdateResourceWithWait(db.build());

        Service service = KubeTestUtils.configFromYaml(
            installFile("006-Service.yaml"), Service.class);
        KubeResourceManager.get().createOrUpdateResourceWithoutWait(new ServiceBuilder(service)
            .editMetadata().withNamespace(namespace).endMetadata()
            .build());
    }

    private static File installFile(final String filename) {
        return new File(Constants.INSTALL_DIR, filename);
    }
}
