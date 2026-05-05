/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.guardrail;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests for {@link MetricsFilter}.
 */
class MetricsFilterTest {

    private MetricsFilter filter;
    private SimpleMeterRegistry registry;

    MetricsFilterTest() {
    }

    @BeforeEach
    void setUp() {
        filter = new MetricsFilter();
        filter.serverName = "test-mcp";
        registry = new SimpleMeterRegistry();
        filter.setRegistry(registry);
    }

    @Test
    void testSuccessfulCallRecordsMetrics() {
        Object[] params = new Object[]{"cluster"};

        filter.filterInput("listKafkaClusters", params);
        filter.filterOutput("listKafkaClusters", "result");

        Timer timer = registry.find("mcp.tool.call.duration")
            .tag("server", "test-mcp")
            .tag("tool", "listKafkaClusters")
            .tag("status", "success")
            .timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());

        Counter counter = registry.find("mcp.tool.calls")
            .tag("server", "test-mcp")
            .tag("tool", "listKafkaClusters")
            .tag("status", "success")
            .counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
    }

    @Test
    void testErrorCallRecordsMetrics() {
        Object[] params = new Object[]{"cluster"};

        filter.filterInput("getKafkaCluster", params);
        filter.filterError("getKafkaCluster", new RuntimeException("K8s API error"));

        Timer timer = registry.find("mcp.tool.call.duration")
            .tag("server", "test-mcp")
            .tag("tool", "getKafkaCluster")
            .tag("status", "error")
            .timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());

        Counter counter = registry.find("mcp.tool.calls")
            .tag("server", "test-mcp")
            .tag("tool", "getKafkaCluster")
            .tag("status", "error")
            .counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
    }

    @Test
    void testNoDoubleCountingWhenOutputSucceedsThenErrorCalled() {
        Object[] params = new Object[]{"cluster"};

        filter.filterInput("listKafkaClusters", params);
        filter.filterOutput("listKafkaClusters", "result");
        filter.filterError("listKafkaClusters", new RuntimeException("output filter failed"));

        Counter successCounter = registry.find("mcp.tool.calls")
            .tag("server", "test-mcp")
            .tag("tool", "listKafkaClusters")
            .tag("status", "success")
            .counter();
        assertNotNull(successCounter);
        assertEquals(1.0, successCounter.count());

        Counter errorCounter = registry.find("mcp.tool.calls")
            .tag("server", "test-mcp")
            .tag("tool", "listKafkaClusters")
            .tag("status", "error")
            .counter();
        assertNull(errorCounter);
    }

    @Test
    void testMultipleToolCallsRecordSeparateMetrics() {
        filter.filterInput("listKafkaClusters", new Object[]{});
        filter.filterOutput("listKafkaClusters", "result1");

        filter.filterInput("getKafkaCluster", new Object[]{});
        filter.filterOutput("getKafkaCluster", "result2");

        filter.filterInput("listKafkaClusters", new Object[]{});
        filter.filterOutput("listKafkaClusters", "result3");

        Counter listCounter = registry.find("mcp.tool.calls")
            .tag("server", "test-mcp")
            .tag("tool", "listKafkaClusters")
            .tag("status", "success")
            .counter();
        assertNotNull(listCounter);
        assertEquals(2.0, listCounter.count());

        Counter getCounter = registry.find("mcp.tool.calls")
            .tag("server", "test-mcp")
            .tag("tool", "getKafkaCluster")
            .tag("status", "success")
            .counter();
        assertNotNull(getCounter);
        assertEquals(1.0, getCounter.count());
    }

    @Test
    void testParametersPassedThroughUnmodified() {
        Object[] params = new Object[]{"cluster", "namespace", 42};
        Object[] result = filter.filterInput("listKafkaClusters", params);
        assertSame(params, result);
    }

    @Test
    void testResultPassedThroughUnmodified() {
        filter.filterInput("listKafkaClusters", new Object[]{});
        Object original = "the result";
        Object result = filter.filterOutput("listKafkaClusters", original);
        assertSame(original, result);
    }

    @Test
    void testNoOpWithoutRegistry() {
        MetricsFilter noRegistryFilter = new MetricsFilter();
        Object[] params = new Object[]{"cluster"};

        Object[] inputResult = noRegistryFilter.filterInput("listKafkaClusters", params);
        assertSame(params, inputResult);

        Object outputResult = noRegistryFilter.filterOutput("listKafkaClusters", "result");
        assertEquals("result", outputResult);

        noRegistryFilter.filterError("listKafkaClusters", new RuntimeException("test"));
    }

    @Test
    void testMixedSuccessAndErrorCounts() {
        filter.filterInput("getKafkaCluster", new Object[]{});
        filter.filterOutput("getKafkaCluster", "ok");

        filter.filterInput("getKafkaCluster", new Object[]{});
        filter.filterError("getKafkaCluster", new RuntimeException("fail"));

        filter.filterInput("getKafkaCluster", new Object[]{});
        filter.filterOutput("getKafkaCluster", "ok");

        Counter successCounter = registry.find("mcp.tool.calls")
            .tag("server", "test-mcp")
            .tag("tool", "getKafkaCluster")
            .tag("status", "success")
            .counter();
        assertNotNull(successCounter);
        assertEquals(2.0, successCounter.count());

        Counter errorCounter = registry.find("mcp.tool.calls")
            .tag("server", "test-mcp")
            .tag("tool", "getKafkaCluster")
            .tag("status", "error")
            .counter();
        assertNotNull(errorCounter);
        assertEquals(1.0, errorCounter.count());
    }
}
