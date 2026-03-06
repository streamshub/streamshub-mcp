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
import io.streamshub.mcp.strimzi.dto.KafkaClusterResponse;
import io.streamshub.mcp.strimzi.service.KafkaService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * MCP resource template for Kafka cluster status.
 *
 * <p>Exposes cluster status and conditions as a structured JSON resource
 * that LLM clients can attach to conversations for immediate context.</p>
 */
@Singleton
public class KafkaClusterStatusResource {

    @Inject
    KafkaService kafkaService;

    @Inject
    ObjectMapper objectMapper;

    KafkaClusterStatusResource() {
    }

    /**
     * Get the status of a Kafka cluster as a JSON resource.
     *
     * @param namespace the Kubernetes namespace
     * @param name      the Kafka cluster name
     * @return resource response with cluster status JSON
     * @throws JsonProcessingException if serialization fails
     */
    @ResourceTemplate(
        name = "kafka-cluster-status",
        uriTemplate = "strimzi://cluster/{namespace}/{name}/status",
        description = "Current status and conditions of a Kafka cluster"
            + " including readiness, version, listeners, and replica counts.",
        mimeType = "application/json"
    )
    public ResourceResponse getClusterStatus(
        @ResourceTemplateArg(name = "namespace") final String namespace,
        @ResourceTemplateArg(name = "name") final String name
    ) throws JsonProcessingException {
        KafkaClusterResponse cluster = kafkaService.getCluster(namespace, name);
        String json = objectMapper.writeValueAsString(cluster);
        String uri = "strimzi://cluster/" + namespace + "/" + name + "/status";
        return new ResourceResponse(TextResourceContents.create(uri, json));
    }
}
