/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * Response containing TLS certificate metadata and listener authentication
 * configuration for a Kafka cluster.
 *
 * @param clusterName            the Kafka cluster name
 * @param namespace              the Kubernetes namespace
 * @param certificates           certificate metadata from Strimzi-managed secrets
 * @param listenerAuthentication authentication configuration per listener
 * @param message                a human-readable message describing the result
 * @param timestamp              the time this result was generated
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaCertificateResponse(
    @JsonProperty("cluster_name") String clusterName,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("certificates") List<CertificateInfo> certificates,
    @JsonProperty("listener_authentication") List<ListenerAuthInfo> listenerAuthentication,
    @JsonProperty("message") String message,
    @JsonProperty("timestamp") Instant timestamp
) {

    /**
     * Creates a successful response with certificate and listener authentication data.
     *
     * @param clusterName            the Kafka cluster name
     * @param namespace              the Kubernetes namespace
     * @param certificates           certificate metadata list
     * @param listenerAuthentication listener authentication info list
     * @return a new KafkaCertificateResponse
     */
    public static KafkaCertificateResponse of(final String clusterName, final String namespace,
                                              final List<CertificateInfo> certificates,
                                              final List<ListenerAuthInfo> listenerAuthentication) {
        return new KafkaCertificateResponse(
            clusterName,
            namespace,
            certificates,
            listenerAuthentication,
            String.format("Found %d certificates and %d listeners for cluster '%s'",
                certificates.size(), listenerAuthentication.size(), clusterName),
            Instant.now()
        );
    }

    /**
     * Creates an empty response when no certificate data is available.
     *
     * @param clusterName the Kafka cluster name
     * @param namespace   the Kubernetes namespace
     * @param message     explanation of why no data was returned
     * @return an empty KafkaCertificateResponse
     */
    public static KafkaCertificateResponse empty(final String clusterName, final String namespace,
                                                 final String message) {
        return new KafkaCertificateResponse(
            clusterName,
            namespace,
            List.of(),
            List.of(),
            message,
            Instant.now()
        );
    }

    /**
     * TLS certificate metadata extracted from a Strimzi-managed secret.
     * Contains only metadata — never private keys or raw certificate bodies.
     *
     * @param secretName     the Kubernetes secret name
     * @param type           the certificate type (e.g., "cluster-ca", "clients-ca")
     * @param subject        the certificate subject DN
     * @param issuer         the certificate issuer DN
     * @param notBefore      the certificate validity start time
     * @param notAfter       the certificate expiry time
     * @param daysUntilExpiry days remaining until the certificate expires
     * @param expired        whether the certificate has expired
     * @param san            Subject Alternative Names
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CertificateInfo(
        @JsonProperty("secret_name") String secretName,
        @JsonProperty("type") String type,
        @JsonProperty("subject") String subject,
        @JsonProperty("issuer") String issuer,
        @JsonProperty("not_before") Instant notBefore,
        @JsonProperty("not_after") Instant notAfter,
        @JsonProperty("days_until_expiry") long daysUntilExpiry,
        @JsonProperty("expired") boolean expired,
        @JsonProperty("san") List<String> san
    ) {

        /**
         * Creates a certificate info from the given parameters.
         *
         * @param secretName     the secret name
         * @param type           the certificate type
         * @param subject        the subject DN
         * @param issuer         the issuer DN
         * @param notBefore      the validity start
         * @param notAfter       the expiry time
         * @param daysUntilExpiry days until expiry
         * @param expired        whether expired
         * @param san            the SANs
         * @return a new CertificateInfo
         */
        public static CertificateInfo of(final String secretName, final String type,
                                         final String subject, final String issuer,
                                         final Instant notBefore, final Instant notAfter,
                                         final long daysUntilExpiry, final boolean expired,
                                         final List<String> san) {
            return new CertificateInfo(secretName, type, subject, issuer,
                notBefore, notAfter, daysUntilExpiry, expired, san);
        }
    }

    /**
     * Authentication configuration for a Kafka listener.
     *
     * @param listenerName       the listener name
     * @param listenerType       the listener type (e.g., internal, route, loadbalancer)
     * @param tlsEnabled         whether TLS is enabled on this listener
     * @param authenticationType the authentication type (e.g., scram-sha-512, tls, oauth, or null)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ListenerAuthInfo(
        @JsonProperty("listener_name") String listenerName,
        @JsonProperty("listener_type") String listenerType,
        @JsonProperty("tls_enabled") boolean tlsEnabled,
        @JsonProperty("authentication_type") String authenticationType
    ) {

        /**
         * Creates a listener auth info from the given parameters.
         *
         * @param listenerName       the listener name
         * @param listenerType       the listener type
         * @param tlsEnabled         whether TLS is enabled
         * @param authenticationType the auth type
         * @return a new ListenerAuthInfo
         */
        public static ListenerAuthInfo of(final String listenerName, final String listenerType,
                                          final boolean tlsEnabled, final String authenticationType) {
            return new ListenerAuthInfo(listenerName, listenerType, tlsEnabled, authenticationType);
        }
    }
}
