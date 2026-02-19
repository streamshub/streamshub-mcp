/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.tool;

import io.streamshub.mcp.dto.ToolError;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolErrorTest {

    ToolErrorTest() {
    }

    @Test
    void simple_error_creation() {
        ToolError error = ToolError.of("Operation failed");

        assertEquals("Operation failed", error.error());
        assertNull(error.details());
    }

    @Test
    void error_with_exception_details() {
        RuntimeException cause = new RuntimeException("Kubernetes API unreachable");
        ToolError error = ToolError.of("Failed to retrieve cluster info", cause);

        assertEquals("Failed to retrieve cluster info", error.error());
        assertEquals("Kubernetes API unreachable", error.details());
    }

    @Test
    void error_with_null_exception() {
        ToolError error = ToolError.of("Something went wrong", (Exception) null);

        assertEquals("Something went wrong", error.error());
        assertNull(error.details());
    }

    @Test
    void validation_error_creation() {
        ToolError error = ToolError.validation("Invalid namespace format");

        assertEquals("Validation error: Invalid namespace format", error.error());
        assertNull(error.details());
    }

    @Test
    void validation_error_with_empty_message() {
        ToolError error = ToolError.validation("");

        assertEquals("Validation error: ", error.error());
        assertNull(error.details());
    }

    @Test
    void validation_error_with_null_message() {
        ToolError error = ToolError.validation(null);

        assertEquals("Validation error: null", error.error());
        assertNull(error.details());
    }

    @Test
    void nested_exception_handling() {
        IllegalArgumentException root = new IllegalArgumentException("Invalid cluster name");
        RuntimeException wrapper = new RuntimeException("Request processing failed", root);

        ToolError error = ToolError.of("API call failed", wrapper);

        assertEquals("API call failed", error.error());
        assertEquals("Request processing failed", error.details());
    }

    @Test
    void exception_with_null_message() {
        RuntimeException exceptionWithNullMessage = new RuntimeException((String) null);
        ToolError error = ToolError.of("Failed", exceptionWithNullMessage);

        assertEquals("Failed", error.error());
        assertNull(error.details()); // RuntimeException with null message should result in null details
    }

    @Test
    void exception_with_empty_message() {
        RuntimeException exceptionWithEmptyMessage = new RuntimeException("");
        ToolError error = ToolError.of("Failed", exceptionWithEmptyMessage);

        assertEquals("Failed", error.error());
        assertEquals("", error.details());
    }

    @Test
    void error_message_preservation() {
        String[] testMessages = {
            "Connection timeout after 30 seconds",
            "Namespace 'production' not found",
            "Invalid YAML configuration: line 42",
            "Permission denied: insufficient RBAC rights",
            "CRD validation failed: missing required field 'spec.replicas'"
        };

        for (String message : testMessages) {
            ToolError directError = ToolError.of(message);
            assertEquals(message, directError.error());

            RuntimeException exception = new RuntimeException(message);
            ToolError exceptionError = ToolError.of("Operation failed", exception);
            assertEquals("Operation failed", exceptionError.error());
            assertEquals(message, exceptionError.details());
        }
    }

    @Test
    void common_error_scenarios() {
        // Kubernetes API errors
        ToolError k8sError = ToolError.of("Kubernetes operation failed",
            new RuntimeException("connection refused: connect to api-server:6443"));
        assertEquals("Kubernetes operation failed", k8sError.error());
        assertTrue(k8sError.details().contains("connection refused"));

        // Permission errors
        ToolError permError = ToolError.of("Access denied",
            new SecurityException("User 'alice' cannot list pods in namespace 'production'"));
        assertEquals("Access denied", permError.error());
        assertTrue(permError.details().contains("cannot list pods"));

        // Validation errors
        ToolError validationError = ToolError.validation("Cluster name cannot contain uppercase letters");
        assertTrue(validationError.error().startsWith("Validation error:"));
        assertTrue(validationError.error().contains("uppercase letters"));

        // Network timeout errors
        ToolError timeoutError = ToolError.of("Request timeout",
            new java.net.SocketTimeoutException("Read timeout"));
        assertEquals("Request timeout", timeoutError.error());
        assertEquals("Read timeout", timeoutError.details());

        // Resource not found errors
        ToolError notFoundError = ToolError.of("Resource not found",
            new IllegalArgumentException("Namespace 'missing-ns' does not exist"));
        assertEquals("Resource not found", notFoundError.error());
        assertTrue(notFoundError.details().contains("missing-ns"));
    }

    @Test
    void error_consistency() {
        // Test that the same input produces consistent results
        String errorMsg = "Consistent error message";
        RuntimeException exception = new RuntimeException("Consistent exception message");

        ToolError error1 = ToolError.of(errorMsg, exception);
        ToolError error2 = ToolError.of(errorMsg, exception);

        assertEquals(error1.error(), error2.error());
        assertEquals(error1.details(), error2.details());

        // Test validation consistency
        ToolError validation1 = ToolError.validation("same message");
        ToolError validation2 = ToolError.validation("same message");

        assertEquals(validation1.error(), validation2.error());
        assertEquals(validation1.details(), validation2.details());
    }

    @Test
    void edge_case_handling() {
        // Very long error messages
        String longMessage = "x".repeat(1000);
        ToolError longError = ToolError.of(longMessage);
        assertEquals(longMessage, longError.error());

        // Unicode characters
        String unicodeMessage = "Ошибка: соединение прервано";
        ToolError unicodeError = ToolError.of(unicodeMessage);
        assertEquals(unicodeMessage, unicodeError.error());

        // Special characters
        String specialMessage = "Error: \"connection failed\" at line 123 (code: 500)";
        ToolError specialError = ToolError.of(specialMessage);
        assertEquals(specialMessage, specialError.error());

        // Newlines and tabs
        String multilineMessage = "Error occurred:\n\tConnection failed\n\tRetry attempted";
        RuntimeException multilineException = new RuntimeException(multilineMessage);
        ToolError multilineError = ToolError.of("Network error", multilineException);
        assertEquals("Network error", multilineError.error());
        assertEquals(multilineMessage, multilineError.details());
    }

    @Test
    void exception_type_variety() {
        // Test various exception types are handled correctly
        Exception[] exceptions = {
            new RuntimeException("Runtime error"),
            new IllegalArgumentException("Invalid argument"),
            new IllegalStateException("Invalid state"),
            new UnsupportedOperationException("Not supported"),
            new java.io.IOException("I/O error"),
            new java.net.ConnectException("Connection failed"),
            new java.util.concurrent.TimeoutException("Operation timed out")
        };

        for (Exception ex : exceptions) {
            ToolError error = ToolError.of("Operation failed", ex);
            assertEquals("Operation failed", error.error());
            assertEquals(ex.getMessage(), error.details());
        }
    }

    @Test
    void error_formatting_for_mcp_tools() {
        // Test scenarios that would be common in MCP tool responses

        // Namespace discovery errors
        ToolError multipleNsError = ToolError.of(
            "Multiple namespaces found",
            new RuntimeException("Found Strimzi in: kafka, strimzi-system, production")
        );
        assertTrue(multipleNsError.details().contains("kafka, strimzi-system"));

        // Cluster connection errors
        ToolError connectionError = ToolError.of(
            "Failed to connect to cluster",
            new RuntimeException("dial tcp 192.168.1.100:6443: connection refused")
        );
        assertTrue(connectionError.details().contains("connection refused"));

        // CRD errors
        ToolError crdError = ToolError.of(
            "Kafka CRD not found",
            new RuntimeException("the server could not find the requested resource (get kafkas.kafka.strimzi.io)")
        );
        assertTrue(crdError.details().contains("kafkas.kafka.strimzi.io"));

        // Permission errors
        ToolError rbacError = ToolError.of(
            "Insufficient permissions",
            new SecurityException("pods is forbidden: User \"alice\" cannot list resource \"pods\" in API group \"\" in the namespace \"production\"")
        );
        assertTrue(rbacError.details().contains("forbidden"));
        assertTrue(rbacError.details().contains("production"));
    }

    @Test
    void null_safety() {
        // All methods should handle nulls gracefully without throwing NPE
        assertDoesNotThrow(() -> ToolError.of(null));
        assertDoesNotThrow(() -> ToolError.of(null, (Exception) null));
        assertDoesNotThrow(() -> ToolError.of("message", (Exception) null));
        assertDoesNotThrow(() -> ToolError.validation(null));

        // Test results with nulls
        ToolError nullError = ToolError.of(null);
        assertNull(nullError.error());
        assertNull(nullError.details());

        ToolError nullValidation = ToolError.validation(null);
        assertEquals("Validation error: null", nullValidation.error());
        assertNull(nullValidation.details());
    }

    @Test
    void realistic_kubernetes_error_scenarios() {
        // Based on real Kubernetes/Strimzi error messages

        // Pod not ready
        ToolError podNotReady = ToolError.of(
            "Pod not ready",
            new RuntimeException("Pod kafka-0 is not ready: container strimzi-kafka is not ready")
        );
        assertTrue(podNotReady.details().contains("not ready"));

        // Operator deployment failed
        ToolError deploymentError = ToolError.of(
            "Operator deployment failed",
            new RuntimeException("Deployment strimzi-cluster-operator has 0/1 ready replicas")
        );
        assertTrue(deploymentError.details().contains("0/1 ready"));

        // Topic creation failed
        ToolError topicError = ToolError.of(
            "Topic creation failed",
            new RuntimeException("Topic 'my-topic' validation failed: partitions must be >= 1")
        );
        assertTrue(topicError.details().contains("partitions must be"));

        // Resource quota exceeded
        ToolError quotaError = ToolError.of(
            "Resource quota exceeded",
            new RuntimeException("exceeded quota: pods \"2\", used: \"10\", limited: \"10\"")
        );
        assertTrue(quotaError.details().contains("exceeded quota"));
    }
}
