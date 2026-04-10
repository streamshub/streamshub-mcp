/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service.log;

import io.fabric8.kubernetes.api.model.Pod;
import io.streamshub.mcp.common.dto.LogCollectionOptions;
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
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Service for collecting, filtering, and deduplicating logs from Kubernetes pods.
 *
 * <p>Delegates raw log fetching to the active {@link LogCollectorProvider} and applies
 * filtering, keyword matching, deduplication, and progress callbacks on top.</p>
 */
@ApplicationScoped
public class LogCollectionService {

    private static final Logger LOG = Logger.getLogger(LogCollectionService.class);
    private static final int MAX_FILTER_LENGTH = 200;

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
     * Collect logs from a list of pods with optional filtering, keyword matching,
     * deduplication, pagination, and lifecycle callbacks.
     *
     * @param namespace the namespace of the pods
     * @param pods      the list of pods to collect logs from
     * @param options   log collection options (filter, keywords, pagination, callbacks)
     * @return the aggregated, filtered, and deduplicated log result
     */
    @SuppressWarnings("checkstyle:CyclomaticComplexity")
    public PodLogsResult collectLogs(final String namespace, final List<Pod> pods,
                                     final LogCollectionOptions options) {
        List<String> podNames = pods.stream()
            .map(pod -> pod.getMetadata().getName())
            .toList();

        Pattern filterPattern = compileLogFilter(options.filter());
        List<String> normalizedKeywords = normalizeKeywords(options.keywords());

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
                    options.sinceSeconds(), options.previous());

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
                        if (matchesFilter(line, filterPattern) && matchesKeywords(upperLine, normalizedKeywords)) {
                            matchedLines.add(line);
                            filteredLines++;
                        }
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
     * Check whether a log line matches the compiled filter pattern.
     *
     * @param line          the log line to check
     * @param filterPattern the compiled filter pattern, or null for no filtering
     * @return true if the line matches or no filter is set
     */
    private boolean matchesFilter(final String line, final Pattern filterPattern) {
        return filterPattern == null || filterPattern.matcher(line).find();
    }

    /**
     * Check whether a log line (uppercased) contains at least one of the keywords.
     *
     * @param upperLine          the uppercased log line
     * @param normalizedKeywords the list of uppercased keywords, or null for no keyword filtering
     * @return true if the line matches at least one keyword or no keywords are set
     */
    private boolean matchesKeywords(final String upperLine, final List<String> normalizedKeywords) {
        return normalizedKeywords == null || normalizedKeywords.stream().anyMatch(upperLine::contains);
    }

    /**
     * Normalize a list of keywords to uppercase for case-insensitive matching.
     * Returns null if the input is null or empty.
     *
     * @param keywords the raw keyword list
     * @return uppercased keywords, or null if no keywords provided
     */
    private List<String> normalizeKeywords(final List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return null;
        }
        return keywords.stream()
            .filter(k -> k != null && !k.isBlank())
            .map(k -> k.toUpperCase(Locale.ENGLISH).trim())
            .toList();
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

    /**
     * Compile a log filter string into a regex pattern.
     * Supports "errors", "warnings" shortcuts, or a custom regex.
     * Rejects patterns longer than {@value #MAX_FILTER_LENGTH} characters
     * to mitigate ReDoS attacks.
     *
     * @param filter the filter string
     * @return the compiled pattern, or null for no filtering
     */
    private Pattern compileLogFilter(final String filter) {
        if (filter == null || filter.isBlank()) {
            return null;
        }
        String normalized = filter.trim().toLowerCase(Locale.ENGLISH);
        if ("errors".equals(normalized)) {
            return Pattern.compile("(?i)(ERROR|EXCEPTION)");
        }
        if ("warnings".equals(normalized)) {
            return Pattern.compile("(?i)(ERROR|EXCEPTION|WARN)");
        }
        String trimmed = filter.trim();
        if (trimmed.length() > MAX_FILTER_LENGTH) {
            LOG.warnf("Log filter regex too long (%d chars, max %d), returning unfiltered logs",
                trimmed.length(), MAX_FILTER_LENGTH);
            return null;
        }
        try {
            return Pattern.compile(trimmed);
        } catch (PatternSyntaxException e) {
            LOG.warnf("Invalid log filter regex '%s', returning unfiltered logs: %s", filter, e.getMessage());
            return null;
        }
    }
}
