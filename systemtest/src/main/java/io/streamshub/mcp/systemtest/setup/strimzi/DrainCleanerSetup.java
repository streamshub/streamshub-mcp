/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest.setup.strimzi;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.admissionregistration.v1.ValidatingWebhookConfiguration;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBinding;
import io.fabric8.kubernetes.api.model.rbac.RoleBinding;
import io.qameta.allure.Step;
import io.skodjob.kubetest4j.resources.KubeResourceManager;
import io.skodjob.kubetest4j.security.CertAndKey;
import io.skodjob.kubetest4j.security.CertAndKeyBuilder;
import io.skodjob.kubetest4j.security.CertAndKeyFiles;
import io.skodjob.kubetest4j.utils.SecurityUtils;
import io.streamshub.mcp.systemtest.Constants;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.x509.GeneralName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Installs Strimzi Drain Cleaner from YAML manifests in
 * {@code dev/manifests/strimzi/drain-cleaner/certmanager/}.
 *
 * <p>Issuer and Certificate resources are skipped (cert-manager is not required).
 * A self-signed TLS certificate is generated using kubetest4j's {@link CertAndKeyBuilder}
 * and the webhook's {@code caBundle} is patched to trust it.
 */
public final class DrainCleanerSetup {

    private static final Logger LOGGER = LoggerFactory.getLogger(DrainCleanerSetup.class);

    private static final Path MANIFESTS_DIR = Path.of(Constants.DRAIN_CLEANER_MANIFESTS_DIR);

    /** Drain cleaner Deployment name. */
    public static final String DEPLOYMENT_NAME = Constants.DRAIN_CLEANER_NAME;

    private static final String TLS_SECRET_NAME = "strimzi-drain-cleaner";
    private static final Set<String> CERT_MANAGER_KINDS = Set.of("Issuer", "Certificate");

    private DrainCleanerSetup() {
    }

    /**
     * Deploy Strimzi Drain Cleaner with default configuration.
     *
     * @param namespace the target namespace
     */
    @Step("Deploy Strimzi Drain Cleaner")
    public static void deploy(final String namespace) {
        LOGGER.info("Installing Strimzi Drain Cleaner into namespace {}", namespace);

        String caBundle = createSelfSignedTlsSecret(namespace);

        List<HasMetadata> resources = loadManifests(MANIFESTS_DIR);

        try (AutoCloseable ignored = KubeResourceManager.get().openBatch()) {
            for (HasMetadata resource : resources) {
                if (resource instanceof Namespace) {
                    continue;
                }

                if (CERT_MANAGER_KINDS.contains(resource.getKind())) {
                    LOGGER.debug("Skipping {} resource (using self-signed certificate)", resource.getKind());
                    continue;
                }
                if (resource instanceof Namespaced) {
                    resource.getMetadata().setNamespace(namespace);
                }
                if (resource instanceof ClusterRoleBinding crb) {
                    crb.getSubjects().forEach(sbj -> sbj.setNamespace(namespace));
                } else if (resource instanceof RoleBinding rb) {
                    rb.getSubjects().forEach(sbj -> sbj.setNamespace(namespace));
                } else if (resource instanceof ValidatingWebhookConfiguration vwc) {
                    patchWebhookCaBundle(vwc, caBundle);
                    KubeResourceManager.get().createOrUpdateResourceWithoutWait(vwc);
                    continue;
                } else if (resource instanceof Deployment dep
                    && DEPLOYMENT_NAME.equals(dep.getMetadata().getName())) {
                    KubeResourceManager.get().createOrUpdateResourceWithWait(dep);
                    continue;
                }
                KubeResourceManager.get().createOrUpdateResourceWithoutWait(resource);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Generates a self-signed TLS certificate using kubetest4j's {@link CertAndKeyBuilder}
     * and creates the Kubernetes Secret the Drain Cleaner Deployment expects.
     *
     * @param namespace the target namespace
     * @return the base64-encoded certificate PEM for use as webhook {@code caBundle}
     */
    @Step("Create self-signed TLS secret for Drain Cleaner")
    private static String createSelfSignedTlsSecret(final String namespace) {
        try {
            CertAndKey certAndKey = CertAndKeyBuilder.rootCaCertBuilder()
                .withIssuerDn("CN=" + TLS_SECRET_NAME)
                .withSubjectDn("CN=" + TLS_SECRET_NAME)
                .withSanDnsNames(new ASN1Encodable[]{
                    new GeneralName(GeneralName.dNSName, TLS_SECRET_NAME),
                    new GeneralName(GeneralName.dNSName, TLS_SECRET_NAME + "." + namespace),
                    new GeneralName(GeneralName.dNSName, TLS_SECRET_NAME + "." + namespace + ".svc"),
                    new GeneralName(GeneralName.dNSName,
                        TLS_SECRET_NAME + "." + namespace + ".svc.cluster.local")
                })
                .build();

            CertAndKeyFiles pemFiles = SecurityUtils.exportToPemFiles(certAndKey);
            String certPem = Files.readString(Path.of(pemFiles.getCertPath()), StandardCharsets.UTF_8);
            String keyPem = Files.readString(Path.of(pemFiles.getKeyPath()), StandardCharsets.UTF_8);

            KubeResourceManager.get().createOrUpdateResourceWithoutWait(new SecretBuilder()
                .withNewMetadata()
                    .withName(TLS_SECRET_NAME)
                    .withNamespace(namespace)
                .endMetadata()
                .withType("kubernetes.io/tls")
                .addToStringData("tls.crt", certPem)
                .addToStringData("tls.key", keyPem)
                .build());

            LOGGER.info("Created self-signed TLS secret '{}' in namespace '{}'",
                TLS_SECRET_NAME, namespace);

            return Base64.getEncoder().encodeToString(certPem.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(
                "Failed to create self-signed TLS secret for Drain Cleaner", e);
        }
    }

    /**
     * Patches a {@link ValidatingWebhookConfiguration} to use the self-signed certificate.
     * Removes the cert-manager CA injection annotation and sets the {@code caBundle} directly.
     *
     * @param vwc      the webhook configuration to patch
     * @param caBundle base64-encoded certificate PEM
     */
    private static void patchWebhookCaBundle(final ValidatingWebhookConfiguration vwc,
                                             final String caBundle) {
        Map<String, String> annotations = vwc.getMetadata().getAnnotations();
        if (annotations != null) {
            annotations.remove("cert-manager.io/inject-ca-from");
        }
        vwc.getWebhooks().forEach(wh -> wh.getClientConfig().setCaBundle(caBundle));
        LOGGER.debug("Patched webhook caBundle with self-signed certificate");
    }

    /**
     * Load all YAML manifests from a directory.
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
