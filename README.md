# hitorro-mesh-k8s

Kubernetes bridge for the Hitorro Mesh. Parallel to
[`hitorro-mesh-orion`](../hitorro-mesh-orion/) — both are first-class
deployment targets. The mesh itself doesn't prefer either; the deployer
picks whichever platform they run.

Two pieces:

1. **`KubernetesClusterManager`** — a Java `ClusterManager` implementation
   that reads the declared agent set from the K8s API (Pods labeled
   `app=hitorro-mesh, role=agent`, capabilities in the
   `hitorro.mesh/capabilities` annotation). Enriches the driver UI with
   declared-vs-live-vs-orphan states. Falls back to heartbeat-only ground
   truth if the K8s API is unreachable.

2. **Helm chart + flat manifests** in [`helm/`](helm/) and
   [`manifests/`](manifests/) — deploy the driver as a Deployment + Service
   (optionally Ingress), one Deployment per shard for agents.

## Orion vs. Kubernetes — pick the one you're running

Orion (`hitorro-mesh-orion`) is the lightweight option — native binaries,
capability-aware placement, no container mandate, aimed at personal /
heterogeneous clusters (Mac + Linux + Pi). If Orion works for you, use it.

Kubernetes (this module) is the option when you're deploying into an
existing Kubernetes cluster — cloud, on-prem, k3s at home, or dev-time
`kind`/`minikube`. Everything the mesh needs (agent inventory, capability
tags, deep-links) maps naturally onto K8s primitives (Pods, annotations,
dashboard URLs).

Both modules implement the same `ClusterManager` interface. Both live in
the same Maven build. Neither depends on the other except the K8s bridge
reuses the SPI type defined in the Orion module (historical — it belongs
in `hitorro-mesh-core` and will move there when we grow more platform
bridges).

## Prereqs

- A Kubernetes cluster (any distro). `kind create cluster` works for local
  iteration.
- A NATS server reachable at `nats://nats:4222` in the target namespace.
  The community `nats` chart is fine; wrap this chart with it if you want
  everything in one release.
- Container images for the driver and agent fat JARs. Build them from
  this repo:
  ```bash
  cd /path/to/hitorro
  docker build -f hitorro-mesh-examples/docker/Dockerfile.driver -t hitorro/mesh-driver:3.0.1 .
  docker build -f hitorro-mesh-examples/docker/Dockerfile.agent  -t hitorro/mesh-agent:3.0.1 .
  # If using kind:  kind load docker-image hitorro/mesh-driver:3.0.1 hitorro/mesh-agent:3.0.1
  ```

## Helm walkthrough

```bash
# 1. Install (with default 3-shard docs table + sample NDJSON baked into the chart).
kubectl create namespace mesh
helm install mesh ./helm/hitorro-mesh --namespace mesh

# 2. Watch it come up.
kubectl -n mesh get pods -l app=hitorro-mesh
# NAME                                      READY   STATUS    RESTARTS   AGE
# mesh-hitorro-mesh-driver-...              1/1     Running   0          20s
# mesh-hitorro-mesh-agent-us-...            1/1     Running   0          20s
# mesh-hitorro-mesh-agent-eu-...            1/1     Running   0          20s
# mesh-hitorro-mesh-agent-apac-...          1/1     Running   0          20s

# 3. Verify the driver sees all agents.
kubectl -n mesh port-forward svc/mesh-hitorro-mesh-driver 8085:8085 &
curl -s http://localhost:8085/mesh/agents | jq .

# 4. Submit a distributed query.
curl -s -X POST http://localhost:8085/mesh/queries \
  -H 'Content-Type: application/json' \
  -d '{"sql":"SELECT id, title, lang FROM docs WHERE lang='"'"'en'"'"'","timeoutMs":5000}' \
  | jq .

# 5. Tear down.
helm uninstall mesh --namespace mesh
```

## Overriding for your own tables

Copy `helm/hitorro-mesh/values.yaml` to `my-values.yaml`, edit the
`driver.tables` and `agents` blocks (add shards, change ndjson, adjust
capabilities), then:

```bash
helm upgrade --install mesh ./helm/hitorro-mesh -n mesh -f my-values.yaml
```

## Node pinning (data locality)

If a shard's data lives on a specific node (e.g., the agent uses a
node-local kvstore or Lucene index), pin it:

```yaml
# my-values.yaml
agents:
  - id: agent-us
    partitionKey: us
    capabilities: [jvssql, "partition:docs:us"]
    ndjson: |
      ...
    nodeSelector:
      hitorro.mesh/shard: us      # label the node first: kubectl label node nodeX hitorro.mesh/shard=us
```

## Enabling the K8s cluster-manager enrichment

Wire `KubernetesClusterManager` into the driver's Spring context in a
downstream module or the driver-app itself:

```java
@Bean
public ClusterManager clusterManager(
        @Value("${hitorro.mesh.k8s.namespace:mesh}") String namespace,
        @Value("${hitorro.mesh.k8s.dashboard-base:}") String dashboardBase) {
    URI dash = dashboardBase.isEmpty() ? null : URI.create(dashboardBase);
    return KubernetesClusterManager.fromAmbientConfig(namespace, dash, 5_000);
}
```

The driver Pod's ServiceAccount (created by `templates/rbac.yaml`) has
`get/list/watch pods` permission scoped to its own namespace — the
minimum needed for discovery.

## What this bridge does NOT do

- **Ship a NATS server.** Provide your own (community `nats` chart is
  a fine choice — wrap this chart or install it separately).
- **Manage cluster-wide credentials or TLS.** Set `hitorro.mesh.*.nats-url`
  to `tls://…` and mount your certs; the mesh doesn't own that policy.
- **Manage shard rebalancing.** If a shard needs to move, delete its
  Deployment and create a new one on the target node. The driver picks
  up the new agent via NATS heartbeats within a few seconds.

## Related

- Sibling module [`hitorro-mesh-orion`](../hitorro-mesh-orion/) — Orion
  bridge with the same SPI.
- Main mesh docs: [`../hitorro-mesh-core/README.md`](../hitorro-mesh-core/README.md)
  and [`../hitorro-mesh-core/ARCHITECTURE.md`](../hitorro-mesh-core/ARCHITECTURE.md).
