/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest;

/**
 * Definitions of test tags
 */
public final class TestTags {

    private TestTags() {
    }

    /**
     * Tag for acceptance tests
     */
    public static final String ACCEPTANCE = "acceptance";

    /**
     * Tag for regression tests
     */
    public static final String REGRESSION = "regression";

    /**
     * Tag for tools tests
     */
    public static final String TOOLS = "tools";

    /**
     * Tag for Drain Cleaner tests
     */
    public static final String DRAIN_CLEANER = "drain-cleaner";

    /**
     * Tag for security tests
     */
    public static final String SECURITY = "security";

    /**
     * Tag for resilience tests
     */
    public static final String RESILIENCE = "resilience";

    /**
     * Tag for metrics tests
     */
    public static final String METRICS = "metrics";

    /**
     * Tag for metrics tests with Prometheus provider
     */
    public static final String PROMETHEUS = "prometheus";

    /**
     * Tag for logs tests
     */
    public static final String LOGS = "logs";

    /**
     * Tag for metrics tests with Loki provider
     */
    public static final String LOKI = "loki";
}
