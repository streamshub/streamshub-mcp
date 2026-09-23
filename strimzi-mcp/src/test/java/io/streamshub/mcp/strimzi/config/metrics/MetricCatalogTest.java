/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.config.metrics;

import io.streamshub.mcp.metrics.prometheus.util.PromQLSanitizer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Layer-1 regression test for the metric catalog.
 * <p>
 * Parameterized over all six {@code *MetricCategories} classes. Runs in {@code ./mvnw test}
 * — no Kubernetes cluster needed. Five invariants:
 * <ol>
 *   <li>Catalog↔description coherence: every name in CATEGORIES appears in DESCRIPTIONS
 *       and every metric-like name extracted from DESCRIPTIONS exists in CATEGORIES.</li>
 *   <li>Backend shape rules: Micrometer names are absent from JMX-exporter classes
 *       and vice-versa; the Go-based exporter class has no {@code jvm_} prefix at all.</li>
 *   <li>Well-formedness: every name passes {@link PromQLSanitizer#sanitizeMetricName}.</li>
 *   <li>No silent duplicates: no name appears in more than one category within a class.</li>
 *   <li>Golden catalog: every configured name is present in the JMX-exporter golden file.</li>
 * </ol>
 * See {@code .claude/plans/strimzi-coverage/testing.md} §10a.
 */
class MetricCatalogTest {

    private static final Pattern METRIC_NAME_EXTRACT = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]{6,}");
    private static final Pattern LABEL_REFERENCE = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*\\s+label\\b");
    private static final Set<String> TOOL_NAME_PREFIXES = Set.of("get_", "list_", "diagnose_", "compare_", "assess_");

    /**
     * Response field names a description may legitimately point the client at. They are
     * snake_case like a metric name, but they name part of the response envelope rather than a
     * series, so the backward check must not demand a catalog entry for them.
     */
    private static final Set<String> RESPONSE_FIELDS = Set.of(
        "aggregation_fn", "source_count", "data_points");

    /**
     * Forbidden metric names per backend, per the §4.9 finding.
     * A name in the forbidden set for a class means the class uses a different backend.
     */
    private static final Set<String> JMX_EXPORTER_FORBIDDEN = Set.of(
        "jvm_gc_pause_seconds_count", "jvm_gc_pause_seconds_sum", "jvm_gc_pause_seconds_max",
        "jvm_threads_live_threads", "process_cpu_usage"
    );
    private static final Set<String> MICROMETER_FORBIDDEN = Set.of(
        "jvm_gc_collection_seconds_count", "jvm_gc_collection_seconds_sum",
        "jvm_threads_current", "process_cpu_seconds_total"
    );

    MetricCatalogTest() {
    }

    /**
     * Record holding the metadata needed to test one metric catalog class.
     */
    record CatalogDescriptor(
        String componentName,
        Set<String> categories,
        Function<String, List<String>> resolve,
        Function<List<String>, String> interpretation,
        Set<String> forbiddenNames,
        boolean goBinary
    ) {
    }

    /**
     * Returns all six catalog classes under test, each annotated with its backend rules.
     */
    static Stream<Arguments> catalogs() {
        return Stream.of(
            Arguments.of(new CatalogDescriptor(
                "Kafka",
                KafkaMetricCategories.allCategories(),
                KafkaMetricCategories::resolve,
                KafkaMetricCategories::interpretation,
                JMX_EXPORTER_FORBIDDEN, false)),
            Arguments.of(new CatalogDescriptor(
                "KafkaConnect",
                KafkaConnectMetricCategories.allCategories(),
                KafkaConnectMetricCategories::resolve,
                KafkaConnectMetricCategories::interpretation,
                JMX_EXPORTER_FORBIDDEN, false)),
            Arguments.of(new CatalogDescriptor(
                "KafkaBridge",
                KafkaBridgeMetricCategories.allCategories(),
                KafkaBridgeMetricCategories::resolve,
                KafkaBridgeMetricCategories::interpretation,
                MICROMETER_FORBIDDEN, false)),
            Arguments.of(new CatalogDescriptor(
                "StrimziOperator",
                StrimziOperatorMetricCategories.allCategories(),
                StrimziOperatorMetricCategories::resolve,
                StrimziOperatorMetricCategories::interpretation,
                MICROMETER_FORBIDDEN, false)),
            // kafka_exporter is a Go binary — no JVM at all, so no jvm_ name may appear
            Arguments.of(new CatalogDescriptor(
                "KafkaExporter",
                KafkaExporterMetricCategories.allCategories(),
                KafkaExporterMetricCategories::resolve,
                KafkaExporterMetricCategories::interpretation,
                Set.of(), true)),
            // Cruise Control runs on the JVM and is scraped by the JMX Prometheus Exporter
            Arguments.of(new CatalogDescriptor(
                "CruiseControl",
                CruiseControlMetricCategories.allCategories(),
                CruiseControlMetricCategories::resolve,
                CruiseControlMetricCategories::interpretation,
                JMX_EXPORTER_FORBIDDEN, false))
        );
    }

    // --- Invariant 1: catalog ↔ description coherence ---

    /**
     * Every metric name in CATEGORIES must appear in the description text,
     * and every metric-like name extracted from DESCRIPTIONS must exist in CATEGORIES.
     */
    @ParameterizedTest
    @MethodSource("catalogs")
    void catalogAndDescriptionCoherence(final CatalogDescriptor catalog) {
        for (String category : catalog.categories()) {
            category = category.toLowerCase(Locale.ROOT);
            List<String> names = catalog.resolve().apply(category);
            String description = catalog.interpretation().apply(List.of(category));

            if (description == null) {
                fail(catalog.componentName() + " category '" + category
                    + "' has no description — add a DESCRIPTIONS entry");
            }

            // Forward: every CATEGORIES name must appear as a substring of the description
            for (String name : names) {
                assertTrue(description.contains(name),
                    catalog.componentName() + " category '" + category
                    + "': metric name '" + name + "' is in CATEGORIES but missing from DESCRIPTIONS");
            }

            // Backward: every metric-like name extracted from DESCRIPTION must be in CATEGORIES.
            // Label references ("...carried in the current_state label") are prose about a label,
            // not a claim that a metric by that name exists — drop them before extracting.
            Set<String> catalogNameSet = new HashSet<>(names);
            Matcher matcher = METRIC_NAME_EXTRACT.matcher(LABEL_REFERENCE.matcher(description).replaceAll(""));
            Set<String> extracted = new HashSet<>();
            while (matcher.find()) {
                String match = matcher.group();
                // Skip tool-name references (e.g. get_kafka_cluster_pods) and bare words without underscores
                if (TOOL_NAME_PREFIXES.stream().anyMatch(match::startsWith)) {
                    continue;
                }
                if (!match.contains("_") || RESPONSE_FIELDS.contains(match)) {
                    continue;
                }
                extracted.add(match);
            }

            Set<String> unmapped = new HashSet<>(extracted);
            unmapped.removeAll(catalogNameSet);
            if (!unmapped.isEmpty()) {
                fail(catalog.componentName() + " category '" + category
                    + "': names in DESCRIPTIONS but not in CATEGORIES: " + unmapped);
            }
        }
    }

    // --- Invariant 2: backend shape rules ---

    /**
     * Micrometer/JMX-exporter Go names must not appear in the wrong backend class.
     */
    @ParameterizedTest
    @MethodSource("catalogs")
    void backendShapeRules(final CatalogDescriptor catalog) {
        if (catalog.goBinary()) {
            // Go binary: forbid any jvm_ prefix
            for (String category : catalog.categories()) {
                List<String> names = catalog.resolve().apply(category.toLowerCase(Locale.ROOT));
                for (String name : names) {
                    assertFalse(name.startsWith("jvm_"),
                        catalog.componentName() + " (Go binary): metric '" + name
                        + "' has jvm_ prefix but the component is a Go binary with no JVM");
                }
            }
            return;
        }

        Set<String> forbidden = catalog.forbiddenNames();
        for (String category : catalog.categories()) {
            List<String> names = catalog.resolve().apply(category.toLowerCase(Locale.ROOT));
            for (String name : names) {
                assertFalse(forbidden.contains(name),
                    catalog.componentName() + " category '" + category
                    + "': metric '" + name + "' is a forbidden backend-specific name");
            }
        }
    }

    // --- Invariant 3: well-formedness ---

    /**
     * Every metric name must pass PromQLSanitizer.sanitizeMetricName().
     */
    @ParameterizedTest
    @MethodSource("catalogs")
    void allNamesWellFormed(final CatalogDescriptor catalog) {
        for (String category : catalog.categories()) {
            List<String> names = catalog.resolve().apply(category.toLowerCase(Locale.ROOT));
            for (String name : names) {
                // Should not throw
                PromQLSanitizer.sanitizeMetricName(name);
            }
        }
    }

    // --- Invariant 4: no silent duplicates ---

    /**
     * No metric name may appear in more than one category within the same class.
     */
    @ParameterizedTest
    @MethodSource("catalogs")
    void noDuplicateNamesWithinClass(final CatalogDescriptor catalog) {
        Map<String, String> nameToCategory = new HashMap<>();
        for (String category : catalog.categories()) {
            List<String> names = catalog.resolve().apply(category.toLowerCase(Locale.ROOT));
            for (String name : names) {
                String existing = nameToCategory.putIfAbsent(name.toLowerCase(Locale.ROOT), category);
                if (existing != null && !existing.equals(category)) {
                    fail(catalog.componentName() + ": metric '" + name
                        + "' appears in both '" + existing + "' and '" + category + "'");
                }
            }
        }
    }

    // --- Invariant 5: golden catalog ---

    /**
     * Every metric name in every category class must appear in the JMX-exporter golden file.
     * The golden file is a sorted snapshot of Prometheus /api/v1/label/__name__/values.
     * Compare catalog names (pre-rename), not response names — see §4.0 F2.
     */
    @ParameterizedTest
    @MethodSource("catalogs")
    void allConfiguredNamesInGoldenFile(final CatalogDescriptor catalog) {
        Set<String> goldenNames = loadGoldenFile("metrics/known-series-jmx-exporter.txt");
        assertFalse(goldenNames.isEmpty(), "Golden file is empty — check test resource path");

        List<String> missing = new ArrayList<>();
        for (String category : catalog.categories()) {
            List<String> names = catalog.resolve().apply(category.toLowerCase(Locale.ROOT));
            for (String name : names) {
                if (!goldenNames.contains(name)) {
                    missing.add(catalog.componentName() + "/" + category + "/" + name);
                }
            }
        }
        assertTrue(missing.isEmpty(),
            "Metric names in catalog but not in golden file:\n"
            + String.join("\n", missing));
    }

    // --- Helper ---

    /**
     * Loads a golden file from test resources, ignoring comment lines (starting with {@code #}).
     */
    private static Set<String> loadGoldenFile(final String resourcePath) {
        Set<String> names = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            MetricCatalogTest.class.getClassLoader().getResourceAsStream(resourcePath),
            StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    names.add(line);
                }
            }
        } catch (Exception e) {
            fail("Failed to load golden file " + resourcePath + ": " + e.getMessage());
        }
        return names;
    }
}
