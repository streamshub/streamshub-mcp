/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest.setup.strimzi;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBinding;
import io.fabric8.kubernetes.api.model.rbac.RoleBinding;
import io.qameta.allure.Step;
import io.skodjob.kubetest4j.resources.KubeResourceManager;
import io.streamshub.mcp.systemtest.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * Installs Strimzi Drain Cleaner from YAML manifests in
 * {@code dev/manifests/strimzi/drain-cleaner/certmanager/}.
 *
 * <p>Handles cert-manager availability gracefully: when cert-manager is not installed,
 * Issuer and Certificate resources are skipped and a self-signed TLS Secret is created
 * via {@code keytool} as a fallback so the Deployment can start.
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

        boolean certManagerAvailable = isCertManagerAvailable();
        if (!certManagerAvailable) {
            LOGGER.warn("cert-manager not available — skipping Issuer/Certificate resources "
                + "and creating self-signed TLS secret as fallback");
            createSelfSignedTlsSecret(namespace);
        }

        List<HasMetadata> resources = loadManifests(MANIFESTS_DIR);

        for (HasMetadata resource : resources) {
            if (resource instanceof Namespace) {
                continue;
            }
            String kind = resource.getKind();
            if (!certManagerAvailable && CERT_MANAGER_KINDS.contains(kind)) {
                LOGGER.debug("Skipping {} resource (cert-manager not available)", kind);
                continue;
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
                KubeResourceManager.get().createOrUpdateResourceWithWait(dep);
                continue;
            }
            KubeResourceManager.get().createOrUpdateResourceWithoutWait(resource);
        }
    }

    private static boolean isCertManagerAvailable() {
        try {
            return KubeResourceManager.get().kubeClient().getClient()
                .supports("cert-manager.io/v1", "Certificate");
        } catch (Exception e) {
            LOGGER.debug("Failed to check cert-manager availability", e);
            return false;
        }
    }

    /**
     * Generates a self-signed TLS certificate using {@code keytool} (ships with every JDK)
     * and creates the Kubernetes Secret the Drain Cleaner Deployment expects.
     */
    @Step("Create self-signed TLS secret for Drain Cleaner")
    private static void createSelfSignedTlsSecret(final String namespace) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("drain-cleaner-cert");
            Path keystorePath = tempDir.resolve("keystore.p12");
            String password = "changeit";

            Process keytool = new ProcessBuilder(
                "keytool", "-genkeypair",
                "-alias", "drain-cleaner",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "3650",
                "-dname", "CN=" + TLS_SECRET_NAME,
                "-storetype", "PKCS12",
                "-keystore", keystorePath.toString(),
                "-storepass", password,
                "-keypass", password)
                .redirectErrorStream(true)
                .start();

            int exitCode = keytool.waitFor();
            if (exitCode != 0) {
                String output = new String(keytool.getInputStream().readAllBytes());
                throw new IllegalStateException("keytool failed (exit " + exitCode + "): " + output);
            }

            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (InputStream fis = Files.newInputStream(keystorePath)) {
                ks.load(fis, password.toCharArray());
            }

            X509Certificate cert = (X509Certificate) ks.getCertificate("drain-cleaner");
            PrivateKey key = (PrivateKey) ks.getKey("drain-cleaner", password.toCharArray());

            String certPem = toPem("CERTIFICATE", cert.getEncoded());
            String keyPem = toPem("RSA PRIVATE KEY", key.getEncoded());

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
        } catch (Exception e) {
            throw new IllegalStateException(
                "Failed to create self-signed TLS secret for Drain Cleaner", e);
        } finally {
            cleanupTempDir(tempDir);
        }
    }

    private static String toPem(final String type, final byte[] der) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + base64 + "\n-----END " + type + "-----\n";
    }

    private static void cleanupTempDir(final Path dir) {
        if (dir == null) {
            return;
        }
        try (var entries = Files.list(dir)) {
            entries.forEach(f -> {
                try {
                    Files.deleteIfExists(f);
                } catch (IOException ignored) {
                    // best effort
                }
            });
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {
            // best effort
        }
    }

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
