/*
 * Copyright (c) 2006-2025 Chris Collins
 */
package com.hitorro.mesh.k8s;

import com.hitorro.mesh.orion.ClusterManager;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodListBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parser-only tests — same shape as {@code OrionClusterManagerTest}. Pin
 * down the annotation-and-label conventions so a schema drift on the K8s
 * side (Pod without the annotation, or a rename) fails loudly instead of
 * silently reporting empty declared sets.
 */
class KubernetesClusterManagerTest {

    @Test
    void toDeclared_readsCapabilitiesFromAnnotation() {
        Pod p1 = new PodBuilder()
                .withNewMetadata()
                    .withName("hitorro-agent-shard-3")
                    .withLabels(java.util.Map.of("app", "hitorro-mesh", "role", "agent"))
                    .withAnnotations(java.util.Map.of(
                            "hitorro.mesh/capabilities", "jvssql,partition:docs:shard-3"))
                .endMetadata()
                .withNewSpec().withNodeName("node1").endSpec()
                .build();

        Pod p2 = new PodBuilder()
                .withNewMetadata()
                    .withName("hitorro-agent-shard-4")
                    .withLabels(java.util.Map.of("app", "hitorro-mesh", "role", "agent"))
                    .withAnnotations(java.util.Map.of(
                            "hitorro.mesh/capabilities", "jvssql, partition:docs:shard-4 , arch:arm64"))
                .endMetadata()
                .withNewSpec().endSpec()
                .build();

        PodList list = new PodListBuilder().withItems(List.of(p1, p2)).build();
        Set<ClusterManager.DeclaredAgent> agents = KubernetesClusterManager.toDeclared(list);

        assertThat(agents).hasSize(2);
        assertThat(agents).anySatisfy(a -> {
            if (a.name().equals("hitorro-agent-shard-3")) {
                assertThat(a.declaredCapabilities())
                        .containsExactlyInAnyOrder("jvssql", "partition:docs:shard-3");
                assertThat(a.nodeName()).isEqualTo("node1");
            }
        });
        assertThat(agents).anySatisfy(a -> {
            if (a.name().equals("hitorro-agent-shard-4")) {
                assertThat(a.declaredCapabilities())
                        .containsExactlyInAnyOrder("jvssql", "partition:docs:shard-4", "arch:arm64");
                assertThat(a.nodeName()).isNull();
            }
        });
    }

    @Test
    void toDeclared_podWithoutCapabilityAnnotation_yieldsEmptyCaps() {
        Pod bare = new PodBuilder()
                .withNewMetadata().withName("hitorro-agent-bare").endMetadata()
                .withNewSpec().endSpec()
                .build();
        Set<ClusterManager.DeclaredAgent> agents = KubernetesClusterManager.toDeclared(
                new PodListBuilder().withItems(List.of(bare)).build());
        assertThat(agents).hasSize(1);
        assertThat(agents.iterator().next().declaredCapabilities()).isEmpty();
    }

    @Test
    void toDeclared_nullOrEmpty_returnsEmpty() {
        assertThat(KubernetesClusterManager.toDeclared(null)).isEmpty();
        assertThat(KubernetesClusterManager.toDeclared(new PodListBuilder().build())).isEmpty();
    }
}
