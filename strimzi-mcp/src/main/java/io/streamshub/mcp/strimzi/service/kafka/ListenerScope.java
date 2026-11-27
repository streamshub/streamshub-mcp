/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafka;

import io.streamshub.mcp.common.util.McpErrors;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaBootstrapResponse;
import io.streamshub.mcp.strimzi.dto.kafkauser.KafkaUserResponse;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.kafka.listener.GenericKafkaListener;
import io.strimzi.api.kafka.model.kafka.listener.KafkaListenerAuthenticationScramSha512;
import io.strimzi.api.kafka.model.kafka.listener.KafkaListenerAuthenticationTls;
import io.strimzi.api.kafka.model.user.KafkaUserScramSha512ClientAuthentication;
import io.strimzi.api.kafka.model.user.KafkaUserTlsClientAuthentication;
import io.strimzi.api.kafka.model.user.KafkaUserTlsExternalClientAuthentication;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Narrows connectivity data to one Kafka listener.
 *
 * <p>Used by {@code diagnose_kafka_connectivity} when a {@code listenerName} is given, so the
 * bootstrap servers, users and logs it reports belong to that listener only.</p>
 */
final class ListenerScope {

    private ListenerScope() {
    }

    /**
     * Finds a listener in the Kafka CR's spec.
     *
     * @param kafka        the Kafka cluster
     * @param listenerName the listener name
     * @return the listener
     * @throws io.quarkiverse.mcp.server.McpException invalid params naming the available listeners
     *                                                when the cluster has no listener of that name
     */
    static GenericKafkaListener resolve(final Kafka kafka, final String listenerName) {
        List<GenericKafkaListener> listeners = kafka.getSpec() != null && kafka.getSpec().getKafka() != null
            && kafka.getSpec().getKafka().getListeners() != null
            ? kafka.getSpec().getKafka().getListeners() : List.of();
        return listeners.stream()
            .filter(l -> listenerName.equals(l.getName()))
            .findFirst()
            .orElseThrow(() -> McpErrors.invalidParams(String.format(
                "Listener '%s' not found in Kafka cluster '%s'. Available listeners: %s",
                listenerName, kafka.getMetadata().getName(),
                listeners.isEmpty() ? "none" : String.join(", ",
                    listeners.stream().map(GenericKafkaListener::getName).toList()))));
    }

    /**
     * Keeps only the bootstrap addresses of the listener.
     *
     * @param response     the bootstrap servers of every listener, or null
     * @param listenerName the listener name
     * @return the listener's bootstrap servers, or null when the response is null
     */
    static KafkaBootstrapResponse bootstrapServers(final KafkaBootstrapResponse response, final String listenerName) {
        if (response == null || response.bootstrapServers() == null) {
            return response;
        }
        List<KafkaBootstrapResponse.BootstrapServerInfo> servers = response.bootstrapServers().stream()
            .filter(s -> listenerName.equals(s.listenerName()))
            .toList();
        return servers.isEmpty()
            ? KafkaBootstrapResponse.empty(response.namespace(), response.clusterName())
            : KafkaBootstrapResponse.of(response.namespace(), response.clusterName(), servers);
    }

    /**
     * Keeps only the users that can authenticate on the listener.
     *
     * <ul>
     *   <li>No authentication on the listener: no users, since any client connects anonymously.</li>
     *   <li>{@code tls}: users with {@code tls} or {@code tls-external} authentication.</li>
     *   <li>{@code scram-sha-512}: users with {@code scram-sha-512} authentication.</li>
     *   <li>{@code custom} (for example OAuth) or an unknown type: all users, since the server
     *       cannot tell which of them the custom mechanism accepts.</li>
     * </ul>
     *
     * @param users    the cluster's users, or null
     * @param listener the listener
     * @return the users for the listener, or null when {@code users} is null
     */
    static List<KafkaUserResponse> users(final List<KafkaUserResponse> users, final GenericKafkaListener listener) {
        if (users == null) {
            return null;
        }
        if (listener.getAuth() == null) {
            return List.of();
        }
        Set<String> userAuthTypes = switch (listener.getAuth().getType()) {
            case KafkaListenerAuthenticationTls.TYPE_TLS -> Set.of(
                KafkaUserTlsClientAuthentication.TYPE_TLS, KafkaUserTlsExternalClientAuthentication.TYPE_TLS_EXTERNAL);
            case KafkaListenerAuthenticationScramSha512.SCRAM_SHA_512 -> Set.of(
                KafkaUserScramSha512ClientAuthentication.TYPE_SCRAM_SHA_512);
            // custom (for example OAuth) or a type added later
            default -> null;
        };
        if (userAuthTypes == null) {
            return users;
        }
        // Set.of(...).contains(null) throws, and a user without authentication matches no type.
        return users.stream()
            .filter(u -> u.authentication() != null && userAuthTypes.contains(u.authentication()))
            .toList();
    }

    /**
     * Builds a log filter for the listener's lines.
     *
     * <p>Kafka names a Strimzi listener {@code <NAME>-<PORT>} in upper case (for example
     * {@code TLS-9093}) in socket-server and authentication logs, and its channel IDs read
     * {@code <local address>:<port>-<remote address>:<port>-<index>}. The filter matches either
     * form. It is a plain alternation, without flags or word boundaries, so the Kubernetes and Loki
     * providers apply it the same way. A listener port is 9092 or higher and at most 65535, so one
     * port is never a prefix of another in these forms.</p>
     *
     * <p>The OpenSearch provider matches a custom filter against analyzed terms, which split on
     * {@code -} and {@code :}, so there the filter finds no lines.</p>
     *
     * @param listener the listener
     * @return a regex for the listener's log lines
     */
    static String logFilter(final GenericKafkaListener listener) {
        int port = listener.getPort();
        return String.format("%s-%d|:%d-", listener.getName().toUpperCase(Locale.ROOT), port, port);
    }
}
