/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.observability;

import jakarta.interceptor.InterceptorBinding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * CDI interceptor binding that records Micrometer metrics for MCP tool calls.
 *
 * <p>Apply at class level on MCP tool classes to enable the {@link ToolMetricsInterceptor},
 * which measures every {@code @Tool} method invocation &mdash; including thrown
 * {@code McpException} protocol errors that output guardrails cannot observe.</p>
 *
 * @see ToolMetricsInterceptor
 */
@InterceptorBinding
@Inherited
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface MeasuredTool {
}
