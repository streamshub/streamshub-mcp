/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.util;

import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

/**
 * Utility methods for parsing and inspecting X.509 certificates
 * from base64-encoded Kubernetes Secret data.
 */
public final class CertificateUtils {

    private static final Logger LOG = Logger.getLogger(CertificateUtils.class);

    private CertificateUtils() {
    }

    /**
     * Parse X.509 certificates from a base64-encoded PEM string.
     *
     * @param base64Encoded the base64-encoded certificate data from a Kubernetes Secret
     * @return list of parsed certificates, or empty list on failure
     */
    public static List<X509Certificate> parseCertificates(final String base64Encoded) {
        try {
            byte[] decoded = Base64.getDecoder().decode(base64Encoded);
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            @SuppressWarnings("unchecked")
            Collection<X509Certificate> certs = (Collection<X509Certificate>)
                factory.generateCertificates(new ByteArrayInputStream(decoded));
            return new ArrayList<>(certs);
        } catch (CertificateException | IllegalArgumentException e) {
            LOG.debugf("Failed to parse certificate: %s", e.getMessage());
            return List.of();
        }
    }

    /**
     * Calculate the number of days until a certificate expires.
     * Returns a negative value if the certificate has already expired.
     *
     * @param cert the X.509 certificate
     * @return days until expiry (negative if expired)
     */
    public static long calculateDaysUntilExpiry(final X509Certificate cert) {
        Instant notAfter = cert.getNotAfter().toInstant();
        return Duration.between(Instant.now(), notAfter).toDays();
    }

    /**
     * Check whether a certificate has expired.
     *
     * @param cert the X.509 certificate
     * @return true if the certificate has expired
     */
    public static boolean isExpired(final X509Certificate cert) {
        return Instant.now().isAfter(cert.getNotAfter().toInstant());
    }
}
