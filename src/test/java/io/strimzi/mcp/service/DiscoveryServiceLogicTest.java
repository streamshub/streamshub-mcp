/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.service;

import io.strimzi.mcp.dto.KafkaClusterInfo;
import io.strimzi.mcp.service.infra.PodsService;
import io.strimzi.mcp.service.infra.StrimziDiscoveryService;
import io.strimzi.mcp.util.InputUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the business logic methods in StrimziDiscoveryService that don't require Kubernetes client.
 * This tests the normalization, validation, and data transformation logic.
 */
class DiscoveryServiceLogicTest {

    DiscoveryServiceLogicTest() {
    }

    @Test
    void normalizeNamespace_handles_various_inputs() {
        // Standard cases
        assertEquals("kafka", InputUtils.normalizeNamespace("kafka"));
        assertEquals("production", InputUtils.normalizeNamespace("production"));

        // Case normalization
        assertEquals("kafka", InputUtils.normalizeNamespace("KAFKA"));
        assertEquals("kafka", InputUtils.normalizeNamespace("Kafka"));
        assertEquals("strimzi-system", InputUtils.normalizeNamespace("STRIMZI-SYSTEM"));

        // Whitespace handling
        assertEquals("kafka", InputUtils.normalizeNamespace(" kafka "));
        assertEquals("kafka", InputUtils.normalizeNamespace("  kafka  "));
        assertEquals("my-namespace", InputUtils.normalizeNamespace(" My-NAMESPACE "));

        // Empty/null handling
        assertNull(InputUtils.normalizeNamespace(null));
        assertNull(InputUtils.normalizeNamespace(""));
        assertNull(InputUtils.normalizeNamespace(" "));
        assertNull(InputUtils.normalizeNamespace("   "));

        // Complex namespace names
        assertEquals("kafka-prod-v1", InputUtils.normalizeNamespace("KAFKA-PROD-V1"));
        assertEquals("team.namespace", InputUtils.normalizeNamespace("Team.Namespace"));
    }

    @Test
    void normalizeNamespace_preserves_valid_kubernetes_names() {
        // Kubernetes valid names should be preserved (after lowercasing)
        assertEquals("abc", InputUtils.normalizeNamespace("abc"));
        assertEquals("a-b-c", InputUtils.normalizeNamespace("a-b-c"));
        assertEquals("namespace123", InputUtils.normalizeNamespace("namespace123"));
        assertEquals("team.kafka", InputUtils.normalizeNamespace("team.kafka"));

        // With different cases
        assertEquals("my-prod-kafka", InputUtils.normalizeNamespace("My-Prod-Kafka"));
        assertEquals("ns.v2.prod", InputUtils.normalizeNamespace("NS.V2.PROD"));
    }

    @Test
    void normalizeClusterName_handles_various_inputs() {
        // Standard cases
        assertEquals("my-cluster", InputUtils.normalizeClusterName("my-cluster"));
        assertEquals("production", InputUtils.normalizeClusterName("production"));

        // Case normalization
        assertEquals("my-cluster", InputUtils.normalizeClusterName("MY-CLUSTER"));
        assertEquals("kafkacluster", InputUtils.normalizeClusterName("KafkaCluster"));

        // Whitespace handling
        assertEquals("my-cluster", InputUtils.normalizeClusterName(" my-cluster "));
        assertEquals("cluster-name", InputUtils.normalizeClusterName("  CLUSTER-NAME  "));

        // Empty/null handling
        assertNull(InputUtils.normalizeClusterName(null));
        assertNull(InputUtils.normalizeClusterName(""));
        assertNull(InputUtils.normalizeClusterName(" "));
        assertNull(InputUtils.normalizeClusterName("   "));

        // Complex cluster names
        assertEquals("prod-events-v2", InputUtils.normalizeClusterName("PROD-EVENTS-V2"));
        assertEquals("team.main", InputUtils.normalizeClusterName("Team.Main"));
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
        // Test idempotence - normalizing twice should give same result
        String input = "  My-CLUSTER-Name  ";
        String normalized = InputUtils.normalizeClusterName(input);
        String normalizedTwice = InputUtils.normalizeClusterName(normalized);

        assertEquals(normalized, normalizedTwice);
        assertEquals("my-cluster-name", normalized);

        // Test namespace idempotence
        String nsInput = "  KAFKA-PRODUCTION  ";
        String nsNormalized = InputUtils.normalizeNamespace(nsInput);
        String nsNormalizedTwice = InputUtils.normalizeNamespace(nsNormalized);

        assertEquals(nsNormalized, nsNormalizedTwice);
        assertEquals("kafka-production", nsNormalized);
    }

    @Test
    void normalization_handles_edge_cases() {
        // Very long names
        String longName = "a".repeat(100);
        assertEquals(longName.toLowerCase(Locale.ENGLISH), InputUtils.normalizeNamespace(longName.toUpperCase(Locale.ENGLISH)));

        // Names with numbers
        assertEquals("kafka123", InputUtils.normalizeNamespace("KAFKA123"));
        assertEquals("cluster-v2", InputUtils.normalizeClusterName("Cluster-V2"));

        // Names with special characters
        assertEquals("my-cluster.v1", InputUtils.normalizeNamespace("My-Cluster.V1"));
        assertEquals("team_kafka", InputUtils.normalizeClusterName("TEAM_KAFKA"));

        // Single character names
        assertEquals("a", InputUtils.normalizeNamespace("A"));
        assertEquals("x", InputUtils.normalizeClusterName(" X "));
    }

    @Test
    void business_logic_validation() {
        // Test that normalization preserves valid Kubernetes naming
        // Kubernetes names must be lowercase alphanumeric with dashes and dots
        String[] validInputs = {
            "kafka", "my-cluster", "team.namespace", "prod-v1.2", "a", "namespace123"
        };

        String[] expectedOutputs = {
            "kafka", "my-cluster", "team.namespace", "prod-v1.2", "a", "namespace123"
        };

        for (int i = 0; i < validInputs.length; i++) {
            assertEquals(expectedOutputs[i], InputUtils.normalizeNamespace(validInputs[i]));
            assertEquals(expectedOutputs[i], InputUtils.normalizeClusterName(validInputs[i]));
        }

        // Test case conversion for uppercase inputs
        String[] uppercaseInputs = {
            "KAFKA", "MY-CLUSTER", "TEAM.NAMESPACE", "PROD-V1.2"
        };

        String[] expectedLowercase = {
            "kafka", "my-cluster", "team.namespace", "prod-v1.2"
        };

        for (int i = 0; i < uppercaseInputs.length; i++) {
            assertEquals(expectedLowercase[i], InputUtils.normalizeNamespace(uppercaseInputs[i]));
            assertEquals(expectedLowercase[i], InputUtils.normalizeClusterName(uppercaseInputs[i]));
        }
    }

    @Test
    void null_safety_validation() {
        StrimziDiscoveryService service = new StrimziDiscoveryService();

        // All normalization methods should handle null safely
        assertNull(InputUtils.normalizeNamespace(null));
        assertNull(InputUtils.normalizeClusterName(null));

        // Should not throw exceptions
        assertDoesNotThrow(() -> {
            InputUtils.normalizeNamespace(null);
            InputUtils.normalizeClusterName(null);
            service.getDefaultNamespace();
        });

        // KafkaClusterInfo should handle null conditions
        KafkaClusterInfo clusterWithNullConditions = new KafkaClusterInfo("test", "ns", null);
        assertEquals("test", clusterWithNullConditions.name());
        assertEquals("ns", clusterWithNullConditions.namespace());
        assertNull(clusterWithNullConditions.conditions());
        assertEquals("test (namespace: ns)", clusterWithNullConditions.getDisplayName());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "strimzi-cluster-operator",
        "strimzi-cluster-operator-v2",
        "my-strimzi-operator",
        "strimzi-operator",
        "prefix-strimzi-cluster-operator-suffix"
    })
    void isOperatorName_positive_matches(String name) {
        assertTrue(StrimziDiscoveryService.isOperatorName(name),
            "Expected operator name match for: " + name);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        "strimzi-kafka-broker",
        "kafka-operator",
        "strimzi-bridge",
        "strimzi-entity-topic",
        "my-deployment"
    })
    void isOperatorName_negative_matches(String name) {
        assertFalse(StrimziDiscoveryService.isOperatorName(name),
            "Expected no operator name match for: " + name);
    }

    @Test
    void parseSections_null_returns_empty_set() {
        assertEquals(Set.of(), PodsService.parseSections(null));
    }

    @Test
    void parseSections_blank_returns_empty_set() {
        assertEquals(Set.of(), PodsService.parseSections(""));
        assertEquals(Set.of(), PodsService.parseSections("   "));
        assertEquals(Set.of(), PodsService.parseSections("\t"));
    }

    @Test
    void parseSections_single_section() {
        assertEquals(Set.of("env"), PodsService.parseSections("env"));
        assertEquals(Set.of("full"), PodsService.parseSections("full"));
    }

    @Test
    void parseSections_multiple_sections() {
        assertEquals(Set.of("env", "resources"), PodsService.parseSections("env,resources"));
        assertEquals(Set.of("node", "labels", "conditions"),
            PodsService.parseSections("node,labels,conditions"));
    }

    @Test
    void parseSections_trims_whitespace() {
        assertEquals(Set.of("env", "resources"),
            PodsService.parseSections("  env , resources  "));
        assertEquals(Set.of("node"),
            PodsService.parseSections("  node  "));
    }

    @Test
    void parseSections_lowercases_input() {
        assertEquals(Set.of("env", "resources"),
            PodsService.parseSections("ENV,Resources"));
        assertEquals(Set.of("full"),
            PodsService.parseSections("FULL"));
    }

    @Test
    void parseSections_ignores_empty_segments() {
        assertEquals(Set.of("env"), PodsService.parseSections("env,"));
        assertEquals(Set.of("env", "resources"),
            PodsService.parseSections("env,,resources"));
        assertEquals(Set.of("env"), PodsService.parseSections(",env,"));
    }

    @Test
    void input_validation_scenarios() {
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

            assertEquals(expectedNs, InputUtils.normalizeNamespace(input),
                "Namespace normalization failed for: " + input);
            assertEquals(expectedCluster, InputUtils.normalizeClusterName(input),
                "Cluster normalization failed for: " + input);
        }

        // Test invalid inputs that should return null
        String[] invalidInputs = {null, "", " ", "   ", "\t", "\n"};

        for (String invalid : invalidInputs) {
            assertNull(InputUtils.normalizeNamespace(invalid),
                "Should return null for invalid namespace: '" + invalid + "'");
            assertNull(InputUtils.normalizeClusterName(invalid),
                "Should return null for invalid cluster: '" + invalid + "'");
        }
    }
}
