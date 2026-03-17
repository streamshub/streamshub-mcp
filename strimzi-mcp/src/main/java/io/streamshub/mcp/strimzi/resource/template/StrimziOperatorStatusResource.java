/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.resource.template;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.ResourceResponse;
import io.quarkiverse.mcp.server.ResourceTemplate;
import io.quarkiverse.mcp.server.ResourceTemplateArg;
import io.quarkiverse.mcp.server.TextResourceContents;
import io.streamshub.mcp.strimzi.config.StrimziConstants;
import io.streamshub.mcp.strimzi.dto.StrimziOperatorResponse;
import io.streamshub.mcp.strimzi.service.StrimziOperatorService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * MCP resource template for Strimzi operator status.
 *
 * <p>Exposes operator deployment status, version, and health
 * as a structured JSON resource for LLM context.</p>
 */
@Singleton
public class StrimziOperatorStatusResource {

    @Inject
    StrimziOperatorService operatorService;

    @Inject
    ObjectMapper objectMapper;

    StrimziOperatorStatusResource() {
    }

    /**
     * Get the status of a specific Strimzi operator as a JSON resource.
     *
     * @param namespace the Kubernetes namespace
     * @param name      the operator deployment name
     * @return resource response with operator status JSON
     * @throws JsonProcessingException if serialization fails
     */
    @ResourceTemplate(
        name = "strimzi-operator-status",
        uriTemplate = StrimziConstants.ResourceUris.OPERATOR_STATUS,
        description = "Strimzi operator deployment status,"
            + " version, readiness, and uptime.",
        mimeType = "application/json"
    )
    public ResourceResponse getOperatorStatus(
        @ResourceTemplateArg(name = "namespace") final String namespace,
        @ResourceTemplateArg(name = "name") final String name
    ) throws JsonProcessingException {
        StrimziOperatorResponse operator = operatorService.getOperator(namespace, name);
        String json = objectMapper.writeValueAsString(operator);
        String uri = StrimziConstants.ResourceUris.operatorStatus(namespace, name);
        return new ResourceResponse(TextResourceContents.create(uri, json));
    }
}
