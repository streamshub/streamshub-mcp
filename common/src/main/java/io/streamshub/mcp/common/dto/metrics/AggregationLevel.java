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
     * Returns {@link #CLUSTER} if the input is null or blank.
     *
     * @param value the level name
     * @return the parsed level, or CLUSTER as default
     */
    public static AggregationLevel fromString(final String value) {
        if (value == null || value.isBlank()) {
            return CLUSTER;
        }
        return valueOf(value.toUpperCase(Locale.ROOT));
    }

    /**
     * Resolves the effective level for a category-scoped request.
     *
     * <p>An explicitly requested level is parsed and clamped to {@code finest} — a caller
     * cannot ask for more detail than the category's data carries. When the caller did not
     * request a level, the default is {@link #CLUSTER} <em>except</em> for categories whose
     * finest level is {@link #PARTITION}: those expose per-partition 0/1 gauges
     * (e.g. {@code kafka_cluster_partition_underminisr}) where averaging destroys the signal —
     * 3 bad partitions out of 1000 would read as 0.003 instead of 3. Those default to
     * {@link #PARTITION}.</p>
     *
     * @param requested the caller-supplied level name (may be null or blank)
     * @param finest    the finest meaningful level for the category in play
     * @return the effective aggregation level; never null
     */
    public static AggregationLevel resolve(final String requested, final AggregationLevel finest) {
        if (requested == null || requested.isBlank()) {
            return finest == PARTITION ? PARTITION : CLUSTER;
        }
        return fromString(requested).clampTo(finest);
    }
}
