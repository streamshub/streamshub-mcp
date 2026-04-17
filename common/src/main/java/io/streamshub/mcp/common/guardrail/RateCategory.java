/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.guardrail;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Assigns a tool method to a rate limit category.
 *
 * <p>When {@link RateLimitFilter} is active, tool calls are throttled
 * per category. Methods without this annotation default to the
 * {@code general} category.</p>
 *
 * <p>Standard categories: {@code "log"}, {@code "metrics"}, {@code "general"}.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateCategory {

    /**
     * The rate limit category name.
     *
     * @return the category
     */
    String value();
}
