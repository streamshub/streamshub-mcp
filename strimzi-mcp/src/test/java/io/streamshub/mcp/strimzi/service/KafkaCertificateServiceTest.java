/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.AppsAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.strimzi.dto.KafkaCertificateResponse;
import io.strimzi.api.ResourceLabels;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.kafka.KafkaBuilder;
import io.strimzi.api.kafka.model.kafka.listener.KafkaListenerAuthenticationScramSha512;
import io.strimzi.api.kafka.model.kafka.listener.KafkaListenerAuthenticationTls;
import io.strimzi.api.kafka.model.kafka.listener.KafkaListenerType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service-level tests for {@link KafkaCertificateService} with mocked Kubernetes.
 */
@QuarkusTest
class KafkaCertificateServiceTest {

    private static final String NAMESPACE = "kafka-prod";
    private static final String CLUSTER_NAME = "my-cluster";

    /** Valid self-signed test certificate (CN=test-ca, O=StreamsHub), expires 2036-04-04. */
    private static final String VALID_CERT_PEM = """
            -----BEGIN CERTIFICATE-----
            MIIC8TCCAdmgAwIBAgIIZAIo2y9VYJQwDQYJKoZIhvcNAQEMBQAwJzETMBEGA1UE
            ChMKU3RyZWFtc0h1YjEQMA4GA1UEAxMHdGVzdC1jYTAeFw0yNjA0MDcxMDI3MDda
            Fw0zNjA0MDQxMDI3MDdaMCcxEzARBgNVBAoTClN0cmVhbXNIdWIxEDAOBgNVBAMT
            B3Rlc3QtY2EwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQDXRdkzCP0m
            TIT4su00BqHceg7anGsu9+3LgX/GObXNOAxKeKru7QqPPEkNlIqZApoR7w/Cilk/
            9vg6AK+7DhKm/4mFw6Jnt2DTvwOQNhe6gz6Igbl9rxDXpqc+ikLdlVIqNuDbgL1n
            VIum5quRwH8+BH6InYJX9NYjB5xPkSOBu1GC6ZpTB9PQ8m8tw1QWVnNIVgfjrmcV
            bPdMQ42O0J0b9L45hbLg9m7MKUQWXnt1HwcpKHcPLvLgP4MRlv6+5MNaf6lnntM9
            pRFnuh1/s/53IAAsl7Nk0lYoNqeAvN6ytAKuxIwtpM0d8dYT046Nphy8Y27E4Ee1
            dlmDRHKd1vw9AgMBAAGjITAfMB0GA1UdDgQWBBSqieFxcGphk6aig7yZ3y9Sf1T7
            pDANBgkqhkiG9w0BAQwFAAOCAQEAb9n7onBk8P1oAgbjF+wpslUJsouYaE4qCwKm
            QtvAsOd623+nQ2htB7OlRIYu4Wq6bTm2foT/dMMBv2i60gtewUEe6XYj3fwlqqHu
            1q616QT6RQeKBeBooTl+UIZUOUJ+uAq1mVmCwgqn+4INQLa3O5pbz+E0dV6panml
            BJ7yb22YbrC0I5Uq+/7g2YUrjwxlQNrqFIXw9H5rZfeNQo37yi9YiBR21JuioPB/
            UFT37Fflx76Pq15Q3uf/xmSEqB+UZxjvoS9IiDkc/R8CGmcpQBduOdroOu/lP99q
            VzSHecudYz390nPMsu5Ccmfe32k350N27QKD1XFh/4GkaqZFYg==
            -----END CERTIFICATE-----
            """;

    /** Expired self-signed test certificate (CN=expired-ca, O=StreamsHub), expired 2024-04-08. */
    private static final String EXPIRED_CERT_PEM = """
            -----BEGIN CERTIFICATE-----
            MIIC+DCCAeCgAwIBAgIJAIIQ9tA/5V4bMA0GCSqGSIb3DQEBDAUAMCoxEzARBgNV
            BAoTClN0cmVhbXNIdWIxEzARBgNVBAMTCmV4cGlyZWQtY2EwHhcNMjQwNDA3MDcx
            MDU3WhcNMjQwNDA4MDcxMDU3WjAqMRMwEQYDVQQKEwpTdHJlYW1zSHViMRMwEQYD
            VQQDEwpleHBpcmVkLWNhMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA
            6PFHzOVkejMs4Ebs6x0GRgIH+ylAXv9FnHppekLqkU7bCtZSumKCPTVtN+oQJmgR
            1KLMPuHMO/ClYuLGdRVygASMcR2BzAW1ZinqspuemrwTASPRKzorL2sOmjEhdvHk
            0M3b7+XxjxGHCllRWKxjjij+H2YFFZuboZFBXKu1wIldK2qIw1mXzjvxbxKygfhM
            N31op5sWciLDugC7opMfT4uElsY1GiHS/O8E10MoHcOxUYMlcXBsK7V0QESeTGJn
            GI+t8rWYRvGvRB/CoFgleH2SXFBIu+ZwrQKM2RQVfbw04myqZkfH7dVlzWKMqZRk
            C8h303jDnE2hRvcdoqaMuwIDAQABoyEwHzAdBgNVHQ4EFgQUvxVk7i1lGYia3xp2
            44+aXx4EREQwDQYJKoZIhvcNAQEMBQADggEBAJTMmjDAgzV+6GvmgWSTRN0sL8WK
            eKBc7KRQTzi1MlfWdG+B4Q5r29Z/087kGxYVruFvqhfiXXYXxvH9WYkML8vkzQ67
            bD2etoib/Nxej3mx0Fva9viRBeJp01GmTQepIGq8bePzcUTXBgB7iUcAdDj9pKUg
            OlizX4ylm0K+bt5VqnLNi9cSxQPV7KDvQdLR/sN8vEkTsrrMEczj2htPIMOw8O8u
            IivzggWuMBx2aqzZVhs0fMajEdH3m9bM3o1O5N4OHbfAmYuWE6L5DgF2tIc2iKB/
            IK4Bvf8hhYpx1zCzy7zJRhq4fgBs68G1pwW68xBRhBrI2wIYd1BFZWrjxjM=
            -----END CERTIFICATE-----
            """;

    @InjectMock
    KubernetesClient kubernetesClient;

    @Inject
    KafkaCertificateService kafkaCertificateService;

    @SuppressWarnings("rawtypes")
    private MixedOperation kafkaOp;

    @SuppressWarnings("rawtypes")
    private NonNamespaceOperation kafkaNsOp;

    @SuppressWarnings("rawtypes")
    private MixedOperation secretOp;

    @SuppressWarnings("rawtypes")
    private NonNamespaceOperation secretNsOp;

    KafkaCertificateServiceTest() {
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        MixedOperation<Pod, PodList, PodResource> podOp = Mockito.mock(MixedOperation.class);
        Mockito.lenient().when(kubernetesClient.pods()).thenReturn(podOp);

        MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deploymentOp =
            Mockito.mock(MixedOperation.class);
        AppsAPIGroupDSL appsApi = Mockito.mock(AppsAPIGroupDSL.class);
        Mockito.lenient().when(kubernetesClient.apps()).thenReturn(appsApi);
        Mockito.lenient().when(appsApi.deployments()).thenReturn(deploymentOp);

        kafkaOp = Mockito.mock(MixedOperation.class);
        kafkaNsOp = Mockito.mock(NonNamespaceOperation.class);
        Mockito.lenient().doReturn(kafkaOp).when(kubernetesClient).resources(Kafka.class);
        Mockito.lenient().doReturn(kafkaNsOp).when(kafkaOp).inNamespace(NAMESPACE);

        secretOp = Mockito.mock(MixedOperation.class);
        secretNsOp = Mockito.mock(NonNamespaceOperation.class);
        Mockito.lenient().doReturn(secretOp).when(kubernetesClient).resources(Secret.class);
        Mockito.lenient().doReturn(secretNsOp).when(secretOp).inNamespace(NAMESPACE);
    }

    @Test
    void testGetCertificatesWithValidSecrets() {
        Kafka kafka = buildKafkaWithListeners();
        mockKafkaResource(kafka);
        mockSecret(CLUSTER_NAME + "-cluster-ca-cert", VALID_CERT_PEM, true);
        mockSecret(CLUSTER_NAME + "-clients-ca-cert", VALID_CERT_PEM, true);

        KafkaCertificateResponse response = kafkaCertificateService.getCertificates(NAMESPACE, CLUSTER_NAME, null);

        assertNotNull(response);
        assertEquals(CLUSTER_NAME, response.clusterName());
        assertEquals(NAMESPACE, response.namespace());
        assertEquals(2, response.certificates().size());
        assertFalse(response.certificates().getFirst().expired());
        assertTrue(response.certificates().getFirst().daysUntilExpiry() > 0);
        assertNotNull(response.certificates().getFirst().subject());
        assertNotNull(response.certificates().getFirst().issuer());
    }

    @Test
    void testGetCertificatesExtractsListenerAuth() {
        Kafka kafka = buildKafkaWithListeners();
        mockKafkaResource(kafka);
        mockSecret(CLUSTER_NAME + "-cluster-ca-cert", VALID_CERT_PEM, true);
        mockSecret(CLUSTER_NAME + "-clients-ca-cert", VALID_CERT_PEM, true);

        KafkaCertificateResponse response = kafkaCertificateService.getCertificates(NAMESPACE, CLUSTER_NAME, null);

        assertNotNull(response.listenerAuthentication());
        assertEquals(2, response.listenerAuthentication().size());

        KafkaCertificateResponse.ListenerAuthInfo plainListener = response.listenerAuthentication().stream()
            .filter(l -> "plain".equals(l.listenerName()))
            .findFirst().orElseThrow();
        assertFalse(plainListener.tlsEnabled());
        assertEquals("scram-sha-512", plainListener.authenticationType());

        KafkaCertificateResponse.ListenerAuthInfo tlsListener = response.listenerAuthentication().stream()
            .filter(l -> "tls".equals(l.listenerName()))
            .findFirst().orElseThrow();
        assertTrue(tlsListener.tlsEnabled());
        assertEquals("tls", tlsListener.authenticationType());
    }

    @Test
    void testGetCertificatesReturnsEmptyWhenSecretsNotFound() {
        Kafka kafka = buildKafkaWithListeners();
        mockKafkaResource(kafka);
        mockMissingSecret(CLUSTER_NAME + "-cluster-ca-cert");
        mockMissingSecret(CLUSTER_NAME + "-clients-ca-cert");

        KafkaCertificateResponse response = kafkaCertificateService.getCertificates(NAMESPACE, CLUSTER_NAME, null);

        assertNotNull(response);
        assertTrue(response.certificates().isEmpty());
        // Listener auth is still available from the Kafka CR (no RBAC needed)
        assertFalse(response.listenerAuthentication().isEmpty());
        assertTrue(response.message().contains("0 certificates"));
    }

    @Test
    void testGetCertificatesRejectsSecretWithoutLabel() {
        Kafka kafka = buildKafkaWithListeners();
        mockKafkaResource(kafka);
        mockSecret(CLUSTER_NAME + "-cluster-ca-cert", VALID_CERT_PEM, false);
        mockMissingSecret(CLUSTER_NAME + "-clients-ca-cert");

        KafkaCertificateResponse response = kafkaCertificateService.getCertificates(NAMESPACE, CLUSTER_NAME, null);

        assertTrue(response.certificates().isEmpty());
    }

    @Test
    void testGetCertificatesFiltersByListenerName() {
        Kafka kafka = buildKafkaWithListeners();
        mockKafkaResource(kafka);
        mockSecret(CLUSTER_NAME + "-cluster-ca-cert", VALID_CERT_PEM, true);
        mockMissingSecret(CLUSTER_NAME + "-clients-ca-cert");

        KafkaCertificateResponse response =
            kafkaCertificateService.getCertificates(NAMESPACE, CLUSTER_NAME, "tls");

        assertEquals(1, response.listenerAuthentication().size());
        assertEquals("tls", response.listenerAuthentication().getFirst().listenerName());
        assertTrue(response.listenerAuthentication().getFirst().tlsEnabled());
    }

    @Test
    void testGetCertificatesThrowsWhenListenerNotFound() {
        Kafka kafka = buildKafkaWithListeners();
        mockKafkaResource(kafka);

        assertThrows(ToolCallException.class,
            () -> kafkaCertificateService.getCertificates(NAMESPACE, CLUSTER_NAME, "nonexistent"));
    }

    @Test
    void testGetCertificatesThrowsWhenClusterNameMissing() {
        assertThrows(ToolCallException.class,
            () -> kafkaCertificateService.getCertificates(NAMESPACE, null, null));
    }

    @Test
    void testGetCertificatesThrowsWhenClusterNotFound() {
        mockMissingKafkaResource();

        assertThrows(ToolCallException.class,
            () -> kafkaCertificateService.getCertificates(NAMESPACE, CLUSTER_NAME, null));
    }

    @Test
    void testGetCertificatesDetectsExpiredCert() {
        Kafka kafka = buildKafkaWithListeners();
        mockKafkaResource(kafka);
        mockSecret(CLUSTER_NAME + "-cluster-ca-cert", EXPIRED_CERT_PEM, true);
        mockMissingSecret(CLUSTER_NAME + "-clients-ca-cert");

        KafkaCertificateResponse response = kafkaCertificateService.getCertificates(NAMESPACE, CLUSTER_NAME, null);

        assertEquals(1, response.certificates().size());
        assertTrue(response.certificates().getFirst().expired());
    }

    @SuppressWarnings("unchecked")
    private void mockKafkaResource(final Kafka kafka) {
        Resource<Kafka> resource = Mockito.mock(Resource.class);
        Mockito.lenient().when(kafkaNsOp.withName(CLUSTER_NAME)).thenReturn(resource);
        Mockito.lenient().when(resource.get()).thenReturn(kafka);
    }

    @SuppressWarnings("unchecked")
    private void mockMissingKafkaResource() {
        Resource<Kafka> resource = Mockito.mock(Resource.class);
        Mockito.lenient().when(kafkaNsOp.withName(CLUSTER_NAME)).thenReturn(resource);
        Mockito.lenient().when(resource.get()).thenReturn(null);
    }

    @SuppressWarnings("unchecked")
    private void mockSecret(final String secretName, final String certPem, final boolean withLabel) {
        String base64Cert = Base64.getEncoder().encodeToString(certPem.getBytes());

        ObjectMetaBuilder metaBuilder = new ObjectMetaBuilder()
            .withName(secretName)
            .withNamespace(NAMESPACE);
        if (withLabel) {
            metaBuilder.withLabels(Map.of(ResourceLabels.STRIMZI_CLUSTER_LABEL, CLUSTER_NAME));
        }

        Secret secret = new SecretBuilder()
            .withMetadata(metaBuilder.build())
            .withData(Map.of("ca.crt", base64Cert))
            .build();

        Resource<Secret> resource = Mockito.mock(Resource.class);
        Mockito.lenient().when(secretNsOp.withName(secretName)).thenReturn(resource);
        Mockito.lenient().when(resource.get()).thenReturn(secret);
    }

    @SuppressWarnings("unchecked")
    private void mockMissingSecret(final String secretName) {
        Resource<Secret> resource = Mockito.mock(Resource.class);
        Mockito.lenient().when(secretNsOp.withName(secretName)).thenReturn(resource);
        Mockito.lenient().when(resource.get()).thenReturn(null);
    }

    private Kafka buildKafkaWithListeners() {
        return new KafkaBuilder()
            .withMetadata(new ObjectMetaBuilder()
                .withName(CLUSTER_NAME)
                .withNamespace(NAMESPACE)
                .build())
            .withNewSpec()
                .withNewKafka()
                    .addNewListener()
                        .withName("plain")
                        .withPort(9092)
                        .withType(KafkaListenerType.INTERNAL)
                        .withTls(false)
                        .withAuth(new KafkaListenerAuthenticationScramSha512())
                    .endListener()
                    .addNewListener()
                        .withName("tls")
                        .withPort(9093)
                        .withType(KafkaListenerType.INTERNAL)
                        .withTls(true)
                        .withAuth(new KafkaListenerAuthenticationTls())
                    .endListener()
                .endKafka()
            .endSpec()
            .build();
    }
}
