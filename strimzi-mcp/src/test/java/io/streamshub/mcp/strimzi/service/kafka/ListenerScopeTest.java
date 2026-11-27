/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafka;

import io.streamshub.mcp.strimzi.dto.kafkauser.KafkaUserResponse;
import io.strimzi.api.kafka.model.kafka.listener.GenericKafkaListener;
import io.strimzi.api.kafka.model.kafka.listener.GenericKafkaListenerBuilder;
import io.strimzi.api.kafka.model.kafka.listener.KafkaListenerAuthenticationCustomBuilder;
import io.strimzi.api.kafka.model.kafka.listener.KafkaListenerAuthenticationScramSha512;
import io.strimzi.api.kafka.model.kafka.listener.KafkaListenerAuthenticationTls;
import io.strimzi.api.kafka.model.kafka.listener.KafkaListenerType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ListenerScope}.
 */
class ListenerScopeTest {

    private static final List<KafkaUserResponse> USERS = List.of(
        user("tls-user", "tls"),
        user("external-user", "tls-external"),
        user("scram-user", "scram-sha-512"),
        user("no-auth-user", null));

    ListenerScopeTest() {
    }

    @Test
    void testUsersForListenerWithoutAuthentication() {
        assertEquals(List.of(), names(ListenerScope.users(USERS, listener("plain", 9092))));
    }

    @Test
    void testUsersForTlsListener() {
        GenericKafkaListener tls = listener("tls", 9093);
        tls.setAuth(new KafkaListenerAuthenticationTls());
        assertEquals(List.of("tls-user", "external-user"), names(ListenerScope.users(USERS, tls)));
    }

    @Test
    void testUsersForScramListener() {
        GenericKafkaListener scram = listener("scram", 9094);
        scram.setAuth(new KafkaListenerAuthenticationScramSha512());
        assertEquals(List.of("scram-user"), names(ListenerScope.users(USERS, scram)));
    }

    @Test
    void testUsersForCustomListenerKeepsAll() {
        GenericKafkaListener oauth = listener("oauth", 9095);
        oauth.setAuth(new KafkaListenerAuthenticationCustomBuilder().withSasl(true).build());
        assertEquals(4, ListenerScope.users(USERS, oauth).size());
    }

    @Test
    void testLogFilterMatchesListenerNameAndChannelPort() {
        String regex = ListenerScope.logFilter(listener("tls", 9093));
        // A plain alternation, so Loki (RE2) applies it exactly as Java regex does.
        assertEquals("TLS-9093|:9093-", regex);
        Pattern filter = Pattern.compile(regex);

        assertTrue(filter.matcher("[SocketServer listenerType=BROKER, nodeId=0] Failed authentication with "
            + "/10.0.0.7 (channelId=10.244.0.5:9093-10.244.0.1:45678-3)").find());
        assertTrue(filter.matcher("Created data-plane acceptor and processors for endpoint : "
            + "ListenerName(TLS-9093)").find());
        assertFalse(filter.matcher("channelId=10.244.0.5:9092-10.244.0.1:45678-3").find());
        assertFalse(filter.matcher("ListenerName(PLAIN-9092)").find());
    }

    private static GenericKafkaListener listener(final String name, final int port) {
        return new GenericKafkaListenerBuilder()
            .withName(name).withPort(port).withType(KafkaListenerType.INTERNAL).withTls(port != 9092).build();
    }

    private static KafkaUserResponse user(final String name, final String authentication) {
        return new KafkaUserResponse(name, "kafka", "my-cluster", authentication,
            null, null, null, null, null, null, null, null, null);
    }

    private static List<String> names(final List<KafkaUserResponse> users) {
        return users.stream().map(KafkaUserResponse::name).toList();
    }
}
