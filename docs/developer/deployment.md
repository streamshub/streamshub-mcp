+++
title = 'Deployment Guide'
weight = 5
+++

This guide covers deploying StreamsHub MCP servers to Kubernetes clusters with production-ready configurations.

## Prerequisites

- Kubernetes cluster (1.24+)
- kubectl configured to access the cluster
- Container registry access
- RBAC permissions to create namespaces, deployments, and RBAC resources

## Deployment Patterns

Each MCP server can be deployed independently. See individual server documentation for specific manifests and configuration:

- **[Strimzi MCP Server](mcp-servers/strimzi-mcp/installation.md#kubernetes-deployment)** — Strimzi-specific deployment instructions, RBAC, and configuration

This guide covers general deployment patterns applicable to all MCP servers.

## Building Container Images

### Using Jib (Recommended)

Build and push the container image:

```bash
cd strimzi-mcp

# Build and push to your registry
mvn clean package -DskipTests \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.push=true \
  -Dquarkus.container-image.registry=quay.io \
  -Dquarkus.container-image.group=myorg \
  -Dquarkus.container-image.name=strimzi-mcp \
  -Dquarkus.container-image.tag=1.0.0
```

### Using Docker/Podman

```bash
cd strimzi-mcp
mvn clean package -DskipTests

# Build the image
docker build -f src/main/docker/Dockerfile.jvm -t quay.io/myorg/strimzi-mcp:1.0.0 .

# Push to registry
docker push quay.io/myorg/strimzi-mcp:1.0.0
```

## Kubernetes Deployment

### Using Provided Manifests

Each MCP server includes Kubernetes manifests in its `install/` directory:

```bash
cd strimzi-mcp

# Review the manifests
ls -la install/

# Deploy all resources
kubectl apply -f install/

# Verify deployment
kubectl -n streamshub-mcp rollout status deployment/streamshub-strimzi-mcp
kubectl -n streamshub-mcp get pods
```

### Manifest Structure

| File | Resource | Purpose |
|------|----------|---------|
| `001-Namespace.yaml` | Namespace | Creates dedicated namespace |
| `002-ServiceAccount.yaml` | ServiceAccount | Identity for the MCP server |
| `003-ClusterRole.yaml` | ClusterRole | Read-only permissions for non-sensitive resources |
| `004-ClusterRoleBinding.yaml` | ClusterRoleBinding | Binds ClusterRole to ServiceAccount |
| `005-Deployment.yaml` | Deployment | MCP server deployment with health probes |
| `006-Service.yaml` | Service | ClusterIP service for internal access |
| `007-Role-sensitive.yaml` | Role | Optional per-namespace permissions for sensitive resources |

### Customizing the Deployment

#### Update Container Image

Edit `005-Deployment.yaml` or use kubectl:

```bash
kubectl -n streamshub-mcp set image deployment/streamshub-strimzi-mcp \
  strimzi-mcp=quay.io/myorg/strimzi-mcp:1.0.0
```

#### Configure Environment Variables

Edit `005-Deployment.yaml` to add environment variables:

```yaml
spec:
  template:
    spec:
      containers:
      - name: strimzi-mcp
        env:
        - name: MCP_LOG_TAIL_LINES
          value: "500"
        - name: LOKI_URL
          value: "http://loki.monitoring:3100"
        - name: PROMETHEUS_URL
          value: "http://prometheus.monitoring:9090"
```

#### Adjust Resource Limits

```yaml
spec:
  template:
    spec:
      containers:
      - name: strimzi-mcp
        resources:
          requests:
            memory: "256Mi"
            cpu: "100m"
          limits:
            memory: "512Mi"
            cpu: "500m"
```

## RBAC Configuration

### Two-Tier Permission Model

StreamsHub MCP uses a two-tier RBAC model following the principle of least privilege:

#### Tier 1: ClusterRole (Default, Non-Sensitive)

Provides read-only access to non-sensitive resources:

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: streamshub-mcp-reader
rules:
- apiGroups: ["kafka.strimzi.io"]
  resources: ["kafkas", "kafkanodepools", "kafkatopics", "strimzipodsets"]
  verbs: ["get", "list", "watch"]
- apiGroups: ["apps"]
  resources: ["deployments"]
  verbs: ["get", "list", "watch"]
- apiGroups: [""]
  resources: ["pods", "pods/log", "services", "configmaps", "events"]
  verbs: ["get", "list"]
- apiGroups: ["route.openshift.io"]
  resources: ["routes"]
  verbs: ["get", "list"]
- apiGroups: ["networking.k8s.io"]
  resources: ["ingresses"]
  verbs: ["get", "list"]
```

#### Tier 2: Role (Opt-In, Sensitive)

Provides access to sensitive resources on a per-namespace basis:

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: streamshub-mcp-sensitive
  namespace: kafka  # Deploy in each namespace where needed
rules:
- apiGroups: [""]
  resources: ["secrets"]
  verbs: ["get"]
- apiGroups: [""]
  resources: ["pods/proxy"]
  verbs: ["get"]
```

Deploy the sensitive Role only in namespaces where:
- Certificate metadata access is needed
- Direct pod metrics scraping is required

```bash
# Deploy sensitive Role in specific namespace
kubectl apply -f install/007-Role-sensitive.yaml -n kafka

# Create RoleBinding
kubectl create rolebinding streamshub-mcp-sensitive \
  --role=streamshub-mcp-sensitive \
  --serviceaccount=streamshub-mcp:streamshub-mcp \
  -n kafka
```

### Namespace-Scoped Deployment

For namespace-scoped deployments, replace ClusterRole with Role:

```bash
# Create namespace
kubectl create namespace streamshub-mcp

# Deploy ServiceAccount
kubectl apply -f install/002-ServiceAccount.yaml

# Convert ClusterRole to Role
kubectl apply -f - <<EOF
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: streamshub-mcp-reader
  namespace: kafka
rules:
  # Copy rules from 003-ClusterRole.yaml
EOF

# Create RoleBinding instead of ClusterRoleBinding
kubectl create rolebinding streamshub-mcp-reader \
  --role=streamshub-mcp-reader \
  --serviceaccount=streamshub-mcp:streamshub-mcp \
  -n kafka
```

## Accessing the MCP Server

### Port-Forward (Development/Testing)

Forward the service port to your local machine:

```bash
kubectl -n streamshub-mcp port-forward svc/streamshub-strimzi-mcp 8080:8080
```

Configure your MCP client:

```bash
claude mcp add --transport http strimzi http://localhost:8080/mcp
```

### Ingress (Kubernetes)

Create an Ingress resource:

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: streamshub-strimzi-mcp
  namespace: streamshub-mcp
  annotations:
    cert-manager.io/cluster-issuer: letsencrypt-prod
spec:
  ingressClassName: nginx
  tls:
  - hosts:
    - strimzi-mcp.example.com
    secretName: strimzi-mcp-tls
  rules:
  - host: strimzi-mcp.example.com
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: streamshub-strimzi-mcp
            port:
              number: 8080
```

### Route (OpenShift)

Create an edge-terminated Route:

```bash
oc -n streamshub-mcp create route edge streamshub-strimzi-mcp \
  --service=streamshub-strimzi-mcp \
  --port=http

# Get the Route hostname
oc -n streamshub-mcp get route streamshub-strimzi-mcp -o jsonpath='{.spec.host}'
```

Configure your MCP client with the Route URL:

```bash
claude mcp add --transport http strimzi https://strimzi-mcp-streamshub-mcp.apps.example.com/mcp
```

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `MCP_LOG_TAIL_LINES` | `200` | Default number of log lines to tail per pod |
| `LOKI_URL` | - | Loki endpoint URL (e.g., `http://loki:3100`) |
| `LOKI_AUTH_ENABLED` | `false` | Enable Loki authentication |
| `LOKI_AUTH_TOKEN` | - | Bearer token for Loki authentication |
| `PROMETHEUS_URL` | - | Prometheus endpoint URL (e.g., `http://prometheus:9090`) |
| `PROMETHEUS_AUTH_ENABLED` | `false` | Enable Prometheus authentication |
| `PROMETHEUS_AUTH_TOKEN` | - | Bearer token for Prometheus authentication |

### Using ConfigMaps

Store configuration in a ConfigMap:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: streamshub-mcp-config
  namespace: streamshub-mcp
data:
  MCP_LOG_TAIL_LINES: "500"
  LOKI_URL: "http://loki.monitoring:3100"
  PROMETHEUS_URL: "http://prometheus.monitoring:9090"
```

Reference in Deployment:

```yaml
spec:
  template:
    spec:
      containers:
      - name: strimzi-mcp
        envFrom:
        - configMapRef:
            name: streamshub-mcp-config
```

### Using Secrets

Store sensitive data in Secrets:

```bash
kubectl create secret generic streamshub-mcp-secrets \
  --from-literal=LOKI_AUTH_TOKEN=your-token-here \
  --from-literal=PROMETHEUS_AUTH_TOKEN=your-token-here \
  -n streamshub-mcp
```

Reference in Deployment:

```yaml
spec:
  template:
    spec:
      containers:
      - name: strimzi-mcp
        env:
        - name: LOKI_AUTH_TOKEN
          valueFrom:
            secretKeyRef:
              name: streamshub-mcp-secrets
              key: LOKI_AUTH_TOKEN
        - name: PROMETHEUS_AUTH_TOKEN
          valueFrom:
            secretKeyRef:
              name: streamshub-mcp-secrets
              key: PROMETHEUS_AUTH_TOKEN
```

## Health Checks

The MCP server exposes health endpoints:

- **Liveness**: `/q/health/live` — Server is running
- **Readiness**: `/q/health/ready` — Server is ready to accept requests
- **Health**: `/q/health` — Combined health status

Health probes are configured in the Deployment:

```yaml
livenessProbe:
  httpGet:
    path: /q/health/live
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 10
readinessProbe:
  httpGet:
    path: /q/health/ready
    port: 8080
  initialDelaySeconds: 10
  periodSeconds: 5
```

## Monitoring

### Metrics

MCP servers expose Prometheus metrics at `/q/metrics`:

```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: streamshub-strimzi-mcp
  namespace: streamshub-mcp
spec:
  selector:
    matchLabels:
      app: streamshub-strimzi-mcp
  endpoints:
  - port: http
    path: /q/metrics
```

### Logging

Configure log levels via environment variables:

```yaml
env:
- name: QUARKUS_LOG_LEVEL
  value: "INFO"
- name: QUARKUS_LOG_CATEGORY__IO_STREAMSHUB_MCP__LEVEL
  value: "DEBUG"
```

Collect logs using your cluster's logging solution (e.g., Loki, Elasticsearch).

## Production Considerations

### High Availability

Deploy multiple replicas for high availability:

```yaml
spec:
  replicas: 3
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 1
      maxSurge: 1
```

### Resource Limits

Set appropriate resource requests and limits:

```yaml
resources:
  requests:
    memory: "512Mi"
    cpu: "250m"
  limits:
    memory: "1Gi"
    cpu: "1000m"
```

### Security

- Use read-only root filesystem
- Run as non-root user
- Drop all capabilities
- Enable security context

```yaml
securityContext:
  runAsNonRoot: true
  runAsUser: 1000
  allowPrivilegeEscalation: false
  capabilities:
    drop:
    - ALL
  readOnlyRootFilesystem: true
```

### Network Policies

Restrict network access:

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: streamshub-mcp-netpol
  namespace: streamshub-mcp
spec:
  podSelector:
    matchLabels:
      app: streamshub-strimzi-mcp
  policyTypes:
  - Ingress
  - Egress
  ingress:
  - from:
    - namespaceSelector:
        matchLabels:
          name: ingress-nginx
    ports:
    - protocol: TCP
      port: 8080
  egress:
  - to:
    - namespaceSelector: {}
    ports:
    - protocol: TCP
      port: 6443  # Kubernetes API
    - protocol: TCP
      port: 443   # HTTPS
```

## Troubleshooting

### Pod Not Starting

```bash
# Check pod status
kubectl -n streamshub-mcp get pods

# View pod events
kubectl -n streamshub-mcp describe pod <pod-name>

# Check logs
kubectl -n streamshub-mcp logs <pod-name>
```

### RBAC Issues

```bash
# Check ServiceAccount
kubectl -n streamshub-mcp get serviceaccount streamshub-mcp

# Check ClusterRoleBinding
kubectl get clusterrolebinding streamshub-mcp-reader

# Test permissions
kubectl auth can-i list kafkas --as=system:serviceaccount:streamshub-mcp:streamshub-mcp
```

### Connection Issues

```bash
# Test service connectivity
kubectl -n streamshub-mcp run test --rm -it --image=curlimages/curl -- \
  curl http://streamshub-strimzi-mcp:8080/q/health

# Check service endpoints
kubectl -n streamshub-mcp get endpoints streamshub-strimzi-mcp
```

## Upgrading

### Rolling Update

```bash
# Update image
kubectl -n streamshub-mcp set image deployment/streamshub-strimzi-mcp \
  strimzi-mcp=quay.io/streamshub/strimzi-mcp:1.1.0

# Monitor rollout
kubectl -n streamshub-mcp rollout status deployment/streamshub-strimzi-mcp

# Rollback if needed
kubectl -n streamshub-mcp rollout undo deployment/streamshub-strimzi-mcp
```

### Configuration Changes

```bash
# Update ConfigMap
kubectl -n streamshub-mcp edit configmap streamshub-mcp-config

# Restart pods to pick up changes
kubectl -n streamshub-mcp rollout restart deployment/streamshub-strimzi-mcp
```

## Uninstalling

```bash
# Delete all resources
kubectl delete -f install/

# Or delete namespace (if dedicated)
kubectl delete namespace streamshub-mcp