/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service.log;

import io.fabric8.kubernetes.api.model.Pod;
import io.streamshub.mcp.common.dto.LogCollectionParams;
import io.streamshub.mcp.common.dto.PodLogsResult;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Orchestrator for collecting logs from multiple Kubernetes pods.
 *
 * <p>Iterates pods, delegates fetching and filtering to the active
 * {@link LogCollectorProvider}, then aggregates results with error counting,
 * deduplication, and progress callbacks.</p>
 */
@ApplicationScoped
public class LogCollectionService {

    private static final Logger LOG = Logger.getLogger(LogCollectionService.class);

    @Inject
    Instance<LogCollectorProvider> logProvider;

    @ConfigProperty(name = "mcp.log.provider", defaultValue = "streamshub-kubernetes")
    String providerName;

    LogCollectionService() {
    }

    /**
     * Validates log provider configuration at startup.
     * Logs a warning if no provider is configured.
     */
    @PostConstruct
    void validateConfiguration() {
        if (logProvider.isUnsatisfied()) {
            LOG.warnf("No log provider configured. Set 'mcp.log.provider' to "
                + "'streamshub-kubernetes'. Provider name: %s", providerName);
        } else {
            LOG.infof("Log provider initialized: %s", providerName);
        }
    }

    /**
     * Collect logs from a list of pods with error counting, deduplication,
     * pagination, and lifecycle callbacks.
     *
     * <p>The active {@link LogCollectorProvider} handles fetching and filtering.
     * This service handles orchestration: iterating pods, counting errors,
     * deduplicating lines, and managing callbacks.</p>
     *
     * @param namespace the namespace of the pods
     * @param pods      the list of pods to collect logs from
     * @param options   log collection params (filter, keywords, pagination, callbacks)
     * @return the aggregated, deduplicated log result
     */
    @SuppressWarnings("checkstyle:CyclomaticComplexity")
    public PodLogsResult collectLogs(final String namespace, final List<Pod> pods,
                                     final LogCollectionParams options) {
        if (options.tailLines() <= 0) {
            throw new IllegalArgumentException("tailLines must be positive, got: " + options.tailLines());
        }

        List<String> podNames = pods.stream()
            .map(pod -> pod.getMetadata().getName())
            .toList();

        StringBuilder allLogs = new StringBuilder();
        int errorCount = 0;
        int totalLines = 0;
        int filteredLines = 0;
        boolean hasMore = false;

        int podIndex = 0;
        for (Pod pod : pods) {
            if (options.cancelCheck() != null) {
                options.cancelCheck().run();
            }
            String podName = pod.getMetadata().getName();
            podIndex++;
            if (options.notifier() != null) {
                options.notifier().accept(String.format("Collecting logs from pod %s (%d/%d)",
                    podName, podIndex, pods.size()));
            }
            try {
                String podLog = logProvider.get().fetchLogs(namespace, podName, options.tailLines(),
                    options.sinceSeconds(), options.previous(), options.filter(), options.keywords(),
                    options.startTime(), options.endTime());

                if (podLog != null && !podLog.isEmpty()) {
                    String trimmedLog = tailLines(podLog, options.tailLines());
                    hasMore = hasMore || trimmedLog.length() < podLog.length();
                    String[] lines = trimmedLog.split("\n");

                    totalLines += lines.length;

                    List<String> matchedLines = new ArrayList<>();
                    for (String line : lines) {
                        String upperLine = line.toUpperCase(Locale.ENGLISH);
                        if (upperLine.contains("ERROR") || upperLine.contains("EXCEPTION")) {
                            errorCount++;
                        }
                        matchedLines.add(line);
                        filteredLines++;
                    }

                    if (!matchedLines.isEmpty()) {
                        allLogs.append("=== Pod: ").append(podName).append(" ===\n");
                        allLogs.append(deduplicateLines(matchedLines));
                    }
                }
            } catch (Exception e) {
                LOG.debugf("Could not retrieve logs from pod %s: %s", podName, e.getMessage());
                allLogs.append("=== Pod: ").append(podName).append(" === (logs unavailable)\n");
            }
            if (options.progressCallback() != null) {
                options.progressCallback().accept(podIndex, pods.size());
            }
        }

        return new PodLogsResult(podNames, allLogs.toString(), errorCount, totalLines, filteredLines, hasMore);
    }

    /**
     * Extract the last {@code count} lines from a log string by scanning
     * backward for newline characters. Avoids splitting the entire string
     * into an array when only the tail is needed.
     *
     * @param log   the full log string
     * @param count the maximum number of lines to keep
     * @return the tail portion of the log, or the full log if it has fewer lines
     */
    static String tailLines(final String log, final int count) {
        int end = log.length();
        if (end > 0 && log.charAt(end - 1) == '\n') {
            end--;
        }
        int newlinesSeen = 0;
        for (int i = end - 1; i >= 0; i--) {
            if (log.charAt(i) == '\n') {
                newlinesSeen++;
                if (newlinesSeen == count) {
                    return log.substring(i + 1);
                }
            }
        }
        return log;
    }

    /**
     * Deduplicate consecutive identical log lines. When the same line appears
     * multiple times in a row, it is shown once with a {@code [repeated N times]}
     * suffix. Non-consecutive duplicates are preserved to maintain chronological order.
     *
     * @param lines the list of log lines to deduplicate
     * @return the deduplicated log output as a string
     */
    String deduplicateLines(final List<String> lines) {
        StringBuilder output = new StringBuilder();
        String previous = null;
        int count = 0;

        for (String line : lines) {
            if (line.equals(previous)) {
                count++;
            } else {
                if (previous != null) {
                    appendLine(output, previous, count);
                }
                previous = line;
                count = 1;
            }
        }
        if (previous != null) {
            appendLine(output, previous, count);
        }
        return output.toString();
    }

    private static void appendLine(final StringBuilder output, final String line, final int count) {
        output.append(line);
        if (count > 1) {
            output.append(" [repeated ").append(count).append(" times]");
        }
        output.append("\n");
    }
}
