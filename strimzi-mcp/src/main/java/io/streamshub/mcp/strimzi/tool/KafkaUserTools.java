/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.tool;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.WrapBusinessError;
import io.streamshub.mcp.common.guardrail.Guarded;
import io.streamshub.mcp.strimzi.config.StrimziToolsPrompts;
import io.streamshub.mcp.strimzi.dto.KafkaUserResponse;
import io.streamshub.mcp.strimzi.service.KafkaUserService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.List;

/**
 * MCP tools for KafkaUser operations.
 */
@Singleton
@Guarded
@WrapBusinessError(Exception.class)
public class KafkaUserTools {

    @Inject
    KafkaUserService userService;

    KafkaUserTools() {
    }

    /**
     * List KafkaUsers.
     *
     * @param clusterName optional Kafka cluster filter
     * @param namespace   optional namespace filter
     * @return list of user summary responses
     */
    @Tool(
        name = "list_kafka_users",
        description = "List KafkaUsers with authentication type,"
            + " authorization, ACL count, and readiness."
            + " Optionally filter by Kafka cluster.",
        annotations = @Tool.Annotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )
    )
    public List<KafkaUserResponse> listKafkaUsers(
        @ToolArg(
            description = StrimziToolsPrompts.CLUSTER_FILTER_DESC,
            required = false
        ) final String clusterName,
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace
    ) {
        return userService.listUsers(namespace, clusterName);
    }

    /**
     * Get a specific KafkaUser.
     *
     * @param userName  the user name
     * @param namespace optional namespace
     * @return the detailed user response
     */
    @Tool(
        name = "get_kafka_user",
        description = "Get detailed KafkaUser information including"
            + " ACL rules, quotas, and Kafka principal name."
            + " Never exposes credential secrets.",
        annotations = @Tool.Annotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )
    )
    public KafkaUserResponse getKafkaUser(
        @ToolArg(
            description = StrimziToolsPrompts.USER_NAME_DESC
        ) final String userName,
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace
    ) {
        return userService.getUser(namespace, userName);
    }
}
