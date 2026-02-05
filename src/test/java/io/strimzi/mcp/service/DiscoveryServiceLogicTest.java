package io.strimzi.mcp.service;

import io.strimzi.mcp.service.infra.StrimziDiscoveryService;
import io.strimzi.mcp.service.infra.StrimziDiscoveryService.KafkaClusterInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the business logic methods in StrimziDiscoveryService that don't require Kubernetes client.
 * This tests the normalization, validation, and data transformation logic.
 */
class DiscoveryServiceLogicTest {

    @Test
    void normalizeNamespace_handles_various_inputs() {
        StrimziDiscoveryService service = new StrimziDiscoveryService();

        // Standard cases
        assertEquals("kafka", service.normalizeNamespace("kafka"));
        assertEquals("production", service.normalizeNamespace("production"));

        // Case normalization
        assertEquals("kafka", service.normalizeNamespace("KAFKA"));
        assertEquals("kafka", service.normalizeNamespace("Kafka"));
        assertEquals("strimzi-system", service.normalizeNamespace("STRIMZI-SYSTEM"));

        // Whitespace handling
        assertEquals("kafka", service.normalizeNamespace(" kafka "));
        assertEquals("kafka", service.normalizeNamespace("  kafka  "));
        assertEquals("my-namespace", service.normalizeNamespace(" My-NAMESPACE "));

        // Empty/null handling
        assertNull(service.normalizeNamespace(null));
        assertNull(service.normalizeNamespace(""));
        assertNull(service.normalizeNamespace(" "));
        assertNull(service.normalizeNamespace("   "));

        // Complex namespace names
        assertEquals("kafka-prod-v1", service.normalizeNamespace("KAFKA-PROD-V1"));
        assertEquals("team.namespace", service.normalizeNamespace("Team.Namespace"));
    }

    @Test
    void normalizeNamespace_preserves_valid_kubernetes_names() {
        StrimziDiscoveryService service = new StrimziDiscoveryService();

        // Kubernetes valid names should be preserved (after lowercasing)
        assertEquals("abc", service.normalizeNamespace("abc"));
        assertEquals("a-b-c", service.normalizeNamespace("a-b-c"));
        assertEquals("namespace123", service.normalizeNamespace("namespace123"));
        assertEquals("team.kafka", service.normalizeNamespace("team.kafka"));

        // With different cases
        assertEquals("my-prod-kafka", service.normalizeNamespace("My-Prod-Kafka"));
        assertEquals("ns.v2.prod", service.normalizeNamespace("NS.V2.PROD"));
    }

    @Test
    void normalizeClusterName_handles_various_inputs() {
        StrimziDiscoveryService service = new StrimziDiscoveryService();

        // Standard cases
        assertEquals("my-cluster", service.normalizeClusterName("my-cluster"));
        assertEquals("production", service.normalizeClusterName("production"));

        // Case normalization
        assertEquals("my-cluster", service.normalizeClusterName("MY-CLUSTER"));
        assertEquals("kafkacluster", service.normalizeClusterName("KafkaCluster"));

        // Whitespace handling
        assertEquals("my-cluster", service.normalizeClusterName(" my-cluster "));
        assertEquals("cluster-name", service.normalizeClusterName("  CLUSTER-NAME  "));

        // Empty/null handling
        assertNull(service.normalizeClusterName(null));
        assertNull(service.normalizeClusterName(""));
        assertNull(service.normalizeClusterName(" "));
        assertNull(service.normalizeClusterName("   "));

        // Complex cluster names
        assertEquals("prod-events-v2", service.normalizeClusterName("PROD-EVENTS-V2"));
        assertEquals("team.main", service.normalizeClusterName("Team.Main"));
    }

    @Test
    void getDefaultNamespace_returns_valid_namespace() {
        StrimziDiscoveryService service = new StrimziDiscoveryService();

        String defaultNs = service.getDefaultNamespace();

        assertNotNull(defaultNs);
        assertFalse(defaultNs.isEmpty());
        assertFalse(defaultNs.isBlank());

        // Should be either environment value or "default"
        assertTrue(defaultNs.equals("default") || !defaultNs.isEmpty());
    }

    @Test
    void kafkaClusterInfo_display_name_formatting() {
        // Test display name generation logic
        KafkaClusterInfo cluster1 = new KafkaClusterInfo("my-cluster", "kafka", List.of());
        assertEquals("my-cluster (namespace: kafka)", cluster1.getDisplayName());

        KafkaClusterInfo cluster2 = new KafkaClusterInfo("production-events", "prod", List.of());
        assertEquals("production-events (namespace: prod)", cluster2.getDisplayName());

        // Test with complex names
        KafkaClusterInfo cluster3 = new KafkaClusterInfo("team.main-cluster", "ns-v2.prod", List.of());
        assertEquals("team.main-cluster (namespace: ns-v2.prod)", cluster3.getDisplayName());

        // Test edge cases
        KafkaClusterInfo clusterEmpty = new KafkaClusterInfo("", "", List.of());
        assertEquals(" (namespace: )", clusterEmpty.getDisplayName());

        KafkaClusterInfo clusterSingle = new KafkaClusterInfo("a", "b", List.of());
        assertEquals("a (namespace: b)", clusterSingle.getDisplayName());
    }

    @Test
    void kafkaClusterInfo_equality_and_identity() {
        List<Object> emptyConditions = List.of();

        KafkaClusterInfo cluster1 = new KafkaClusterInfo("cluster", "namespace", emptyConditions);
        KafkaClusterInfo cluster2 = new KafkaClusterInfo("cluster", "namespace", emptyConditions);
        KafkaClusterInfo cluster3 = new KafkaClusterInfo("different", "namespace", emptyConditions);

        // Test equality (records implement equals/hashCode automatically)
        assertEquals(cluster1, cluster2);
        assertNotEquals(cluster1, cluster3);

        // Test field access
        assertEquals("cluster", cluster1.name());
        assertEquals("namespace", cluster1.namespace());
        assertEquals(emptyConditions, cluster1.conditions());
    }

    @Test
    void normalization_consistency_and_idempotence() {
        StrimziDiscoveryService service = new StrimziDiscoveryService();

        // Test idempotence - normalizing twice should give same result
        String input = "  My-CLUSTER-Name  ";
        String normalized = service.normalizeClusterName(input);
        String normalizedTwice = service.normalizeClusterName(normalized);

        assertEquals(normalized, normalizedTwice);
        assertEquals("my-cluster-name", normalized);

        // Test namespace idempotence
        String nsInput = "  KAFKA-PRODUCTION  ";
        String nsNormalized = service.normalizeNamespace(nsInput);
        String nsNormalizedTwice = service.normalizeNamespace(nsNormalized);

        assertEquals(nsNormalized, nsNormalizedTwice);
        assertEquals("kafka-production", nsNormalized);
    }

    @Test
    void normalization_handles_edge_cases() {
        StrimziDiscoveryService service = new StrimziDiscoveryService();

        // Very long names
        String longName = "a".repeat(100);
        assertEquals(longName.toLowerCase(), service.normalizeNamespace(longName.toUpperCase()));

        // Names with numbers
        assertEquals("kafka123", service.normalizeNamespace("KAFKA123"));
        assertEquals("cluster-v2", service.normalizeClusterName("Cluster-V2"));

        // Names with special characters
        assertEquals("my-cluster.v1", service.normalizeNamespace("My-Cluster.V1"));
        assertEquals("team_kafka", service.normalizeClusterName("TEAM_KAFKA"));

        // Single character names
        assertEquals("a", service.normalizeNamespace("A"));
        assertEquals("x", service.normalizeClusterName(" X "));
    }

    @Test
    void business_logic_validation() {
        StrimziDiscoveryService service = new StrimziDiscoveryService();

        // Test that normalization preserves valid Kubernetes naming
        // Kubernetes names must be lowercase alphanumeric with dashes and dots
        String[] validInputs = {
            "kafka", "my-cluster", "team.namespace", "prod-v1.2", "a", "namespace123"
        };

        String[] expectedOutputs = {
            "kafka", "my-cluster", "team.namespace", "prod-v1.2", "a", "namespace123"
        };

        for (int i = 0; i < validInputs.length; i++) {
            assertEquals(expectedOutputs[i], service.normalizeNamespace(validInputs[i]));
            assertEquals(expectedOutputs[i], service.normalizeClusterName(validInputs[i]));
        }

        // Test case conversion for uppercase inputs
        String[] uppercaseInputs = {
            "KAFKA", "MY-CLUSTER", "TEAM.NAMESPACE", "PROD-V1.2"
        };

        String[] expectedLowercase = {
            "kafka", "my-cluster", "team.namespace", "prod-v1.2"
        };

        for (int i = 0; i < uppercaseInputs.length; i++) {
            assertEquals(expectedLowercase[i], service.normalizeNamespace(uppercaseInputs[i]));
            assertEquals(expectedLowercase[i], service.normalizeClusterName(uppercaseInputs[i]));
        }
    }

    @Test
    void null_safety_validation() {
        StrimziDiscoveryService service = new StrimziDiscoveryService();

        // All normalization methods should handle null safely
        assertNull(service.normalizeNamespace(null));
        assertNull(service.normalizeClusterName(null));

        // Should not throw exceptions
        assertDoesNotThrow(() -> {
            service.normalizeNamespace(null);
            service.normalizeClusterName(null);
            service.getDefaultNamespace();
        });

        // KafkaClusterInfo should handle null conditions
        KafkaClusterInfo clusterWithNullConditions = new KafkaClusterInfo("test", "ns", null);
        assertEquals("test", clusterWithNullConditions.name());
        assertEquals("ns", clusterWithNullConditions.namespace());
        assertNull(clusterWithNullConditions.conditions());
        assertEquals("test (namespace: ns)", clusterWithNullConditions.getDisplayName());
    }

    @Test
    void input_validation_scenarios() {
        StrimziDiscoveryService service = new StrimziDiscoveryService();

        // Test realistic input scenarios that users might provide
        String[][] testCases = {
            // Input, Expected namespace, Expected cluster
            {"kafka", "kafka", "kafka"},
            {"PRODUCTION", "production", "production"},
            {" staging ", "staging", "staging"},
            {"  Dev-Environment  ", "dev-environment", "dev-environment"},
            {"TEAM.KAFKA.V1", "team.kafka.v1", "team.kafka.v1"},
            {"ns-prod_v2", "ns-prod_v2", "ns-prod_v2"},
            {"123test", "123test", "123test"}
        };

        for (String[] testCase : testCases) {
            String input = testCase[0];
            String expectedNs = testCase[1];
            String expectedCluster = testCase[2];

            assertEquals(expectedNs, service.normalizeNamespace(input),
                "Namespace normalization failed for: " + input);
            assertEquals(expectedCluster, service.normalizeClusterName(input),
                "Cluster normalization failed for: " + input);
        }

        // Test invalid inputs that should return null
        String[] invalidInputs = {null, "", " ", "   ", "\t", "\n"};

        for (String invalid : invalidInputs) {
            assertNull(service.normalizeNamespace(invalid),
                "Should return null for invalid namespace: '" + invalid + "'");
            assertNull(service.normalizeClusterName(invalid),
                "Should return null for invalid cluster: '" + invalid + "'");
        }
    }
}