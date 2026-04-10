/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.loki;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link LokiLogProvider} verifying LogQL query construction
 * and response parsing.
 */
@QuarkusTest
class LokiLogProviderTest {

    @Inject
    LokiLogProvider lokiLogCollectorProvider;

    LokiLogProviderTest() {
    }

    @Test
    void testBuildLogQueryDefaultLabels() {
        String query = lokiLogCollectorProvider.buildLogQuery("kafka-prod", "my-cluster-kafka-0");

        assertEquals("{namespace=\"kafka-prod\", pod=\"my-cluster-kafka-0\"}", query);
    }

    @Test
    void testExtractLogLinesFromResponse() {
        LokiResponse response = new LokiResponse("success", new LokiResponse.LokiData(
            "streams",
            List.of(new LokiResponse.LokiStream(
                Map.of("namespace", "kafka", "pod", "test-pod"),
                List.of(
                    List.of("1000000000000000000", "first line"),
                    List.of("2000000000000000000", "second line"),
                    List.of("3000000000000000000", "third line")
                )
            ))
        ));

        String result = lokiLogCollectorProvider.extractLogLines(response);

        assertNotNull(result);
        assertEquals("first line\nsecond line\nthird line\n", result);
    }

    @Test
    void testExtractLogLinesSortsChronologically() {
        LokiResponse response = new LokiResponse("success", new LokiResponse.LokiData(
            "streams",
            List.of(new LokiResponse.LokiStream(
                Map.of(),
                List.of(
                    List.of("3000000000000000000", "third"),
                    List.of("1000000000000000000", "first"),
                    List.of("2000000000000000000", "second")
                )
            ))
        ));

        String result = lokiLogCollectorProvider.extractLogLines(response);

        assertEquals("first\nsecond\nthird\n", result);
    }

    @Test
    void testExtractLogLinesMergesMultipleStreams() {
        LokiResponse response = new LokiResponse("success", new LokiResponse.LokiData(
            "streams",
            List.of(
                new LokiResponse.LokiStream(Map.of(), List.of(
                    List.of("1000000000000000000", "stream1-line1"),
                    List.of("3000000000000000000", "stream1-line2")
                )),
                new LokiResponse.LokiStream(Map.of(), List.of(
                    List.of("2000000000000000000", "stream2-line1")
                ))
            )
        ));

        String result = lokiLogCollectorProvider.extractLogLines(response);

        assertEquals("stream1-line1\nstream2-line1\nstream1-line2\n", result);
    }

    @Test
    void testExtractLogLinesReturnsNullForEmptyResponse() {
        LokiResponse response = new LokiResponse("success", new LokiResponse.LokiData(
            "streams", List.of()
        ));

        assertNull(lokiLogCollectorProvider.extractLogLines(response));
    }

    @Test
    void testExtractLogLinesReturnsNullForNullData() {
        assertNull(lokiLogCollectorProvider.extractLogLines(new LokiResponse("success", null)));
        assertNull(lokiLogCollectorProvider.extractLogLines(null));
    }

    @Test
    void testBuildLogQueryEscapesSpecialCharacters() {
        String query = lokiLogCollectorProvider.buildLogQuery("ns\"} | logfmt", "pod-0");

        assertEquals("{namespace=\"ns\\\"} | logfmt\", pod=\"pod-0\"}", query);
    }

    @Test
    void testExtractLogLinesSkipsInvalidEntries() {
        LokiResponse response = new LokiResponse("success", new LokiResponse.LokiData(
            "streams",
            List.of(new LokiResponse.LokiStream(
                Map.of(),
                List.of(
                    List.of("1000000000000000000", "valid line"),
                    List.of("only-timestamp")
                )
            ))
        ));

        String result = lokiLogCollectorProvider.extractLogLines(response);

        assertEquals("valid line\n", result);
    }
}
