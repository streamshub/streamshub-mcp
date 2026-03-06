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

import java.util.List;

/**
 * MCP resource template for Kafka cluster topology.
 *
 * <p>Exposes node pool information (roles, replica counts, storage)
 * as a structured JSON resource for LLM context.</p>
 */
@Singleton
public class KafkaClusterTopologyResource {

    @Inject
    KafkaNodePoolService nodePoolService;

    @Inject
    ObjectMapper objectMapper;

    KafkaClusterTopologyResource() {
    }

    /**
     * Get the topology of a Kafka cluster as a JSON resource.
     *
     * @param namespace the Kubernetes namespace
     * @param name      the Kafka cluster name
     * @return resource response with node pool topology JSON
     * @throws JsonProcessingException if serialization fails
     */
    @ResourceTemplate(
        name = "kafka-cluster-topology",
        uriTemplate = "strimzi://cluster/{namespace}/{name}/topology",
        description = "Cluster topology: node pools, roles,"
            + " replica counts, and storage configuration.",
        mimeType = "application/json"
    )
    public ResourceResponse getClusterTopology(
        @ResourceTemplateArg(name = "namespace") final String namespace,
        @ResourceTemplateArg(name = "name") final String name
    ) throws JsonProcessingException {
        List<KafkaNodePoolResponse> nodePools = nodePoolService.listNodePools(namespace, name);
        String json = objectMapper.writeValueAsString(nodePools);
        String uri = "strimzi://cluster/" + namespace + "/" + name + "/topology";
        return new ResourceResponse(TextResourceContents.create(uri, json));
    }
}
