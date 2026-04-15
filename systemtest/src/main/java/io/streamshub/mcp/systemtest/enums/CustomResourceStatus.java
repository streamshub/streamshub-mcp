/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest.enums;

/**
 * Status values for Strimzi custom resource conditions.
 */
public enum CustomResourceStatus {
    /** Resource is ready. */
    Ready,
    /** Resource is not ready. */
    NotReady,
    /** Resource has warnings. */
    Warning,
    /** Reconciliation is paused. */
    ReconciliationPaused
}
