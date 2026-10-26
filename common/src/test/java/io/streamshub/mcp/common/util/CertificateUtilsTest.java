/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.util;

import org.junit.jupiter.api.Test;

import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CertificateUtils}.
 */
class CertificateUtilsTest {

    CertificateUtilsTest() {
    }

    @Test
    void daysUntilExpiryIsNegativeForCertExpiredWithinLastDay() {
        X509Certificate cert = mock(X509Certificate.class);
        when(cert.getNotAfter()).thenReturn(Date.from(Instant.now().minus(6, ChronoUnit.HOURS)));

        assertEquals(-1, CertificateUtils.calculateDaysUntilExpiry(cert));
    }

    @Test
    void daysUntilExpiryIsZeroForCertValidLessThanOneDay() {
        X509Certificate cert = mock(X509Certificate.class);
        when(cert.getNotAfter()).thenReturn(Date.from(Instant.now().plus(6, ChronoUnit.HOURS)));

        assertEquals(0, CertificateUtils.calculateDaysUntilExpiry(cert));
    }

    @Test
    void daysUntilExpiryIsPositiveForFutureCert() {
        X509Certificate cert = mock(X509Certificate.class);
        when(cert.getNotAfter()).thenReturn(Date.from(Instant.now().plus(30, ChronoUnit.DAYS)));

        assertTrue(CertificateUtils.calculateDaysUntilExpiry(cert) > 0);
    }

    @Test
    void daysUntilExpiryIsNegativeForLongExpiredCert() {
        X509Certificate cert = mock(X509Certificate.class);
        when(cert.getNotAfter()).thenReturn(Date.from(Instant.now().minus(10, ChronoUnit.DAYS)));

        assertTrue(CertificateUtils.calculateDaysUntilExpiry(cert) < 0);
    }
}
