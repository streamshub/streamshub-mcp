/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link ToolCallMetricsRecorder}.
 */
class ToolCallMetricsRecorderTest {

    private SimpleMeterRegistry registry;
    private ToolCallMetricsRecorder recorder;

    ToolCallMetricsRecorderTest() {
    }

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        recorder = new ToolCallMetricsRecorder();
        recorder.registry = FakeInstance.of(registry);
        recorder.serverName = "test-mcp";
    }

    @Test
    void recordsSuccessWithNoneErrorType() {
        Timer.Sample sample = recorder.start();
        recorder.record(sample, "listKafkaClusters", ToolCallOutcome.SUCCESS);

        Timer timer = registry.find("mcp.tool.call.duration")
            .tag("server", "test-mcp").tag("tool", "listKafkaClusters")
            .tag("status", "success").tag("error_type", "none").timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());

        Counter counter = registry.find("mcp.tool.calls")
            .tag("server", "test-mcp").tag("tool", "listKafkaClusters")
            .tag("status", "success").tag("error_type", "none").counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
    }

    @Test
    void recordsProtocolError() {
        recorder.record(recorder.start(), "getKafkaCluster", ToolCallOutcome.PROTOCOL_ERROR);

        Counter counter = registry.find("mcp.tool.calls")
            .tag("status", "error").tag("error_type", "protocol_error").counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
    }

    @Test
    void recordsToolError() {
        recorder.record(recorder.start(), "getKafkaCluster", ToolCallOutcome.TOOL_ERROR);

        Counter counter = registry.find("mcp.tool.calls")
            .tag("status", "error").tag("error_type", "tool_error").counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
    }

    @Test
    void recordRejectionCountsCallButNotDuration() {
        recorder.recordRejection("get_kafka_cluster_logs", ToolCallOutcome.RATE_LIMITED);

        Counter counter = registry.find("mcp.tool.calls")
            .tag("tool", "get_kafka_cluster_logs")
            .tag("status", "error").tag("error_type", "rate_limited").counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());

        // A rejected call never ran, so no duration is recorded.
        Timer timer = registry.find("mcp.tool.call.duration")
            .tag("tool", "get_kafka_cluster_logs").timer();
        assertNull(timer);
    }

    @Test
    void nullSampleDoesNotRecord() {
        recorder.record(null, "listKafkaClusters", ToolCallOutcome.SUCCESS);

        assertNull(registry.find("mcp.tool.calls").counter());
        assertNull(registry.find("mcp.tool.call.duration").timer());
    }

    @Test
    void noRegistryIsNoop() {
        ToolCallMetricsRecorder noReg = new ToolCallMetricsRecorder();
        noReg.registry = FakeInstance.unresolvable();
        noReg.serverName = "test-mcp";

        assertNull(noReg.start());
        noReg.record(null, "listKafkaClusters", ToolCallOutcome.SUCCESS);
        noReg.recordRejection("listKafkaClusters", ToolCallOutcome.RATE_LIMITED);
        // Nothing thrown, nothing recorded in the shared registry either.
        assertNull(registry.find("mcp.tool.calls").counter());
    }
}
