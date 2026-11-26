/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.metrics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Builds the label matcher that limits a metrics query to one Strimzi component.
 *
 * <p>Components of one cluster share metric names such as {@code jvm_*} and {@code process_*}, so a
 * query that matches only the namespace and cluster label returns every component's series. The
 * component label ({@code strimzi_io_component_type}, or {@code strimzi_io_kind} for Kafka Connect)
 * tells them apart. It comes from the {@code labelmap} relabeling of the Strimzi example PodMonitors,
 * and a Prometheus setup without that relabeling lacks it.</p>
 *
 * <p>The matcher is therefore a regex with an empty alternative: {@code label=~"value|"}. PromQL (and
 * pod scraping) compares a missing label as {@code ""}, so a series without the label still matches,
 * while a series of another component, which carries a different value, does not.</p>
 */
final class ComponentScope {

    private static final Pattern REGEX_META = Pattern.compile("[.\\\\+*?\\[\\]^$(){}|]");

    private ComponentScope() {
    }

    /**
     * Returns regex label matchers that keep the given component and series without the label.
     *
     * @param label the component label, for example {@code strimzi_io_component_type}
     * @param value the component value, for example {@code kafka-exporter}
     * @return a mutable map with the one matcher, so callers can add their own regex matchers
     */
    static Map<String, String> matchers(final String label, final String value) {
        Map<String, String> matchers = new LinkedHashMap<>();
        matchers.put(label, REGEX_META.matcher(value).replaceAll("\\\\$0") + "|");
        return matchers;
    }
}
