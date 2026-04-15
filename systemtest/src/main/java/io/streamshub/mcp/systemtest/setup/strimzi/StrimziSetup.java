/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest.setup.strimzi;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBinding;
import io.fabric8.kubernetes.api.model.rbac.RoleBinding;
import io.qameta.allure.Step;
import io.skodjob.kubetest4j.resources.KubeResourceManager;
import io.streamshub.mcp.systemtest.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Installs Strimzi operator from YAML manifests in {@code dev/manifests/strimzi/strimzi-operator/}.
 * Loads all YAML files, modifies namespace and RBAC, applies via {@link KubeResourceManager}.
 */
public final class StrimziSetup {

    private static final Logger LOGGER = LoggerFactory.getLogger(StrimziSetup.class);

    private static final Path OPERATOR_DIR =
        Path.of(Constants.STRIMZI_MANIFESTS_DIR, "strimzi-operator");

    /** Strimzi cluster operator Deployment name. */
    public static final String DEPLOYMENT_NAME = "strimzi-cluster-operator";

    private StrimziSetup() {
    }

    /**
     * Deploy Strimzi operator with default configuration.
     *
     * @param namespace the target namespace
     */
    @Step("Deploy Strimzi operator")
    public static void deploy(final String namespace) {
        deploy(namespace, null);
    }

    /**
     * Deploy Strimzi operator with an optional Deployment modifier.
     *
     * @param namespace          the target namespace
     * @param deploymentModifier optional consumer to modify the operator Deployment
     *                           (e.g., add env vars, change image)
     */
    @Step("Deploy Strimzi operator")
    public static void deploy(final String namespace, final Consumer<DeploymentBuilder> deploymentModifier) {
        LOGGER.info("Installing Strimzi operator into namespace {}", namespace);

        // Load all manifests
        List<HasMetadata> resources = loadManifests(OPERATOR_DIR);

        // Apply with namespace and RBAC modifications
        for (HasMetadata resource : resources) {
            if (resource instanceof Namespace) {
                continue; // Already created above
            }
            if (resource instanceof Namespaced) {
                resource.getMetadata().setNamespace(namespace);
            }
            if (resource instanceof ClusterRoleBinding crb) {
                crb.getSubjects().forEach(sbj -> sbj.setNamespace(namespace));
            } else if (resource instanceof RoleBinding rb) {
                rb.getSubjects().forEach(sbj -> sbj.setNamespace(namespace));
            } else if (resource instanceof Deployment dep
                && DEPLOYMENT_NAME.equals(dep.getMetadata().getName())) {
                if (deploymentModifier != null) {
                    DeploymentBuilder db = new DeploymentBuilder(dep);
                    deploymentModifier.accept(db);
                    resource = db.build();
                }
                // createResourceWithWait — DeploymentType handles readiness
                KubeResourceManager.get().createOrUpdateResourceWithWait(resource);
                continue;
            }
            KubeResourceManager.get().createOrUpdateResourceWithoutWait(resource);
        }
    }

    /**
     * Load all YAML manifests from a directory using {@link KubeResourceManager#readResourcesFromFile}.
     *
     * @param dir the directory containing YAML files
     * @return list of parsed Kubernetes resources
     */
    private static List<HasMetadata> loadManifests(final Path dir) {
        List<HasMetadata> resources = new LinkedList<>();
        try (var files = Files.list(dir)) {
            files.sorted()
                .filter(f -> f.toString().endsWith(".yaml"))
                .filter(f -> !f.getFileName().toString().equals("kustomization.yaml"))
                .forEach(file -> {
                    try {
                        resources.addAll(KubeResourceManager.get().readResourcesFromFile(file));
                    } catch (IOException e) {
                        throw new IllegalStateException("Failed to load manifest file: " + file, e);
                    }
                });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list manifest directory: " + dir, e);
        }
        return resources;
    }
}
