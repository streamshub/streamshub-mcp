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
            + "jvm_gc_collection_seconds_count: Number of GC events. "
            + "jvm_gc_collection_seconds_sum: Total time spent in GC. "
            + "High sum/count ratio = long GC pauses. Rapidly increasing count = GC thrashing. "
            + "**THRESHOLDS**: <5% of CPU time = healthy, 5-10% = monitor, >10% = investigate heap sizing. "
            + "Only concerning if it correlates with " + correlationContext + ".\n\n"
            + "process_cpu_seconds_total: Cumulative CPU time. Rate of change = CPU utilization.\n\n"
            + "process_open_fds: Number of open file descriptors allocated by the JVM process. "
            + "Sudden or steady growth indicates an FD leak (e.g. unclosed sockets or files).\n\n"
            + "**[LOW - THREAD HEALTH]**\n\n"
            + "jvm_threads_current: Active JVM thread count. "
            + "Sudden increases may indicate thread leaks or excessive concurrency. "
            + "**BASELINE**: Stable count is normal, rapid growth (>50% in <5 min) needs investigation.";
    }

    /**
     * Builds the JVM metric interpretation guide for the Micrometer backend
     * (used by the Strimzi operator and Kafka Bridge).
     * <p>
     * Micrometer emits different names from the JMX Prometheus Exporter —
     * {@code jvm_gc_pause_seconds_*} instead of {@code jvm_gc_collection_seconds_*},
     * {@code jvm_threads_live_threads} instead of {@code jvm_threads_current}, and
     * {@code process_cpu_usage} is a 0–1 ratio rather than a counter.
     *
     * @param podCheckTool       the MCP tool name for checking pod health
     *                           (e.g. {@code get_strimzi_operator_pod})
     * @param correlationContext what symptoms to correlate with GC pressure
     *                           (e.g. {@code "slow reconciliations or pod restarts"})
     * @return the formatted Micrometer JVM interpretation text
     */
    static String micrometerJvmDescription(final String podCheckTool, final String correlationContext) {
        return "**[MEDIUM - JVM HEALTH]**\n\n"
            + "jvm_memory_used_bytes: Current JVM heap/non-heap memory usage. "
            + "**IMPORTANT**: Java normally uses most of its allocated heap — high usage alone is NOT a problem. "
            + "Only flag as concerning if combined with: (1) pod restarts (check with " + podCheckTool + "), "
            + "(2) OOM errors in logs, or (3) excessive GC overhead (rapidly increasing GC pause count). "
            + "**FALSE POSITIVE TRAP**: Do not raise alerts based solely on high heap usage.\n\n"
            + "jvm_memory_max_bytes: Maximum JVM memory available per pool.\n\n"
            + "**[MEDIUM - GC PRESSURE]**\n\n"
            + "jvm_gc_pause_seconds_count: Number of GC pauses. "
            + "jvm_gc_pause_seconds_sum: Total GC pause duration. "
            + "High sum/count ratio = long GC pauses. Rapidly increasing count = GC thrashing. "
            + "**THRESHOLDS**: <5% of wall-clock time = healthy, 5-10% = monitor, >10% = investigate heap sizing. "
            + "Only concerning if it correlates with " + correlationContext + ".\n\n"
            + "process_cpu_usage: Ratio (0–1) of CPU time used by the process. "
            + "Not a counter — take the rate as a direct fraction (×100 = percent). "
            + "Sustained values near 1.0 = CPU-bound; near 0.0 on an idle pod is normal.\n\n"
            + "**[LOW - THREAD HEALTH]**\n\n"
            + "jvm_threads_live_threads: Live JVM thread count. "
            + "Sudden increases may indicate thread leaks or excessive concurrency. "
            + "**BASELINE**: Stable count is normal, rapid growth (>50% in <5 min) needs investigation.";
    }

    /**
     * Builds the process/runtime interpretation guide for the Go-based Kafka Exporter.
     *
     * @return the formatted Go process interpretation text
     */
    static String goProcessDescription() {
        return "**[MEDIUM - PROCESS HEALTH]**\n\n"
            + "process_cpu_seconds_total: Cumulative CPU time consumed by the exporter process. "
            + "Rate of change = CPU utilization. Sustained high values = the exporter itself is busy scraping "
            + "many topics.\n\n"
            + "process_resident_memory_bytes: Resident set size (RSS) — physical memory in use. "
            + "Compare against the pod memory limit (see get_kafka_cluster_pods) to assess "
            + "OOMKill risk. Steadily growing RSS = memory leak in the exporter.\n\n"
            + "process_open_fds: Number of open file descriptors. "
            + "Approaching the pod limit (default 1024) = risk of scrape failures. "
            + "**THRESHOLD**: >80% of the fd limit warrants investigation.\n\n"
            + "go_goroutines: Number of Go goroutines. "
            + "Sudden increases may indicate a goroutine leak. "
            + "**BASELINE**: Stable count is normal; rapid growth (>50% in <5 min) needs investigation.";
    }

}
