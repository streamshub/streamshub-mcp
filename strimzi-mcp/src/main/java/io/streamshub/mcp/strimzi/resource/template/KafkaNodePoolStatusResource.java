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
import io.streamshub.mcp.strimzi.dto.KafkaNodePoolResponse;
import io.streamshub.mcp.strimzi.service.KafkaNodePoolService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * MCP resource template for KafkaNodePool status.
 *
 * <p>Exposes node pool status, ready replicas, roles, and storage
 * configuration as a structured JSON resource for LLM context.</p>
 */
@Singleton
public class KafkaNodePoolStatusResource {

    @Inject
    KafkaNodePoolService nodePoolService;

    @Inject
    ObjectMapper objectMapper;

    KafkaNodePoolStatusResource() {
    }

    /**
     * Get the status of a KafkaNodePool as a JSON resource.
     *
     * @param namespace the Kubernetes namespace
     * @param name      the KafkaNodePool name
     * @return resource response with node pool status JSON
     * @throws JsonProcessingException if serialization fails
     */
    @ResourceTemplate(
        name = "kafka-nodepool-status",
        uriTemplate = "strimzi://kafka.strimzi.io/v1/namespaces/{namespace}/kafkanodepools/{name}/status",
        description = "KafkaNodePool status, ready replicas, roles,"
            + " and storage configuration.",
        mimeType = "application/json"
    )
    public ResourceResponse getNodePoolStatus(
        @ResourceTemplateArg(name = "namespace") final String namespace,
        @ResourceTemplateArg(name = "name") final String name
    ) throws JsonProcessingException {
        KafkaNodePoolResponse nodePool = nodePoolService.getNodePool(namespace, null, name);
        String json = objectMapper.writeValueAsString(nodePool);
        String uri = "strimzi://kafka.strimzi.io/v1/namespaces/" + namespace
            + "/kafkanodepools/" + name + "/status";
        return new ResourceResponse(TextResourceContents.create(uri, json));
    }
}
