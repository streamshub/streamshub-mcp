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
import io.streamshub.mcp.strimzi.dto.KafkaTopicResponse;
import io.streamshub.mcp.strimzi.service.KafkaTopicService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * MCP resource template for KafkaTopic status.
 *
 * <p>Exposes topic status, conditions, and configuration
 * (partitions, replicas) as a structured JSON resource for LLM context.</p>
 */
@Singleton
public class KafkaTopicStatusResource {

    @Inject
    KafkaTopicService topicService;

    @Inject
    ObjectMapper objectMapper;

    KafkaTopicStatusResource() {
    }

    /**
     * Get the status of a KafkaTopic as a JSON resource.
     *
     * @param namespace the Kubernetes namespace
     * @param name      the KafkaTopic name
     * @return resource response with topic status JSON
     * @throws JsonProcessingException if serialization fails
     */
    @ResourceTemplate(
        name = "kafka-topic-status",
        uriTemplate = "strimzi://kafka.strimzi.io/v1/namespaces/{namespace}/kafkatopics/{name}/status",
        description = "KafkaTopic status, conditions, and topic"
            + " configuration including partitions and replicas.",
        mimeType = "application/json"
    )
    public ResourceResponse getTopicStatus(
        @ResourceTemplateArg(name = "namespace") final String namespace,
        @ResourceTemplateArg(name = "name") final String name
    ) throws JsonProcessingException {
        KafkaTopicResponse topic = topicService.getTopic(namespace, null, name);
        String json = objectMapper.writeValueAsString(topic);
        String uri = "strimzi://kafka.strimzi.io/v1/namespaces/" + namespace
            + "/kafkatopics/" + name + "/status";
        return new ResourceResponse(TextResourceContents.create(uri, json));
    }
}
