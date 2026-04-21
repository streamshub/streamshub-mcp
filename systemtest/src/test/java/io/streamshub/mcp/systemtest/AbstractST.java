/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest;

import io.skodjob.kubetest4j.annotations.ResourceManager;
import io.skodjob.kubetest4j.resources.ClusterRoleBindingType;
import io.skodjob.kubetest4j.resources.ClusterRoleType;
import io.skodjob.kubetest4j.resources.ConfigMapType;
import io.skodjob.kubetest4j.resources.DeploymentType;
import io.skodjob.kubetest4j.resources.JobType;
import io.skodjob.kubetest4j.resources.KubeResourceManager;
import io.skodjob.kubetest4j.resources.RoleBindingType;
import io.skodjob.kubetest4j.resources.RoleType;
import io.skodjob.kubetest4j.resources.SecretType;
import io.skodjob.kubetest4j.resources.ServiceAccountType;
import io.skodjob.kubetest4j.resources.ServiceType;
import io.streamshub.mcp.systemtest.resources.strimzi.KafkaConnectType;
import io.streamshub.mcp.systemtest.resources.strimzi.KafkaConnectorType;
import io.streamshub.mcp.systemtest.resources.strimzi.KafkaNodePoolType;
import io.streamshub.mcp.systemtest.resources.strimzi.KafkaTopicType;
import io.streamshub.mcp.systemtest.resources.strimzi.KafkaType;
import org.junit.jupiter.api.TestInstance;

/**
 * Base class for all system tests. Registers kubetest4j resource types
 * so the framework knows how to create, wait for readiness, and clean up resources.
 * All setup classes use {@link KubeResourceManager#get()} singleton directly.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ResourceManager()
public abstract class AbstractST {

    static {
        KubeResourceManager.get().setResourceTypes(
            // Kubernetes built-in resource types
            new DeploymentType(),
            new ServiceType(),
            new ServiceAccountType(),
            new ClusterRoleType(),
            new ClusterRoleBindingType(),
            new RoleType(),
            new RoleBindingType(),
            new JobType(),
            new ConfigMapType(),
            new SecretType(),
            // Strimzi custom resource types
            new KafkaType(),
            new KafkaNodePoolType(),
            new KafkaTopicType(),
            new KafkaConnectType(),
            new KafkaConnectorType()
        );
    }
}
