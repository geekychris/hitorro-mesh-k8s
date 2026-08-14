# Flat Kubernetes manifests

For deployers who don't want to install Helm — these are the equivalent
raw YAML files. If you're going to iterate on the deployment, prefer the
Helm chart in `../helm/hitorro-mesh/` (values-file customization).

Apply order (edit image tags + NATS URL to match your setup first):

```bash
kubectl create namespace mesh
kubectl -n mesh apply -f 01-nats-service.yaml     # or use your own NATS
kubectl -n mesh apply -f 10-driver-configmap.yaml
kubectl -n mesh apply -f 11-driver.yaml
kubectl -n mesh apply -f 20-agent-us.yaml
kubectl -n mesh apply -f 21-agent-eu.yaml
kubectl -n mesh apply -f 22-agent-apac.yaml
```

These files are hand-authored templates you can adapt in place. The Helm
chart generates equivalent YAML — see it with:

```bash
helm template mesh ../helm/hitorro-mesh --namespace mesh
```
