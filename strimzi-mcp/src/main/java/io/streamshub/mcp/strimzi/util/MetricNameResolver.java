/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.util;

import io.streamshub.mcp.common.util.McpErrors;
import io.streamshub.mcp.common.util.metrics.MetricNameSuffixes;
import io.streamshub.mcp.metrics.prometheus.util.PromQLSanitizer;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
/**
 * Resolves metric names from a combination of category and explicit metric names.
 * Shared across metrics services to avoid duplicated resolution logic.
 */
public final class MetricNameResolver {

    /**
     * Sentinel value used in alias maps to signal that a JMX Exporter metric name
     * cannot be mapped to Strimzi Metrics Reporter. When the resolver encounters this
     * value it excludes the metric entirely from the resolved list for that backend.
     */
    public static final String UNMAPPED = "__unmapped__";

    /** Appended to an {@link #UNMAPPED} name in interpretation text — see {@code alignInterpretation}. */
    private static final String UNAVAILABLE_NOTE = " (not exposed by the Strimzi Metrics Reporter)";

    private static final Logger LOG = Logger.getLogger(MetricNameResolver.class);

    private MetricNameResolver() {
    }

    /**
     * Resolves metric names from a category and/or explicit metric names.
     * When neither category nor metric names are provided, uses the default category.
     *
     * @param category         the metric category (may be null)
     * @param metricNames      comma-separated explicit metric names (may be null)
     * @param defaultCategory  the default category to use when no inputs are provided
     * @param categoryResolver function that maps a category name to its metric names
     * @param allCategories    supplier of all valid category names (for error messages)
     * @return the resolved list of metric names
     */
    public static List<String> resolve(final String category,
                                        final String metricNames,
                                        final String defaultCategory,
                                        final Function<String, List<String>> categoryResolver,
                                        final Set<String> allCategories) {
        return resolve(category, metricNames, defaultCategory, categoryResolver, allCategories,
            MetricsBackend.JMX_EXPORTER, Map.of());
    }

    /**
     * Resolves metric names from a category and/or explicit metric names, applying
     * backend-aware name translation via an alias map.
     *
     * <p>For each JMX Exporter name in the resolved list the alias map is consulted:
     * <ul>
     *   <li>If the alias value is {@link #UNMAPPED} the metric is excluded from the
     *       result (it cannot be queried on this backend).</li>
     *   <li>Otherwise the alias value replaces the JMX name in the result.</li>
     *   <li>Names absent from the map pass through unchanged.</li>
     * </ul>
     * When {@code backend} is {@link MetricsBackend#JMX_EXPORTER} the alias map is
     * ignored and names are returned as-is.</p>
     *
     * @param category         the metric category (may be null)
     * @param metricNames      comma-separated explicit metric names (may be null)
     * @param defaultCategory  the default category to use when no inputs are provided
     * @param categoryResolver function that maps a category name to its metric names
     * @param allCategories    supplier of all valid category names (for error messages)
     * @param backend          the metrics backend for the target cluster
     * @param aliasMap         JMX name → SMR name mappings; values of {@link #UNMAPPED} exclude
     *                         the metric from the result; may be empty but must not be null
     * @return the resolved list of metric names, translated for the given backend
     */
    public static List<String> resolve(final String category,
                                        final String metricNames,
                                        final String defaultCategory,
                                        final Function<String, List<String>> categoryResolver,
                                        final Set<String> allCategories,
                                        final MetricsBackend backend,
                                        final Map<String, String> aliasMap) {
        List<String> resolved = new ArrayList<>();

        String effectiveCategory = category;
        if (effectiveCategory == null && (metricNames == null || metricNames.isBlank())) {
            effectiveCategory = defaultCategory;
        }

        if (effectiveCategory != null) {
            List<String> categoryMetrics = categoryResolver.apply(effectiveCategory);
            if (categoryMetrics.isEmpty() && category != null) {
                throw McpErrors.invalidParams(
                    String.format("Unknown metric category '%s'. Available: %s",
                        category, allCategories));
            }
            resolved.addAll(categoryMetrics);
        }

        if (metricNames != null && !metricNames.isBlank()) {
            for (String metric : metricNames.split(",")) {
                String trimmed = metric.trim();
                if (!trimmed.isEmpty()) {
                    try {
                        String validated = PromQLSanitizer.sanitizeMetricName(trimmed);
                        if (!resolved.contains(validated)) {
                            resolved.add(validated);
                        }
                    } catch (IllegalArgumentException e) {
                        LOG.warnf("Invalid metric name '%s': %s", trimmed, e.getMessage());
                    }
                }
            }
        }

        if (backend == MetricsBackend.JMX_EXPORTER || aliasMap.isEmpty()) {
            return resolved;
        }

        // Translate JMX names → SMR names, dropping UNMAPPED entries.
        // LinkedHashSet so a translated name cannot collide with an untranslated one.
        Set<String> translated = new LinkedHashSet<>();
        for (String name : resolved) {
            String alias = aliasMap.get(name);
            if (alias == null) {
                translated.add(name);
            } else if (!UNMAPPED.equals(alias)) {
                translated.add(alias);
                LOG.debugf("Backend %s: mapped metric name '%s' → '%s'", backend, name, alias);
            } else {
                LOG.debugf("Backend %s: excluded unmappable metric name '%s'", backend, name);
            }
        }
        return List.copyOf(translated);
    }

    /**
     * Aligns an interpretation guide for a component with a single metrics backend, where only the
     * provider's counter rate rename can shift a name.
     *
     * @param interpretation the guide text (may be null)
     * @param returnedNames  the metric names present in the response
     * @return the guide text with names aligned to the response, or the input unchanged
     */
    public static String alignInterpretation(final String interpretation,
                                             final Collection<String> returnedNames) {
        return alignInterpretation(interpretation, MetricsBackend.JMX_EXPORTER, Map.of(), returnedNames);
    }

    /**
     * Rewrites the metric names embedded in an interpretation guide so they match the names the
     * caller actually receives.
     * <p>
     * The guide text is written once, in JMX Exporter spelling, but a name reaches the client only
     * after two possible renames: the SMR alias translation done by
     * {@link #resolve(String, String, String, Function, Set, MetricsBackend, Map)}, and the
     * {@code _total} → {@code _rate_per_second} rename the Prometheus provider applies to counters.
     * Left alone the guide names metrics that are not in the response, which is worse than saying
     * nothing — it reads as missing data rather than as a spelling difference.
     * <p>
     * Names that SMR does not expose at all ({@link #UNMAPPED}) are annotated rather than replaced,
     * because there is no name to point at and silently dropping the sentence would hide the gap.
     *
     * @param interpretation the guide text (may be null)
     * @param backend        the metrics backend the cluster is configured with
     * @param aliasMap       JMX → SMR name map, as passed to {@code resolve} (may be empty)
     * @param returnedNames  the metric names present in the response
     * @return the guide text with names aligned to the response, or the input unchanged
     */
    public static String alignInterpretation(final String interpretation,
                                             final MetricsBackend backend,
                                             final Map<String, String> aliasMap,
                                             final Collection<String> returnedNames) {
        if (interpretation == null || interpretation.isBlank()) {
            return interpretation;
        }

        String aligned = interpretation;

        if (backend == MetricsBackend.STRIMZI_REPORTER && aliasMap != null && !aliasMap.isEmpty()) {
            Map<String, String> smrNames = new LinkedHashMap<>();
            aliasMap.forEach((jmx, smr) ->
                smrNames.put(jmx, UNMAPPED.equals(smr) ? jmx + UNAVAILABLE_NOTE : smr));
            aligned = rewriteNames(aligned, smrNames);
        }

        // The rate rename is the provider's, not the backend's, so derive it from what came back
        // rather than re-deciding here which names are counters.
        Map<String, String> rateNames = new LinkedHashMap<>();
        for (String name : returnedNames) {
            String preRename = MetricNameSuffixes.toTotal(name);
            if (preRename != null) {
                rateNames.put(preRename, name);
            }
        }
        return rewriteNames(aligned, rateNames);
    }

    /**
     * Replaces whole metric-name tokens in one pass, longest name first so that a name which is a
     * prefix of another cannot claim the shorter match.
     */
    private static String rewriteNames(final String text, final Map<String, String> replacements) {
        if (replacements.isEmpty()) {
            return text;
        }
        String alternation = replacements.keySet().stream()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .map(Pattern::quote)
            .collect(Collectors.joining("|"));
        Matcher matcher = Pattern.compile("(?<![A-Za-z0-9_])(" + alternation + ")(?![A-Za-z0-9_])")
            .matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacements.get(matcher.group(1))));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
