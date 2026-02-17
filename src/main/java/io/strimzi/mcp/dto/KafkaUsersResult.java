/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * Result object for Kafka User discovery operations.
 *
 * @param users      the list of discovered Kafka Users
 * @param totalUsers the total number of Users found
 * @param status     the status of the operation
 * @param message    a human-readable message describing the result
 * @param timestamp  the time this result was generated
 */
public record KafkaUsersResult(
    @JsonProperty("users") List<KafkaUserInfo> users,
    @JsonProperty("total_users") int totalUsers,
    @JsonProperty("status") String status,
    @JsonProperty("message") String message,
    @JsonProperty("timestamp") Instant timestamp
) {
    /**
     * Creates a successful result with the discovered Users.
     *
     * @param users the list of discovered Kafka Users
     * @return a successful KafkaUsersResult
     */
    public static KafkaUsersResult of(List<KafkaUserInfo> users) {
        String message = users.size() == 1 ?
            String.format("Found 1 Kafka User: %s", users.get(0).getDisplayName()) :
            String.format("Found %d Kafka Users", users.size());

        return new KafkaUsersResult(
            users,
            users.size(),
            "SUCCESS",
            message,
            Instant.now()
        );
    }

    /**
     * Creates an empty result when no Users are found in the namespace.
     *
     * @param namespace the Kubernetes namespace that was searched
     * @return an empty KafkaUsersResult
     */
    public static KafkaUsersResult empty(String namespace) {
        return new KafkaUsersResult(
            List.of(),
            0,
            "SUCCESS",
            String.format("No Kafka Users found in namespace '%s'", namespace),
            Instant.now()
        );
    }

    /**
     * Creates an error result when User discovery fails.
     *
     * @param namespace    the Kubernetes namespace that was searched
     * @param errorMessage the error description
     * @return an error KafkaUsersResult
     */
    public static KafkaUsersResult error(String namespace, String errorMessage) {
        return new KafkaUsersResult(
            List.of(),
            0,
            "ERROR",
            String.format("Error discovering Kafka Users in namespace '%s': %s", namespace, errorMessage),
            Instant.now()
        );
    }
}