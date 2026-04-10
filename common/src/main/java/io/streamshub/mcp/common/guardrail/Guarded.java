/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.guardrail;

import jakarta.interceptor.InterceptorBinding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * CDI interceptor binding that activates the {@link GuardrailInterceptor}
 * filter chain on annotated tool classes.
 *
 * <p>Apply at class level on MCP tool classes to enable input validation
 * and output sanitization via the pluggable {@link GuardrailFilter} chain.</p>
 *
 * @see GuardrailFilter
 * @see GuardrailInterceptor
 */
@InterceptorBinding
@Inherited
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Guarded {
}
