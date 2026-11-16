/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.guardrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the pattern rules and the JSON tree walk of {@link Redactor}.
 */
class RedactorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CRIO_ID = "2cfc7df7a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c";
    private static final String DIGEST = "b2281f0e9c7d6a5b4c3d2e1f0a9b8c7d6e5f4a3b2c1d0e9f8a7b6c5d4e3f2a1b";
    private static final String KAFKA_IMAGE = "quay.io/strimzi/kafka@sha256:" + DIGEST;
    private static final String BUILD_IMAGE =
        "image-registry.openshift-image-registry.svc:5000/strimzi-kafka/mcp-connect-build@sha256:" + DIGEST;
    private static final String JAVA_HASH_MESSAGE = "[ControllerApis nodeId=8] Unexpected error handling request"
        + " RequestHeader(apiKey=BROKER_HEARTBEAT, apiVersion=1, clientId=raft-client-3, correlationId=42)"
        + " -- builder=org.apache.kafka.common.security.authenticator.DefaultKafkaPrincipalBuilder@6a38a832])";

    private final Redactor redactor = new Redactor(true, List.of());

    RedactorTest() {
    }

    private static JsonNode tree(final String json) throws Exception {
        return MAPPER.readTree(json);
    }

    /**
     * Builds a one-line OpenShift Logging (ViaQ) record as Loki returns it.
     *
     * @param image   the container image reference
     * @param message the log message
     * @return the record as a single line of JSON
     */
    private static String viaqRecord(final String image, final String message) {
        return "{\"@timestamp\":\"2026-11-13T11:15:59.607345973Z\",\"hostname\":\"worker-2\","
            + "\"kubernetes\":{\"container_id\":\"cri-o://" + CRIO_ID + "\","
            + "\"container_image\":\"" + image + "\",\"container_name\":\"kafka\","
            + "\"labels\":{\"strimzi.io/cluster\":\"mcp-cluster\"},\"namespace_name\":\"strimzi-kafka\","
            + "\"pod_name\":\"mcp-cluster-controller-np-8\"},\"level\":\"error\",\"message\":\"" + message + "\"}";
    }

    @Test
    void connectionStringRuleKeepsViaqRecordWithLaterAtSign() {
        String out = redactor.redactText(viaqRecord(KAFKA_IMAGE, JAVA_HASH_MESSAGE));

        assertTrue(out.contains("\"container_image\":\"" + KAFKA_IMAGE), out);
        assertTrue(out.contains("\"pod_name\":\"mcp-cluster-controller-np-8\""), out);
        assertTrue(out.contains("\"level\":\"error\""), out);
        assertTrue(out.contains("DefaultKafkaPrincipalBuilder@6a38a832])"), out);
        assertFalse(out.contains("[REDACTED]@"), out);
    }

    @Test
    void connectionStringRuleKeepsDigestPinnedBuildImage() {
        String out = redactor.redactText(viaqRecord(BUILD_IMAGE, "Kafka Connect started"));

        assertTrue(out.contains(BUILD_IMAGE), out);
        assertFalse(out.contains("[REDACTED]@"), out);
    }

    @Test
    void connectionStringRuleRedactsCredentialsInsideViaqRecord() {
        String out = redactor.redactText(viaqRecord(KAFKA_IMAGE,
            "Failed to connect to jdbc:postgresql://admin:s3cret@db.example.com:5432/app"));

        assertTrue(out.contains("jdbc:postgresql://[REDACTED]@db.example.com:5432/app"), out);
        assertTrue(out.contains(KAFKA_IMAGE), out);
        assertTrue(out.contains("\"pod_name\":\"mcp-cluster-controller-np-8\""), out);
        assertFalse(out.contains("s3cret"), out);
    }

    @Test
    void connectionStringRuleKeepsListenerUrlFollowedByEmail() {
        String text = "advertised.listeners=PLAINTEXT://broker:9092, user alice@example.com";

        assertEquals(text, redactor.redactText(text));
    }

    @Test
    void connectionStringRuleKeepsUrlWithPortAndAtInPath() {
        String text = "https://registry.example.com:443/@scope/pkg";

        assertEquals(text, redactor.redactText(text));
    }

    @Test
    void connectionStringRuleRedactsPasswordWithColonAndSubDelims() {
        assertEquals("amqp://[REDACTED]@broker:5672", redactor.redactText("amqp://svc-user:p:a$s!w0rd@broker:5672"));
    }

    @Test
    void connectionStringRuleRedactsPercentEncodedPassword() {
        assertEquals("mongodb://[REDACTED]@mongo:27017/db",
            redactor.redactText("mongodb://app:pa%2Fss%40word@mongo:27017/db"));
    }

    @Test
    void connectionStringRuleRedactsEmailUsername() {
        assertEquals("mongodb://[REDACTED]@mongo:27017/db",
            redactor.redactText("mongodb://alice@example.com:s3cret@mongo:27017/db"));
    }

    @Test
    void base64RuleKeepsSha1CertHashAnnotation() throws Exception {
        JsonNode node = tree("{\"annotations\":{\"strimzi.io/server-cert-hash\":\"3f786850e387550fdab836ed7e6dc881de23001b\"}}");

        assertFalse(redactor.redactTree(node));
    }

    @Test
    void base64RuleKeepsCrioContainerId() {
        String text = "cri-o://" + CRIO_ID;

        assertEquals(text, redactor.redactText(text));
    }

    @Test
    void base64RuleKeepsUpperCaseHex() {
        String text = "3F786850E387550FDAB836ED7E6DC881DE23001B";

        assertEquals(text, redactor.redactText(text));
    }

    @Test
    void base64RuleKeepsMavenArtifactUrl() {
        String text = "https://repo.maven.apache.org/maven2/org/apache/camel/kafkaconnector/"
            + "camel-timer-source-kafka-connector/4.18.0/camel-timer-source-kafka-connector-4.18.0-package.tar.gz";

        assertEquals(text, redactor.redactText(text));
    }

    @Test
    void base64RuleKeepsJvmInternalClassName() {
        String text = "java.lang.NoClassDefFoundError: org/apache/logging/log4j/core/appender/ConsoleAppender";

        assertEquals(text, redactor.redactText(text));
    }

    @Test
    void viaqRecordPassesThroughUnchanged() {
        String record = viaqRecord(KAFKA_IMAGE, JAVA_HASH_MESSAGE);

        assertEquals(record, redactor.redactText(record));
    }

    @Test
    void redactedCopyKeepsViaqRecord() {
        String record = viaqRecord(KAFKA_IMAGE, JAVA_HASH_MESSAGE);

        assertEquals(record, redactor.redactedCopy(MAPPER, Map.of("logs", record)).get("logs").asText());
    }

    @Test
    void base64RuleRedactsBareBase64Secret() {
        assertEquals("Loaded key [REDACTED] from vault",
            redactor.redactText("Loaded key c2VjcmV0LXZhbHVlLXRoYXQtaXMtbG9uZy1lbm91Z2gtdG8tbWF0Y2g= from vault"));
    }

    @Test
    void base64RuleRedactsBase64WithSlashAndPlus() {
        assertEquals("value [REDACTED] end",
            redactor.redactText("value Zm9vYmFy+/K3lM2nP8qR4sT6uV0wX1yZ3aB5cD7eF9g= end"));
    }

    @Test
    void base64RuleRedactsValueWithSingleLeadingSegment() {
        assertEquals("data [REDACTED] end",
            redactor.redactText("data 9j/4AAQSkZJRgABAQEASABIAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQ end"));
    }

    @Test
    void connectionStringRuleIsLineLocalInMultiLineLogs() {
        String[] lines = redactor.redactText("url=postgresql://u:pw@db:5432/x\n" + JAVA_HASH_MESSAGE).split("\n");

        assertEquals("url=postgresql://[REDACTED]@db:5432/x", lines[0]);
        assertEquals(JAVA_HASH_MESSAGE, lines[1]);
    }

    @Test
    void connectionStringRuleDoesNotCrossNewline() {
        String text = "tcp://alice:abc\ndef@host";

        assertEquals(text, redactor.redactText(text));
    }

    @Test
    void base64RuleStaysLinearOnMegabyteOfHex() {
        String text = "0123456789abcdef".repeat(65_536);

        assertEquals(text, assertTimeoutPreemptively(Duration.ofSeconds(5), () -> redactor.redactText(text)));
    }

    @Test
    void apiKeyRuleKeepsKafkaRequestType() {
        String text = "RequestHeader(apiKey=BROKER_HEARTBEAT, apiVersion=1, clientId=raft-client-3)";

        assertEquals(text, redactor.redactText(text));
    }

    @Test
    void apiKeyRuleKeepsRequestTypeAtEndOfText() {
        assertEquals("apiKey=FETCH", redactor.redactText("apiKey=FETCH"));
    }

    @Test
    void apiKeyRuleRedactsMixedCaseValue() {
        assertEquals("apiKey=[REDACTED] next", redactor.redactText("apiKey=Ab3dEf9hIjK2 next"));
    }

    @Test
    void apiKeyRuleRedactsUpperCaseValueWithDigits() {
        assertEquals("api_key=[REDACTED] next", redactor.redactText("api_key=ABCD1234EFGH next"));
    }

    @Test
    void apiKeyRuleRedactsUpperCasePrefixedKey() {
        assertEquals("apikey=[REDACTED] next", redactor.redactText("apikey: NRAK-ABCDEFGHIJ next"));
        assertEquals("api-key=[REDACTED] next", redactor.redactText("api-key=SG.ABCDEFGH next"));
    }

    @Test
    void apiKeyRuleStaysLinearOnMegabyteOfUpperCase() {
        String text = "apiKey=" + "A_".repeat(500_000);

        assertEquals(text, assertTimeoutPreemptively(Duration.ofSeconds(5), () -> redactor.redactText(text)));
    }

    @Test
    void redactTreeRedactsStringArrayUnderSensitiveKey() throws Exception {
        JsonNode node = tree("{\"secrets\":[\"a\",\"b\"]}");

        assertTrue(redactor.redactTree(node));
        assertEquals("{\"secrets\":[\"[REDACTED]\",\"[REDACTED]\"]}", node.toString());
    }

    @Test
    void redactTreeLeavesArrayUnderPlainKey() throws Exception {
        JsonNode node = tree("{\"hosts\":[\"a\",\"b\"]}");

        assertFalse(redactor.redactTree(node));
        assertEquals("{\"hosts\":[\"a\",\"b\"]}", node.toString());
    }

    @Test
    void redactTreeAppliesPatternRulesInsideArrays() throws Exception {
        JsonNode node = tree("{\"lines\":[\"Authorization: Bearer abc.def\"],\"top\":[\"Bearer xyz\"]}");

        assertTrue(redactor.redactTree(node));
        assertEquals("Authorization: Bearer [REDACTED]", node.get("lines").get(0).asText());
        assertEquals("Bearer [REDACTED]", node.get("top").get(0).asText());
    }

    @Test
    void redactTreeRedactsObjectsNestedInArraysByTheirOwnKeys() throws Exception {
        JsonNode node = tree("[{\"name\":\"x\",\"password\":\"p\"}]");

        assertTrue(redactor.redactTree(node));
        assertEquals("[{\"name\":\"x\",\"password\":\"[REDACTED]\"}]", node.toString());
    }

    @Test
    void redactTreeReturnsFalseWhenNothingChanged() throws Exception {
        JsonNode node = tree("{\"name\":\"x\",\"nested\":{\"count\":1,\"items\":[\"a\"]}}");

        assertFalse(redactor.redactTree(node));
    }

    @Test
    void redactTreeKeepsProviderReferenceInSensitiveArray() throws Exception {
        JsonNode node = tree("{\"secrets\":[\"${secrets:ns/name:key}\"]}");

        assertFalse(redactor.redactTree(node));
        assertEquals("${secrets:ns/name:key}", node.get("secrets").get(0).asText());
    }

    @Test
    void redactTreeRedactsNestedArrayInsideSensitiveArray() throws Exception {
        JsonNode node = tree("{\"secrets\":[[\"a\"]]}");

        assertTrue(redactor.redactTree(node));
        assertEquals("{\"secrets\":[[\"[REDACTED]\"]]}", node.toString());
    }

    @Test
    void redactTreeRedactsSecretKeysInTopicConfiguration() throws Exception {
        JsonNode node = tree("{\"configuration\":{\"retention.ms\":60000,\"custom.api.key\":\"abc123\"}}");

        assertTrue(redactor.redactTree(node));
        assertEquals("[REDACTED]", node.get("configuration").get("custom.api.key").asText());
        assertEquals(60000, node.get("configuration").get("retention.ms").asInt());
    }
}
