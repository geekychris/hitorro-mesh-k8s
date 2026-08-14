/*
 * Copyright (c) 2006-2025 Chris Collins
 */
package com.hitorro.mesh.k8s;

import com.hitorro.mesh.orion.ClusterManager;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;

import java.net.URI;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Kubernetes bridge for the {@link ClusterManager} SPI. Reads declared
 * mesh agents from Pods labeled {@code app=hitorro-mesh, role=agent}
 * and reads their advertised capabilities from the annotation
 * {@code hitorro.mesh/capabilities} (comma-separated string — matches the
 * env-var convention on the agent side).
 *
 * <h3>Design decision — parallel to Orion, not Kubernetes-first</h3>
 *
 * <p>This module exists so Kubernetes-shaped deployments have first-class
 * support. It's the sibling of {@code hitorro-mesh-orion}, not its
 * replacement. Orion is the lightweight primary path for personal /
 * heterogeneous clusters where you don't want the K8s operational
 * surface; K8s is the option when you're deploying into an existing K8s
 * environment. The {@code ClusterManager} interface lives in
 * {@code hitorro-mesh-orion} so it doesn't get duplicated — the module
 * name is historical, not a subordination.</p>
 *
 * <h3>Why annotations, not labels, for capabilities</h3>
 *
 * <p>K8s labels have a 63-char limit per value and disallow characters
 * like {@code :} — which we use in capability tags like
 * {@code partition:docs:shard-3}. Annotations have no such limits. The
 * agent JAR reads them via the Downward API into the
 * {@code HITORRO_MESH_CAPABILITIES} env var.</p>
 *
 * <p>Falls back gracefully on API errors — the driver keeps working
 * from heartbeat ground truth if the K8s API becomes unreachable.</p>
 */
public final class KubernetesClusterManager implements ClusterManager, AutoCloseable {

    /** Label the agent Pods must carry to be discovered. */
    public static final String LABEL_APP  = "app";
    public static final String LABEL_ROLE = "role";
    public static final String LABEL_APP_VALUE  = "hitorro-mesh";
    public static final String LABEL_ROLE_AGENT = "agent";

    /** Annotation on each agent Pod whose value is the comma-separated capability list. */
    public static final String ANNOTATION_CAPABILITIES = "hitorro.mesh/capabilities";

    private final KubernetesClient client;
    private final String namespace;
    private final boolean ownsClient;
    private final URI dashboardBase;   // may be null; only used to build consoleUrl()

    // trivial cache — declared set doesn't change often
    private volatile Set<DeclaredAgent> cache = Set.of();
    private volatile long cacheExpiresAt = 0L;
    private final long cacheTtlMillis;

    public KubernetesClusterManager(KubernetesClient client, String namespace,
                                    URI dashboardBase, long cacheTtlMillis) {
        this.client = client;
        this.namespace = namespace;
        this.ownsClient = false;
        this.dashboardBase = dashboardBase;
        this.cacheTtlMillis = cacheTtlMillis;
    }

    /** Convenience factory that opens its own {@link KubernetesClient} from ambient config. */
    public static KubernetesClusterManager fromAmbientConfig(String namespace, URI dashboardBase, long cacheTtlMillis) {
        KubernetesClient c = new KubernetesClientBuilder().build();
        return new KubernetesClusterManager(c, namespace, dashboardBase, cacheTtlMillis, true);
    }

    private KubernetesClusterManager(KubernetesClient c, String ns, URI dash, long ttl, boolean owns) {
        this.client = c; this.namespace = ns; this.dashboardBase = dash;
        this.cacheTtlMillis = ttl; this.ownsClient = owns;
    }

    @Override public String platform() { return "kubernetes"; }

    @Override
    public Set<DeclaredAgent> declaredAgents() {
        long now = System.currentTimeMillis();
        if (now < cacheExpiresAt) return cache;
        try {
            PodList pods = client.pods().inNamespace(namespace)
                    .withLabel(LABEL_APP, LABEL_APP_VALUE)
                    .withLabel(LABEL_ROLE, LABEL_ROLE_AGENT)
                    .list();
            Set<DeclaredAgent> fresh = toDeclared(pods);
            cache = fresh;
            cacheExpiresAt = now + cacheTtlMillis;
            return fresh;
        } catch (Exception e) {
            // API hiccup: return last-known. Never let discovery take the driver down.
            return cache;
        }
    }

    @Override
    public Optional<URI> consoleUrl(String agentName) {
        if (dashboardBase == null) return Optional.empty();
        // Standard K8s dashboard path shape for a Pod detail view.
        return Optional.of(dashboardBase.resolve(
                "/#/pod/" + namespace + "/" + agentName + "?namespace=" + namespace));
    }

    /** Package-visible for the unit test — deterministic parsing of a PodList. */
    static Set<DeclaredAgent> toDeclared(PodList pods) {
        if (pods == null || pods.getItems() == null) return Set.of();
        Set<DeclaredAgent> out = new LinkedHashSet<>();
        for (Pod p : pods.getItems()) {
            ObjectMeta md = p.getMetadata();
            if (md == null || md.getName() == null) continue;
            Set<String> caps = new HashSet<>();
            if (md.getAnnotations() != null) {
                String csv = md.getAnnotations().get(ANNOTATION_CAPABILITIES);
                if (csv != null) {
                    for (String s : csv.split(",")) {
                        String t = s.trim();
                        if (!t.isEmpty()) caps.add(t);
                    }
                }
            }
            String node = (p.getSpec() != null) ? p.getSpec().getNodeName() : null;
            out.add(new DeclaredAgent(md.getName(), caps, node));
        }
        return out;
    }

    @Override
    public void close() {
        if (ownsClient) {
            try { client.close(); } catch (Exception ignore) {}
        }
    }
}
