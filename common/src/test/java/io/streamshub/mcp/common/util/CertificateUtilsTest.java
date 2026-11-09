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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CertificateUtils}.
 */
class CertificateUtilsTest {

    // Generated with keytool; "valid" expires in year 2126, "expired" expired in January 2020.
    private static final String VALID_CERT_B64 =
        "LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0tDQpNSUlCT0RDQjRLQURBZ0VDQWdrQWx6cjZWdmlxeW1Fd0NnWUlLb1pJemowRUF3TXdFREVP"
            + "TUF3R0ExVUVBeE1GDQpkbUZzYVdRd0lCY05Nall4TVRBek1UUXhPRFUzV2hnUE1qRXlOakV3TVRBeE5ERTROVGRhTUJBeERqQU1CZ05WDQpC"
            + "QU1UQlhaaGJHbGtNRmt3RXdZSEtvWkl6ajBDQVFZSUtvWkl6ajBEQVFjRFFnQUVuY2VNZDlNMGo2a2h0QjhrDQprQTNkNnRwWXI0K24wM3h5"
            + "YTgwcDFMbGdwRHZPWmczVGkrTVgrNkt4cklabCtrT0pQT2IvVlFkSWgrUnRQd3QyDQphMzVJZjZNaE1COHdIUVlEVlIwT0JCWUVGRHptVEUv"
            + "OTJJQW54WGY1L0pjMmJlMEpaRDhDTUFvR0NDcUdTTTQ5DQpCQU1EQTBjQU1FUUNJQS9qV1hteVMrRmg5WDU4MnplSGVYR2RMbE4wTUV6K1Rw"
            + "ckt2SmhCUTIyeEFpQXRzd0FXDQpYMVlmREc3ZzlaUGgydFExWnFlQjJlWU0vcHNtck9zWTh5elBCdz09DQotLS0tLUVORCBDRVJUSUZJQ0FU"
            + "RS0tLS0tDQo=";
    private static final String EXPIRED_CERT_B64 =
        "LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0tDQpNSUlCT2pDQjRxQURBZ0VDQWdrQTE1QzAzdHlyVjFjd0NnWUlLb1pJemowRUF3TXdFakVR"
            + "TUE0R0ExVUVBeE1IDQpaWGh3YVhKbFpEQWVGdzB4T1RFeU16RXlNekF3TURCYUZ3MHlNREF4TXpBeU16QXdNREJhTUJJeEVEQU9CZ05WDQpC"
            + "QU1UQjJWNGNHbHlaV1F3V1RBVEJnY3Foa2pPUFFJQkJnZ3Foa2pPUFFNQkJ3TkNBQVJEWlVuZ3doSXRTamwvDQpUUE82TDg0Y0JIMndhcEFx"
            + "TmdiV3k1L3BVdVNDQWlDOFhINmFKQ21hbS9MZkg5Y0tKOEQ4MHJPeW9LTHVWS042DQo3dWdSWnhuam95RXdIekFkQmdOVkhRNEVGZ1FVTU9I"
            + "NUFTWDZFc1FjSzJ6dEZIWG9FSDVPZDZJd0NnWUlLb1pJDQp6ajBFQXdNRFJ3QXdSQUlnTEVQTjBuNzFpZVdlL2xyQ1lNcEVoT0VvdXJCa05j"
            + "a0V2N0RwNHdsVlRrc0NJQ3RSDQpIOGJHRXFJRVhTK2ZFaDhyOFhUTHgvZ0pWa21BRXJpV3ZJbFI5R0xLDQotLS0tLUVORCBDRVJUSUZJQ0FU"
            + "RS0tLS0tDQo=";

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
    void parseCertificatesReadsBase64EncodedPem() {
        List<X509Certificate> certs = CertificateUtils.parseCertificates(VALID_CERT_B64);

        assertEquals(1, certs.size());
        assertFalse(CertificateUtils.isExpired(certs.getFirst()));
        assertTrue(CertificateUtils.calculateDaysUntilExpiry(certs.getFirst()) > 30);
    }

    @Test
    void parseCertificatesDetectsExpiredCertificate() {
        List<X509Certificate> certs = CertificateUtils.parseCertificates(EXPIRED_CERT_B64);

        assertEquals(1, certs.size());
        assertTrue(CertificateUtils.isExpired(certs.getFirst()));
        assertTrue(CertificateUtils.calculateDaysUntilExpiry(certs.getFirst()) < 0);
    }

    @Test
    void parseCertificatesReturnsEmptyForInvalidInput() {
        assertTrue(CertificateUtils.parseCertificates("!!not-base64!!").isEmpty());
        assertTrue(CertificateUtils.parseCertificates("bm90IGEgY2VydA==").isEmpty());
    }

    @Test
    void daysUntilExpiryIsNegativeForLongExpiredCert() {
        X509Certificate cert = mock(X509Certificate.class);
        when(cert.getNotAfter()).thenReturn(Date.from(Instant.now().minus(10, ChronoUnit.DAYS)));

        assertTrue(CertificateUtils.calculateDaysUntilExpiry(cert) < 0);
    }
}
