/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.metrics;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ComponentScope}.
 */
class ComponentScopeTest {

    ComponentScopeTest() {
    }

    /**
     * PromQL {@code =~} is anchored and compares a missing label as "": a Java full match has the same
     * semantics, and pod scraping applies exactly this.
     */
    @Test
    void keepsTheComponentAndSeriesWithoutTheLabelButNotOtherComponents() {
        Map<String, String> matchers = ComponentScope.matchers("strimzi_io_component_type", "kafka-exporter");
        Pattern regex = Pattern.compile(matchers.get("strimzi_io_component_type"));

        assertTrue(regex.matcher("kafka-exporter").matches());
        assertTrue(regex.matcher("").matches(), "a series without the label must still match");
        assertFalse(regex.matcher("kafka").matches());
        assertFalse(regex.matcher("cruise-control").matches());
    }

    @Test
    void escapesRegexCharactersInTheValue() {
        assertEquals("a\\.b|", ComponentScope.matchers("l", "a.b").get("l"));
    }
}
