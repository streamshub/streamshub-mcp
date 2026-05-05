/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.dto.metrics;

import java.util.Locale;

/**
 * Controls how metric samples are aggregated before being returned to the client.
 * Each level names the dimension to group by; dimensions below that level are
 * averaged out.
 */
public enum AggregationLevel {

    /**
     * Full detail: keep pod + topic + partition. No averaging.
     */
    PARTITION,

    /**
     * Keep pod + topic, average across partitions.
     */
    TOPIC,

    /**
     * Keep pod only, average across topics and partitions.
     */
    BROKER,

    /**
     * Average across all dimensions. Single value per metric + intrinsic labels.
     */
    CLUSTER;

    /**
     * Clamps this level to a ceiling: if this level is finer (lower ordinal)
     * than the ceiling, the ceiling is returned; otherwise this level is kept.
     *
     * @param ceiling the finest meaningful level for a given category
     * @return the clamped level
     */
    public AggregationLevel clampTo(final AggregationLevel ceiling) {
        return this.ordinal() < ceiling.ordinal() ? ceiling : this;
    }

    /**
     * Parses a string to an aggregation level (case-insensitive).
     * Returns {@link #BROKER} if the input is null or blank.
     *
     * @param value the level name
     * @return the parsed level, or BROKER as default
     */
    public static AggregationLevel fromString(final String value) {
        if (value == null || value.isBlank()) {
            return BROKER;
        }
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
