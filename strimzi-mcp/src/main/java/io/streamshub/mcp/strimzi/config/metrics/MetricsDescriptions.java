/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.config.metrics;

/**
 * Shared JVM metric interpretation text used by both Kafka and operator metric categories.
 * Parameterized by the tool name for pod health checks and the correlation context.
 */
final class MetricsDescriptions {

    private MetricsDescriptions() {
        // Utility class — no instantiation
    }

    /**
     * Builds the JVM metric interpretation guide.
     *
     * @param podCheckTool       the MCP tool name for checking pod health
     *                           (e.g. {@code get_kafka_cluster_pods} or {@code get_strimzi_operator_pod})
     * @param correlationContext what symptoms to correlate with GC pressure
     *                           (e.g. {@code "performance degradation or pod restarts"})
     * @return the formatted JVM interpretation text
     */
    static String jvmDescription(final String podCheckTool, final String correlationContext) {
        return "**[MEDIUM - JVM HEALTH]**\n\n"
            + "jvm_memory_used_bytes: Current JVM heap/non-heap memory usage. "
            + "**IMPORTANT**: Java normally uses most of its allocated heap — high usage alone is NOT a problem. "
            + "Only flag as concerning if combined with: (1) pod restarts (check with " + podCheckTool + "), "
            + "(2) OOM errors in logs, or (3) excessive GC overhead (rapidly increasing GC count). "
            + "**FALSE POSITIVE TRAP**: Do not raise alerts based solely on high heap usage.\n\n"
            + "jvm_memory_max_bytes: Maximum JVM memory available per pool.\n\n"
            + "**[MEDIUM - GC PRESSURE]**\n\n"
            + "jvm_gc_collection_seconds_sum/count: GC time and frequency. "
            + "High sum/count ratio = long GC pauses. Rapidly increasing count = GC thrashing. "
            + "**THRESHOLDS**: <5% of CPU time = healthy, 5-10% = monitor, >10% = investigate heap sizing. "
            + "Only concerning if it correlates with " + correlationContext + ".\n\n"
            + "process_cpu_seconds_total: Cumulative CPU time. Rate of change = CPU utilization.\n\n"
            + "**[LOW - THREAD HEALTH]**\n\n"
            + "jvm_threads_current: Active JVM thread count. "
            + "Sudden increases may indicate thread leaks or excessive concurrency. "
            + "**BASELINE**: Stable count is normal, rapid growth (>50% in <5 min) needs investigation.";
    }
}
