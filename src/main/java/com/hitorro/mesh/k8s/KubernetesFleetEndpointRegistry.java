/*
 * Copyright (c) 2006-2026 Chris Collins
 */
package com.hitorro.mesh.k8s;

import com.hitorro.mesh.driver.fleet.FleetEndpointRegistry;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Discovers {@code hitorro-fleet-retrieval} instances by watching
 * Kubernetes Services labeled with {@code app=hitorro-fleet-retrieval}
 * in the driver's namespace. Each Service becomes one endpoint URL
 * (Service DNS: {@code svc-name.namespace:port}).
 *
 * <p>Uses the fabric8 client already on the {@code hitorro-mesh-k8s}
 * classpath (same one {@code KubernetesClusterManager} uses).</p>
 *
 * <p>Cached with a short TTL (default 5s) so per-request calls don't
 * hammer the k8s API. Refresh is lazy on {@link #endpoints()}.</p>
 */
public final class KubernetesFleetEndpointRegistry implements FleetEndpointRegistry {

    private static final Logger log = LoggerFactory.getLogger(KubernetesFleetEndpointRegistry.class);
    private static final String  DEFAULT_LABEL = "app=hitorro-fleet-retrieval";
    private static final int     DEFAULT_PORT  = 8087;
    private static final long    DEFAULT_TTL_MS = 5_000L;

    private final KubernetesClient client;
    private final String namespace;
    private final String labelSelector;
    private final int defaultPort;
    private final long ttlMs;

    private volatile List<String> cachedEndpoints = List.of();
    private volatile long cachedAtMs = 0L;

    public KubernetesFleetEndpointRegistry(KubernetesClient client, String namespace) {
        this(client, namespace, DEFAULT_LABEL, DEFAULT_PORT, DEFAULT_TTL_MS);
    }

    public KubernetesFleetEndpointRegistry(KubernetesClient client, String namespace,
                                           String labelSelector, int defaultPort, long ttlMs) {
        this.client        = client;
        this.namespace     = namespace == null ? client.getNamespace() : namespace;
        this.labelSelector = labelSelector;
        this.defaultPort   = defaultPort;
        this.ttlMs         = ttlMs;
    }

    @Override public String platform() { return "kubernetes"; }

    @Override
    public List<String> endpoints() {
        long now = System.currentTimeMillis();
        if (now - cachedAtMs < ttlMs) return cachedEndpoints;
        try {
            var services = client.services()
                    .inNamespace(namespace)
                    .withLabelSelector(labelSelector)
                    .list()
                    .getItems();
            List<String> discovered = new ArrayList<>(services.size());
            for (var svc : services) {
                String name = svc.getMetadata().getName();
                int port = defaultPort;
                var spec = svc.getSpec();
                if (spec != null && spec.getPorts() != null && !spec.getPorts().isEmpty()) {
                    // First port is convention. Prefer one named "http" or "retrieval" if present.
                    ServicePort chosen = spec.getPorts().stream()
                            .filter(p -> "http".equalsIgnoreCase(p.getName()) || "retrieval".equalsIgnoreCase(p.getName()))
                            .findFirst()
                            .orElse(spec.getPorts().get(0));
                    port = chosen.getPort();
                }
                discovered.add("http://" + name + "." + namespace + ":" + port);
            }
            cachedEndpoints = List.copyOf(discovered);
            cachedAtMs = now;
            return cachedEndpoints;
        } catch (Exception e) {
            log.warn("k8s fleet discovery failed: {} — returning cached ({} endpoints)",
                    e.getMessage(), cachedEndpoints.size());
            return cachedEndpoints;
        }
    }
}
