/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.config;

/**
 * Constants for MCP tool {@code _meta} fields.
 * Used with {@code @MetaField} annotations on tool methods.
 *
 * <p>Key names and tool action types are defined here in the common module
 * so they can be reused across MCP server implementations.
 * Resource-specific constants (e.g., Strimzi resource names) are defined
 * in their respective modules.</p>
 */
public final class ToolMetaFields {

    /**
     * Metadata key: the tool action types (list, get, diagnose, etc.).
     * Value is a JSON array of strings.
     */
    public static final String TYPE = "type";

    /**
     * Metadata key: the Kubernetes/domain resource the tool targets.
     */
    public static final String RESOURCE = "resource";

    /**
     * Metadata key: whether the tool aggregates multiple internal API calls.
     */
    public static final String COMPOSITE = "composite";

    private ToolMetaFields() {
    }

    /**
     * Tool action type values. Each tool declares one or more types
     * as a JSON array in the {@code _meta.type} field.
     */
    public static final class Types {

        /**
         * Enumerate resources of a kind.
         */
        public static final String LIST = "list";

        /**
         * Retrieve details of a single resource.
         */
        public static final String GET = "get";

        /**
         * Aggregated cross-resource summaries.
         */
        public static final String OVERVIEW = "overview";

        /**
         * Retrieve pod/container logs.
         */
        public static final String LOGS = "logs";

        /**
         * Retrieve Kubernetes events.
         */
        public static final String EVENTS = "events";

        /**
         * Retrieve Prometheus metrics.
         */
        public static final String METRICS = "metrics";

        /**
         * Multi-step diagnostic workflows.
         */
        public static final String DIAGNOSE = "diagnose";

        /**
         * Compare resources side-by-side.
         */
        public static final String COMPARE = "compare";

        /**
         * Readiness or upgrade assessments.
         */
        public static final String ASSESS = "assess";

        /**
         * Health or readiness checks.
         */
        public static final String CHECK = "check";

        private Types() {
        }
    }
}
