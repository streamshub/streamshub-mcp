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
import io.streamshub.mcp.strimzi.dto.StrimziOperatorResponse;
import io.streamshub.mcp.strimzi.service.StrimziOperatorService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.List;

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
     * Get the status of Strimzi operators in a namespace as a JSON resource.
     *
     * @param namespace the Kubernetes namespace
     * @return resource response with operator status JSON
     * @throws JsonProcessingException if serialization fails
     */
    @ResourceTemplate(
        name = "strimzi-operator-status",
        uriTemplate = "strimzi://operator/{namespace}/status",
        description = "Strimzi operator deployment status,"
            + " version, readiness, and uptime.",
        mimeType = "application/json"
    )
    public ResourceResponse getOperatorStatus(
        @ResourceTemplateArg(name = "namespace") final String namespace
    ) throws JsonProcessingException {
        List<StrimziOperatorResponse> operators = operatorService.listOperators(namespace);
        String json = objectMapper.writeValueAsString(operators);
        String uri = "strimzi://operator/" + namespace + "/status";
        return new ResourceResponse(TextResourceContents.create(uri, json));
    }
}