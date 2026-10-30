/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.guardrail;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.ExecutionModel;
import io.quarkiverse.mcp.server.SupportedExecutionModels;
import io.quarkiverse.mcp.server.ToolOutputGuardrail;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Output guardrail that redacts sensitive data in tool responses.
 *
 * <p>Delegates to {@link Redactor}, which applies pattern rules to every text value
 * (bearer and basic credentials, passwords, API keys, secrets, tokens, credentials in URLs,
 * PEM private keys, long base64 strings) and key-name rules to JSON objects (a value stored
 * under {@code password}, {@code secret}, {@code token}, ... is replaced regardless of its
 * shape). Matches are replaced with {@code [REDACTED]}.</p>
 *
 * <p>Custom patterns can be added via indexed configuration property
 * {@code mcp.guardrail.log-redaction.custom-patterns[N]}. Invalid regex patterns are
 * logged as warnings and skipped.</p>
 *
 * <p>Can be disabled via {@code mcp.guardrail.log-redaction.enabled=false}.</p>
 */
@Singleton
@SupportedExecutionModels({ExecutionModel.WORKER_THREAD, ExecutionModel.VIRTUAL_THREAD})
public class LogRedactionGuardrail implements ToolOutputGuardrail {

    @Inject
    ObjectMapper mapper;

    @Inject
    Redactor redactor;

    LogRedactionGuardrail() {
    }

    @Override
    public void apply(final ToolOutputContext ctx) {
        if (!redactor.isEnabled()) {
            return;
        }
        GuardedResponses.guard(ctx, mapper, GuardedResponses.OnError.FAIL_CLOSED, redactor::redactTree);
    }
}
