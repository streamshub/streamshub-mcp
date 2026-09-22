/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.guardrail;

import io.quarkiverse.mcp.server.ExecutionModel;
import io.quarkiverse.mcp.server.SupportedExecutionModels;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Rate limit guardrail for metrics tools.
 *
 * <p>Enforces a configurable requests-per-minute limit on metrics tool calls.
 * Configured via {@code mcp.guardrail.rate-limit.metrics-rpm}. Default is 0 (disabled).</p>
 */
@SupportedExecutionModels({ExecutionModel.WORKER_THREAD, ExecutionModel.VIRTUAL_THREAD})
@Singleton
public class MetricsRateLimitGuardrail extends AbstractRateLimitGuardrail {

    @ConfigProperty(name = "mcp.guardrail.rate-limit.metrics-rpm", defaultValue = "0")
    int rpm;

    MetricsRateLimitGuardrail() {
    }

    @Override
    protected String category() {
        return "metrics";
    }

    @Override
    protected int limitRpm() {
        return rpm;
    }
}
