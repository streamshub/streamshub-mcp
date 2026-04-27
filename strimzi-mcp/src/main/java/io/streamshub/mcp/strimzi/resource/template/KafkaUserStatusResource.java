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
import io.streamshub.mcp.strimzi.dto.KafkaUserResponse;
import io.streamshub.mcp.strimzi.service.KafkaUserService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * MCP resource template for KafkaUser status.
 *
 * <p>Exposes user authentication type, ACL rules, quotas,
 * and readiness as a structured JSON resource for LLM context.
 * Never includes credential secret data.</p>
 */
@Singleton
public class KafkaUserStatusResource {

    @Inject
    KafkaUserService userService;

    @Inject
    ObjectMapper objectMapper;

    KafkaUserStatusResource() {
    }

    /**
     * Get the status of a KafkaUser as a JSON resource.
     *
     * @param namespace the Kubernetes namespace
     * @param name      the KafkaUser name
     * @return resource response with user status JSON
     * @throws JsonProcessingException if serialization fails
     */
    @ResourceTemplate(
        name = "kafka-user-status",
        uriTemplate = StrimziConstants.ResourceUris.USER_STATUS,
        description = "KafkaUser authentication, ACL rules, quotas,"
            + " and readiness status. Never includes credential secrets.",
        mimeType = "application/json"
    )
    public ResourceResponse getUserStatus(
        @ResourceTemplateArg(name = "namespace") final String namespace,
        @ResourceTemplateArg(name = "name") final String name
    ) throws JsonProcessingException {
        KafkaUserResponse user = userService.getUser(namespace, name);
        String json = objectMapper.writeValueAsString(user);
        String uri = StrimziConstants.ResourceUris.userStatus(namespace, name);
        return new ResourceResponse(TextResourceContents.create(uri, json));
    }
}
