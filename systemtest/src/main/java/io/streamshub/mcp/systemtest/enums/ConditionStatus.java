/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest.enums;

/**
 * Status of the CR, found inside .status.conditions.*.status
 */
public enum ConditionStatus {
    /** Condition is true. */
    True,
    /** Condition is false. */
    False
}
