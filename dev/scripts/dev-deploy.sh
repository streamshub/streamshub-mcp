#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR/../.."

MODULE="strimzi-mcp"
OVERLAY_DIR="$PROJECT_ROOT/install/$MODULE/overlays/dev"

usage() {
    echo "Usage: $0 <image> [--kind | --minikube | --k3d]"
    echo ""
    echo "Deploy the MCP server to the current Kubernetes context using the dev overlay."
    echo ""
    echo "Arguments:"
    echo "  <image>      Container image to deploy (e.g. quay.io/streamshub/strimzi-mcp:0.1.0-SNAPSHOT)"
    echo "  --kind       Load image into Kind cluster before deploying"
    echo "  --minikube   Load image into Minikube before deploying"
    echo "  --k3d        Import image into k3d cluster before deploying"
    echo ""
    echo "Examples:"
    echo "  $0 quay.io/streamshub/strimzi-mcp:latest"
    echo "  $0 quay.io/streamshub/strimzi-mcp:test --kind"
    echo "  $0 my-registry.io/strimzi-mcp:dev --minikube"
    echo "When using a local cluster (Kind, Minikube, k3d), the loader flag loads the image"
    echo "directly so no registry push is needed. Without a loader flag, the image must be"
    echo "pushed to a registry accessible by the cluster."
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
LOADER="${2:-}"

cd "$PROJECT_ROOT"

case "$LOADER" in
    --kind)
        CLUSTER_NAME="${KIND_CLUSTER_NAME:-kind}"
        echo "==> Loading image into Kind cluster '$CLUSTER_NAME'"
        kind load docker-image "$IMAGE" --name "$CLUSTER_NAME"
        ;;
    --minikube)
        echo "==> Loading image into Minikube"
        minikube image load "$IMAGE"
        ;;
    --k3d)
        CLUSTER_NAME="${K3D_CLUSTER_NAME:-k3s-default}"
        echo "==> Importing image into k3d cluster '$CLUSTER_NAME'"
        k3d image import "$IMAGE" -c "$CLUSTER_NAME"
        ;;
    "")
        ;;
    *)
        echo "Unknown option: $LOADER"
        usage
        ;;
esac

echo "==> Deploying $MODULE with image: $IMAGE"
(cd "$OVERLAY_DIR" && kustomize edit set image "quay.io/streamshub/$MODULE=$IMAGE")
kubectl apply -k "$OVERLAY_DIR"

echo ""
echo "Deployed $MODULE to Kubernetes."
echo "Watch rollout: kubectl -n streamshub-mcp rollout status deployment/streamshub-$MODULE"
