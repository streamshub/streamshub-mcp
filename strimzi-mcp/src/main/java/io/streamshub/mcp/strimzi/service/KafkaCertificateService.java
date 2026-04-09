/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import io.fabric8.kubernetes.api.model.Secret;
import io.quarkiverse.mcp.server.ToolCallException;
import io.streamshub.mcp.common.service.KubernetesResourceService;
import io.streamshub.mcp.common.util.InputUtils;
import io.streamshub.mcp.strimzi.config.StrimziConstants;
import io.streamshub.mcp.strimzi.dto.KafkaCertificateResponse;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.kafka.listener.GenericKafkaListener;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Service for reading TLS certificate metadata and listener authentication
 * configuration from Strimzi-managed Kubernetes secrets.
 */
@ApplicationScoped
public class KafkaCertificateService {

    private static final Logger LOG = Logger.getLogger(KafkaCertificateService.class);

    private static final String CLUSTER_CA_TYPE = "cluster-ca";
    private static final String CLIENTS_CA_TYPE = "clients-ca";
    private static final int SAN_GENERAL_NAME_INDEX = 1;

    @Inject
    KubernetesResourceService k8sService;

    @Inject
    KafkaService kafkaService;

    KafkaCertificateService() {
    }

    /**
     * Get TLS certificate metadata and listener authentication configuration
     * for a Kafka cluster, optionally filtered by listener name.
     *
     * @param namespace    the namespace, or null for auto-discovery
     * @param clusterName  the Kafka cluster name
     * @param listenerName optional listener name to filter results
     * @return certificate and authentication response
     */
    public KafkaCertificateResponse getCertificates(final String namespace, final String clusterName,
                                                    final String listenerName) {
        String ns = InputUtils.normalizeInput(namespace);
        String normalizedName = InputUtils.normalizeInput(clusterName);
        String normalizedListener = InputUtils.normalizeInput(listenerName);

        if (normalizedName == null) {
            throw new ToolCallException("Cluster name is required");
        }

        LOG.infof("Getting certificates for cluster=%s (namespace=%s, listener=%s)",
            normalizedName, ns != null ? ns : "auto",
            normalizedListener != null ? normalizedListener : "all");

        Kafka kafka = kafkaService.findKafkaCluster(ns, normalizedName);
        String resolvedNs = kafka.getMetadata().getNamespace();

        List<KafkaCertificateResponse.ListenerAuthInfo> listenerAuth =
            extractListenerAuthentication(kafka, normalizedListener);

        if (normalizedListener != null && listenerAuth.isEmpty()) {
            throw new ToolCallException(
                "Listener '" + normalizedListener + "' not found on cluster '" + normalizedName + "'");
        }

        List<KafkaCertificateResponse.CertificateInfo> certificates =
            fetchCertificateMetadata(resolvedNs, normalizedName);

        if (certificates.isEmpty() && listenerAuth.isEmpty()) {
            return KafkaCertificateResponse.empty(normalizedName, resolvedNs,
                "No certificate secrets found. Ensure the sensitive RBAC Role is configured.");
        }

        return KafkaCertificateResponse.of(normalizedName, resolvedNs, certificates, listenerAuth);
    }

    private List<KafkaCertificateResponse.CertificateInfo> fetchCertificateMetadata(
            final String namespace, final String clusterName) {

        List<KafkaCertificateResponse.CertificateInfo> result = new ArrayList<>();

        fetchAndParseCert(namespace, clusterName,
            StrimziConstants.Secrets.CLUSTER_CA_CERT_SUFFIX, CLUSTER_CA_TYPE, result);

        fetchAndParseCert(namespace, clusterName,
            StrimziConstants.Secrets.CLIENTS_CA_CERT_SUFFIX, CLIENTS_CA_TYPE, result);

        return result;
    }

    private void fetchAndParseCert(final String namespace, final String clusterName,
                                   final String secretSuffix, final String certType,
                                   final List<KafkaCertificateResponse.CertificateInfo> result) {
        String secretName = clusterName + secretSuffix;
        Secret secret = k8sService.getResource(Secret.class, namespace, secretName);

        if (secret == null) {
            LOG.debugf("Secret %s not found in namespace %s (RBAC may not be configured)",
                secretName, namespace);
            return;
        }

        if (!isStrimziSecret(secret, clusterName)) {
            LOG.debugf("Secret %s does not have expected strimzi.io/cluster label for cluster %s",
                secretName, clusterName);
            return;
        }

        Map<String, String> data = secret.getData();
        if (data == null || !data.containsKey(StrimziConstants.Secrets.CA_CRT_KEY)) {
            LOG.debugf("Secret %s does not contain key %s", secretName,
                StrimziConstants.Secrets.CA_CRT_KEY);
            return;
        }

        String encodedCert = data.get(StrimziConstants.Secrets.CA_CRT_KEY);
        List<X509Certificate> certs = parseCertificates(encodedCert, secretName);
        for (X509Certificate cert : certs) {
            result.add(buildCertificateInfo(secretName, certType, cert));
        }
    }

    private boolean isStrimziSecret(final Secret secret, final String clusterName) {
        Map<String, String> labels = secret.getMetadata().getLabels();
        if (labels == null) {
            return false;
        }
        return clusterName.equals(labels.get(ResourceLabels.STRIMZI_CLUSTER_LABEL));
    }

    private List<X509Certificate> parseCertificates(final String base64Encoded, final String secretName) {
        try {
            byte[] decoded = Base64.getDecoder().decode(base64Encoded);
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            @SuppressWarnings("unchecked")
            Collection<X509Certificate> certs = (Collection<X509Certificate>)
                factory.generateCertificates(new ByteArrayInputStream(decoded));
            return new ArrayList<>(certs);
        } catch (CertificateException | IllegalArgumentException e) {
            LOG.debugf("Failed to parse certificate from secret %s: %s", secretName, e.getMessage());
            return List.of();
        }
    }

    private KafkaCertificateResponse.CertificateInfo buildCertificateInfo(
            final String secretName, final String certType, final X509Certificate cert) {
        Instant notBefore = cert.getNotBefore().toInstant();
        Instant notAfter = cert.getNotAfter().toInstant();
        Instant now = Instant.now();
        long daysUntilExpiry = Duration.between(now, notAfter).toDays();
        boolean expired = now.isAfter(notAfter);

        List<String> sans = extractSans(cert);

        return KafkaCertificateResponse.CertificateInfo.of(
            secretName,
            certType,
            cert.getSubjectX500Principal().getName(),
            cert.getIssuerX500Principal().getName(),
            notBefore,
            notAfter,
            daysUntilExpiry,
            expired,
            sans.isEmpty() ? null : sans
        );
    }

    private List<String> extractSans(final X509Certificate cert) {
        try {
            Collection<List<?>> sans = cert.getSubjectAlternativeNames();
            if (sans == null) {
                return List.of();
            }
            return sans.stream()
                .map(san -> String.valueOf(san.get(SAN_GENERAL_NAME_INDEX)))
                .toList();
        } catch (CertificateParsingException e) {
            LOG.debugf("Failed to extract SANs: %s", e.getMessage());
            return List.of();
        }
    }

    private List<KafkaCertificateResponse.ListenerAuthInfo> extractListenerAuthentication(
            final Kafka kafka, final String listenerName) {
        if (kafka.getSpec() == null || kafka.getSpec().getKafka() == null
            || kafka.getSpec().getKafka().getListeners() == null) {
            return List.of();
        }

        return kafka.getSpec().getKafka().getListeners().stream()
            .filter(l -> listenerName == null || listenerName.equals(l.getName()))
            .map(this::buildListenerAuthInfo)
            .toList();
    }

    private KafkaCertificateResponse.ListenerAuthInfo buildListenerAuthInfo(
            final GenericKafkaListener listener) {
        String listenerName = listener.getName();
        String listenerType = listener.getType() != null
            ? listener.getType().toValue() : null;
        boolean tlsEnabled = listener.isTls();
        String authType = listener.getAuth() != null
            ? listener.getAuth().getType() : null;

        return KafkaCertificateResponse.ListenerAuthInfo.of(
            listenerName, listenerType, tlsEnabled, authType);
    }
}
