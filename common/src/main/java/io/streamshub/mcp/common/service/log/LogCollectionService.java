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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
                    String[] lines = podLog.split("\n");

                    if (lines.length > options.tailLines()) {
                        hasMore = true;
                        String[] trimmed = new String[options.tailLines()];
                        System.arraycopy(lines, lines.length - options.tailLines(), trimmed, 0, options.tailLines());
                        lines = trimmed;
                    }

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
     * Deduplicate log lines by grouping identical consecutive or repeated lines.
     * When the same line appears multiple times, it is shown once with a
     * {@code [repeated N times]} suffix.
     *
     * @param lines the list of log lines to deduplicate
     * @return the deduplicated log output as a string
     */
    private String deduplicateLines(final List<String> lines) {
        Map<String, Integer> lineCounts = new LinkedHashMap<>();
        for (String line : lines) {
            lineCounts.merge(line, 1, Integer::sum);
        }

        StringBuilder output = new StringBuilder();
        for (Map.Entry<String, Integer> entry : lineCounts.entrySet()) {
            output.append(entry.getKey());
            if (entry.getValue() > 1) {
                output.append(" [repeated ").append(entry.getValue()).append(" times]");
            }
            output.append("\n");
        }
        return output.toString();
    }
}
