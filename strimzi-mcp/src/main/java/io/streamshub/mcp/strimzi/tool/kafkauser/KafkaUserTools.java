/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.tool.kafkauser;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.quarkiverse.mcp.server.McpException;
import io.quarkiverse.mcp.server.MetaField;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkiverse.mcp.server.ToolGuardrails;
import io.quarkiverse.mcp.server.WrapBusinessError;
import io.streamshub.mcp.common.config.ToolMetaFields;
import io.streamshub.mcp.common.guardrail.ArgumentSanitizationGuardrail;
import io.streamshub.mcp.common.guardrail.GeneralRateLimitGuardrail;
import io.streamshub.mcp.common.guardrail.LogRedactionGuardrail;
import io.streamshub.mcp.common.guardrail.ResponseSizeLimitGuardrail;
import io.streamshub.mcp.common.observability.MeasuredTool;
import io.streamshub.mcp.strimzi.config.StrimziToolResources;
import io.streamshub.mcp.strimzi.config.StrimziToolsPrompts;
import io.streamshub.mcp.strimzi.dto.kafkauser.KafkaUserAclMatrixResponse;
import io.streamshub.mcp.strimzi.dto.kafkauser.KafkaUserListResponse;
import io.streamshub.mcp.strimzi.dto.kafkauser.KafkaUserResponse;
import io.streamshub.mcp.strimzi.service.kafkauser.KafkaUserService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
/**
 * MCP tools for KafkaUser operations.
 */
@Singleton
@MeasuredTool
@WrapBusinessError(value = Exception.class, unless = {ToolCallException.class, McpException.class})
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
    @WithSpan("tool.list_kafka_users")
    @MetaField(name = ToolMetaFields.TYPE, value = ToolMetaFields.Types.LIST)
    @MetaField(name = ToolMetaFields.RESOURCE, value = StrimziToolResources.KAFKA_USER)
    @Tool(
        name = "list_kafka_users",
        structuredContent = true,
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
    @ToolGuardrails(
        input  = { GeneralRateLimitGuardrail.class, ArgumentSanitizationGuardrail.class },
        output = { LogRedactionGuardrail.class, ResponseSizeLimitGuardrail.class })
    public KafkaUserListResponse listKafkaUsers(
        @ToolArg(
            description = StrimziToolsPrompts.CLUSTER_FILTER_DESC,
            required = false
        ) final String clusterName,
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace
    ) {
        List<KafkaUserResponse> items = userService.listUsers(namespace, clusterName);
        return new KafkaUserListResponse(items, items.size());
    }

    /**
     * Get a specific KafkaUser.
     *
     * @param userName  the user name
     * @param namespace optional namespace
     * @return the detailed user response
     */
    @WithSpan("tool.get_kafka_user")
    @MetaField(name = ToolMetaFields.TYPE, value = ToolMetaFields.Types.GET)
    @MetaField(name = ToolMetaFields.RESOURCE, value = StrimziToolResources.KAFKA_USER)
    @Tool(
        name = "get_kafka_user",
        structuredContent = true,
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
    @ToolGuardrails(
        input  = { GeneralRateLimitGuardrail.class, ArgumentSanitizationGuardrail.class },
        output = { LogRedactionGuardrail.class, ResponseSizeLimitGuardrail.class })
    public KafkaUserResponse getKafkaUser(
        @NotBlank @ToolArg(
            description = StrimziToolsPrompts.USER_NAME_DESC
        ) final String userName,
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace
    ) {
        return userService.getUser(namespace, userName);
    }

    /**
     * Build an ACL matrix across all KafkaUsers of a Kafka cluster.
     *
     * @param clusterName  the Kafka cluster name
     * @param namespace    optional namespace
     * @param resourceType optional ACL resource type filter
     * @return the ACL matrix response
     */
    @WithSpan("tool.get_kafka_user_acls_matrix")
    @MetaField(name = ToolMetaFields.TYPE, value = ToolMetaFields.Types.GET)
    @MetaField(name = ToolMetaFields.RESOURCE, value = StrimziToolResources.KAFKA_USER)
    @Tool(
        name = "get_kafka_user_acls_matrix",
        structuredContent = true,
        description = "Build an ACL matrix (resource to principal to operations) aggregated"
            + " across all KafkaUsers on a Kafka cluster, to answer questions like"
            + " \"who can write to this topic\"."
            + " Filter by resourceType: 'topic' (default), 'group', 'transactionalId', or 'cluster'."
            + " Flags over-broad allow grants (wildcard resource name or the 'All' operation) in broad_grants."
            + " On clusters with many users or ACL rules the response may be truncated;"
            + " narrow the query with resourceType or inspect individual users with get_kafka_user.",
        annotations = @Tool.Annotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )
    )
    @ToolGuardrails(
        input  = { GeneralRateLimitGuardrail.class, ArgumentSanitizationGuardrail.class },
        output = { LogRedactionGuardrail.class, ResponseSizeLimitGuardrail.class })
    public KafkaUserAclMatrixResponse getKafkaUserAclsMatrix(
        @NotBlank @ToolArg(
            description = StrimziToolsPrompts.CLUSTER_DESC
        ) final String clusterName,
        @ToolArg(
            description = StrimziToolsPrompts.NS_DESC,
            required = false
        ) final String namespace,
        @ToolArg(
            description = "ACL resource type to filter on: 'topic', 'group',"
                + " 'transactionalId', or 'cluster'. Defaults to 'topic'.",
            required = false
        ) final String resourceType
    ) {
        return userService.getAclMatrix(namespace, clusterName, resourceType);
    }
}
