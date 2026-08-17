#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR/../.."

MODULE="strimzi-mcp"
BASE_IMAGE="quay.io/streamshub/$MODULE:latest"
OCP=false
LOKI=false
ELASTICSEARCH=false
PROMETHEUS=false
OTEL=false
LOADER=""

usage() {
    echo "Usage: $0 <image> [--kind | --minikube | --k3d] [--ocp]"
    echo ""
    echo "Deploy the MCP server to the current Kubernetes context using the dev overlay."
    echo ""
    echo "Arguments:"
    echo "  <image>      Container image to deploy (e.g. quay.io/streamshub/strimzi-mcp:0.1.0-SNAPSHOT)"
    echo ""
    echo "Options:"
    echo "  --kind       Load image into Kind cluster before deploying"
    echo "  --minikube   Load image into Minikube before deploying"
    echo "  --k3d        Import image into k3d cluster before deploying"
    echo "  --ocp          Deploy with OpenShift Route for external access"
    echo "  --loki         Enable Loki log provider"
    echo "                 (OCP: OCP Logging with sa-token auth | Kind: no auth)"
    echo "  --elasticsearch Enable Elasticsearch log provider"
    echo "                 (OCP: ECK with API key from elasticsearch-mcp-read-token | Kind: no auth)"
    echo "  --prometheus   Enable Prometheus metrics provider"
    echo "                 (OCP: Thanos Querier with sa-token auth)"
    echo "  --otel         Enable OpenTelemetry tracing"
    echo "                 (defaults to Jaeger in observability namespace)"
    echo ""
    echo "Examples:"
    echo "  $0 quay.io/streamshub/strimzi-mcp:latest"
    echo "  $0 quay.io/streamshub/strimzi-mcp:test --kind"
    echo "  $0 my-registry.io/strimzi-mcp:dev --minikube"
    echo "  $0 quay.io/streamshub/strimzi-mcp:latest --ocp"
    echo "  $0 quay.io/streamshub/strimzi-mcp:latest --ocp --loki --prometheus"
    echo "  $0 quay.io/streamshub/strimzi-mcp:latest --kind --elasticsearch"
    echo ""
    echo "Override defaults with environment variables:"
    echo "  QUARKUS_REST_CLIENT_LOKI_URL=https://custom:3100 \\"
    echo "    $0 <image> --ocp --loki"
    echo ""
    echo "  QUARKUS_REST_CLIENT_ELASTICSEARCH_URL=http://elasticsearch:9200 \\"
    echo "    MCP_LOG_ELASTICSEARCH_INDEX_PATTERN=app-logs-* \\"
    echo "    $0 <image> --kind --elasticsearch"
    echo ""
    echo "  QUARKUS_REST_CLIENT_PROMETHEUS_URL=http://prometheus:9090 \\"
    echo "    $0 <image> --ocp --prometheus"
    echo ""
    echo "When using a local cluster (Kind, Minikube, k3d), the loader flag loads the image"
    echo "directly so no registry push is needed. Without a loader flag, the image must be"
    echo "pushed to a registry accessible by the cluster."
    echo ""
    echo "Prerequisites for --elasticsearch on OpenShift:"
    echo "  Run dev/scripts/setup-elasticsearch.sh first to deploy ECK and create API keys."
    echo ""
    echo "Build the image first with Maven:"
    echo "  ./mvnw package -pl strimzi-mcp -am -DskipTests \\"
    echo "    -Dquarkus.container-image.build=true \\"
    echo "    -Dquarkus.container-image.tag=<tag>"
    echo ""
    echo "To build and push to a custom registry:"
    echo "  ./mvnw package -pl strimzi-mcp -am -DskipTests \\"
    echo "    -Dquarkus.container-image.build=true \\"
    echo "    -Dquarkus.container-image.push=true \\"
    echo "    -Dquarkus.container-image.registry=quay.io \\"
    echo "    -Dquarkus.container-image.group=kornys \\"
    echo "    -Dquarkus.container-image.name=mcp-server \\"
    echo "    -Dquarkus.container-image.tag=latest"
    echo ""
    echo "  Then deploy:"
    echo "  $0 quay.io/kornys/mcp-server:latest"
    exit 1
}

if [ $# -lt 1 ]; then
    usage
fi

IMAGE="$1"
shift

for arg in "$@"; do
    case "$arg" in
        --kind)     LOADER="kind" ;;
        --minikube) LOADER="minikube" ;;
        --k3d)      LOADER="k3d" ;;
        --ocp)      OCP=true ;;
        --loki)     LOKI=true ;;
        --elasticsearch) ELASTICSEARCH=true ;;
        --prometheus) PROMETHEUS=true ;;
        --otel)     OTEL=true ;;
        *)
            echo "Unknown option: $arg"
            usage
            ;;
    esac
done

cd "$PROJECT_ROOT"

case "$LOADER" in
    kind)
        CLUSTER_NAME="${KIND_CLUSTER_NAME:-kind}"
        echo "==> Loading image into Kind cluster '$CLUSTER_NAME'"
        kind load docker-image "$IMAGE" --name "$CLUSTER_NAME"
        ;;
    minikube)
        echo "==> Loading image into Minikube"
        minikube image load "$IMAGE"
        ;;
    k3d)
        CLUSTER_NAME="${K3D_CLUSTER_NAME:-k3s-default}"
        echo "==> Importing image into k3d cluster '$CLUSTER_NAME'"
        k3d image import "$IMAGE" -c "$CLUSTER_NAME"
        ;;
esac

if [ "$OCP" = true ]; then
    OVERLAY_DIR="$PROJECT_ROOT/install/$MODULE/overlays/dev-openshift"
else
    OVERLAY_DIR="$PROJECT_ROOT/install/$MODULE/overlays/dev"
fi

echo "==> Deploying $MODULE with image: $IMAGE"
kustomize build "$OVERLAY_DIR" | sed "s|$BASE_IMAGE|$IMAGE|g" | kubectl apply -f -

DEPLOY_NS="streamshub-mcp"
DEPLOY_TARGET="deployment/streamshub-mcp-strimzi"
SA_NAME="streamshub-mcp-strimzi"

OPTIONAL_DIR="$PROJECT_ROOT/install/$MODULE/optional"

if [ "$LOKI" = true ] || [ "$PROMETHEUS" = true ]; then
    echo "==> Granting monitoring RBAC to service account $SA_NAME"
    kubectl apply -f "$OPTIONAL_DIR/clusterrolebinding-cluster-monitoring-view.yaml"
fi

if [ "$LOKI" = true ]; then
    echo "==> Granting Loki RBAC to service account $SA_NAME"
    kubectl apply -f "$OPTIONAL_DIR/clusterrole-loki-application-view.yaml"
    kubectl apply -f "$OPTIONAL_DIR/clusterrolebinding-loki-application-view.yaml"
    echo "==> Configuring Loki log provider"
    kubectl -n "$DEPLOY_NS" set env "$DEPLOY_TARGET" \
        MCP_LOG_PROVIDER="${MCP_LOG_PROVIDER:-streamshub-loki}" \
        MCP_LOG_LOKI_AUTH_MODE="${MCP_LOG_LOKI_AUTH_MODE:-sa-token}" \
        QUARKUS_REST_CLIENT_LOKI_URL="${QUARKUS_REST_CLIENT_LOKI_URL:-https://logging-loki-gateway-http.openshift-logging.svc:8080/api/logs/v1/application}" \
        MCP_LOG_LOKI_LABEL_NAMESPACE="${MCP_LOG_LOKI_LABEL_NAMESPACE:-kubernetes_namespace_name}" \
        MCP_LOG_LOKI_LABEL_POD="${MCP_LOG_LOKI_LABEL_POD:-kubernetes_pod_name}" \
        QUARKUS_TLS_TRUST_ALL="${QUARKUS_TLS_TRUST_ALL:-true}"
fi

if [ "$ELASTICSEARCH" = true ]; then
    echo "==> Configuring Elasticsearch log provider"
    if [ "$OCP" = true ]; then
        # OpenShift: ECK with API key authentication
        ES_TOKEN=$(kubectl get secret elasticsearch-mcp-read-token -n elasticsearch-logging -o jsonpath='{.data.token}' 2>/dev/null | base64 -d || echo "")
        if [ -z "$ES_TOKEN" ]; then
            echo "Warning: elasticsearch-mcp-read-token secret not found. Run dev/scripts/setup-elasticsearch.sh first."
            echo "Falling back to basic auth mode (requires QUARKUS_REST_CLIENT_ELASTICSEARCH_USERNAME and PASSWORD env vars)."
            kubectl -n "$DEPLOY_NS" set env "$DEPLOY_TARGET" \
                MCP_LOG_PROVIDER="${MCP_LOG_PROVIDER:-streamshub-elasticsearch}" \
                MCP_LOG_ELASTICSEARCH_AUTH_MODE="${MCP_LOG_ELASTICSEARCH_AUTH_MODE:-basic}" \
                QUARKUS_REST_CLIENT_ELASTICSEARCH_URL="${QUARKUS_REST_CLIENT_ELASTICSEARCH_URL:-https://elasticsearch-es-http.elasticsearch-logging.svc:9200}" \
                MCP_LOG_ELASTICSEARCH_INDEX_PATTERN="${MCP_LOG_ELASTICSEARCH_INDEX_PATTERN:-kubernetes}" \
                QUARKUS_TLS_TRUST_ALL="${QUARKUS_TLS_TRUST_ALL:-true}"
        else
            kubectl -n "$DEPLOY_NS" set env "$DEPLOY_TARGET" \
                MCP_LOG_PROVIDER="${MCP_LOG_PROVIDER:-streamshub-elasticsearch}" \
                MCP_LOG_ELASTICSEARCH_AUTH_MODE="${MCP_LOG_ELASTICSEARCH_AUTH_MODE:-bearer-token}" \
                MCP_LOG_ELASTICSEARCH_BEARER_TOKEN="$ES_TOKEN" \
                QUARKUS_REST_CLIENT_ELASTICSEARCH_URL="${QUARKUS_REST_CLIENT_ELASTICSEARCH_URL:-https://elasticsearch-es-http.elasticsearch-logging.svc:9200}" \
                MCP_LOG_ELASTICSEARCH_INDEX_PATTERN="${MCP_LOG_ELASTICSEARCH_INDEX_PATTERN:-kubernetes}" \
                QUARKUS_TLS_TRUST_ALL="${QUARKUS_TLS_TRUST_ALL:-true}"
        fi
    else
        # Kind: no auth, Fluent Bit creates date-suffixed indices
        kubectl -n "$DEPLOY_NS" set env "$DEPLOY_TARGET" \
            MCP_LOG_PROVIDER="${MCP_LOG_PROVIDER:-streamshub-elasticsearch}" \
            MCP_LOG_ELASTICSEARCH_AUTH_MODE="${MCP_LOG_ELASTICSEARCH_AUTH_MODE:-none}" \
            QUARKUS_REST_CLIENT_ELASTICSEARCH_URL="${QUARKUS_REST_CLIENT_ELASTICSEARCH_URL:-http://elasticsearch.elasticsearch.svc.cluster.local:9200}" \
            MCP_LOG_ELASTICSEARCH_INDEX_PATTERN="${MCP_LOG_ELASTICSEARCH_INDEX_PATTERN:-kubernetes-*}"
    fi
fi

if [ "$PROMETHEUS" = true ]; then
    echo "==> Configuring Prometheus metrics provider"
    kubectl -n "$DEPLOY_NS" set env "$DEPLOY_TARGET" \
        MCP_METRICS_PROVIDER="${MCP_METRICS_PROVIDER:-streamshub-prometheus}" \
        MCP_METRICS_PROMETHEUS_AUTH_MODE="${MCP_METRICS_PROMETHEUS_AUTH_MODE:-sa-token}" \
        QUARKUS_REST_CLIENT_PROMETHEUS_URL="${QUARKUS_REST_CLIENT_PROMETHEUS_URL:-https://thanos-querier.openshift-monitoring.svc:9091}"
fi

if [ "$OTEL" = true ]; then
    echo "==> Configuring OpenTelemetry tracing"
    kubectl -n "$DEPLOY_NS" set env "$DEPLOY_TARGET" \
        QUARKUS_OTEL_SDK_DISABLED="false" \
        QUARKUS_OTEL_EXPORTER_OTLP_ENDPOINT="${QUARKUS_OTEL_EXPORTER_OTLP_ENDPOINT:-http://jaeger-collector.observability.svc.cluster.local:4317}"
fi

echo ""
echo "Deployed $MODULE to Kubernetes."
if [ "$LOKI" = true ]; then
    echo "  Loki log provider: enabled"
fi
if [ "$ELASTICSEARCH" = true ]; then
    echo "  Elasticsearch log provider: enabled"
fi
if [ "$PROMETHEUS" = true ]; then
    echo "  Prometheus metrics: enabled"
fi
if [ "$OTEL" = true ]; then
    echo "  OpenTelemetry tracing: enabled"
fi
echo "Watch rollout: kubectl -n $DEPLOY_NS rollout status $DEPLOY_TARGET"
