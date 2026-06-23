#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENGINE="${CONTAINER_ENGINE:-podman}"

usage() {
    echo "Usage: $0 <image> [kind|minikube|k3d|openshift|external] [BUILD_ARG=VALUE ...]"
    echo ""
    echo "Build a KafkaConnect image with the Camel Timer Source plugin."
    echo ""
    echo "Arguments:"
    echo "  <image>      Target image name (e.g. localhost/streamshub/strimzi-connect:test)"
    echo ""
    echo "Loader modes:"
    echo "  kind         Build and load into Kind cluster"
    echo "  minikube     Build and load into Minikube"
    echo "  k3d          Build and import into k3d cluster"
    echo "  openshift    Build and push to OpenShift internal registry"
    echo "  external     Build and push to an external registry (image name must include registry)"
    echo "  (empty)      Just build locally"
    echo ""
    echo "Extra arguments are passed as --build-arg flags to the container build."
    echo ""
    echo "Environment variables:"
    echo "  CONTAINER_ENGINE     Container engine to use (default: podman)"
    echo "  CONTAINER_PLATFORM   Target platform (e.g. linux/amd64, default: native)"
    echo ""
    echo "Examples:"
    echo "  $0 localhost/streamshub/strimzi-connect:test"
    echo "  $0 localhost/streamshub/strimzi-connect:test kind"
    echo "  $0 localhost/streamshub/strimzi-connect:test kind STRIMZI_VERSION=1.1.0"
    echo "  $0 quay.io/streamshub/strimzi-connect:dev external"
    echo "  $0 strimzi-connect:dev openshift"
    echo "  CONTAINER_ENGINE=docker $0 localhost/streamshub/strimzi-connect:test kind"
    exit 1
}

if [ $# -lt 1 ]; then
    usage
fi

IMAGE="$1"
LOADER="${2:-}"
shift
shift 2>/dev/null || true

if [ -z "$LOADER" ]; then
    echo "==> No loader specified, skipping Connect image build"
    exit 0
fi

# Collect remaining args as --build-arg flags
BUILD_ARGS=()
for arg in "$@"; do
    BUILD_ARGS+=("--build-arg" "$arg")
done

PLATFORM_FLAG=()
if [ -n "${CONTAINER_PLATFORM:-}" ]; then
    PLATFORM_FLAG=("--platform" "$CONTAINER_PLATFORM")
fi

echo "==> Building Connect image: $IMAGE (engine: $ENGINE${CONTAINER_PLATFORM:+, platform: $CONTAINER_PLATFORM})"
"$ENGINE" build -t "$IMAGE" --pull=always "${PLATFORM_FLAG[@]}" "${BUILD_ARGS[@]}" -f "$SCRIPT_DIR/Dockerfile" "$SCRIPT_DIR"

case "$LOADER" in
    kind)
        CLUSTER_NAME="${KIND_CLUSTER_NAME:-kind-cluster}"
        echo "==> Loading image into Kind cluster '$CLUSTER_NAME'"
        ARCHIVE=$(mktemp /tmp/connect-image-XXXXXX.tar)
        "$ENGINE" save "$IMAGE" -o "$ARCHIVE"
        kind load image-archive "$ARCHIVE" --name "$CLUSTER_NAME"
        rm -f "$ARCHIVE"
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
    openshift)
        NAMESPACE="${CONNECT_IMAGE_NAMESPACE:-strimzi-kafka}"
        IMAGE_NAME=$(echo "$IMAGE" | rev | cut -d'/' -f1 | rev)

        echo "==> Pushing image to OpenShift internal registry (namespace: $NAMESPACE)"

        REGISTRY_ROUTE=$(oc get route default-route -n openshift-image-registry --template='{{ .spec.host }}' 2>/dev/null || true)
        if [ -z "$REGISTRY_ROUTE" ]; then
            echo "    Exposing OpenShift image registry..."
            oc patch configs.imageregistry.operator.openshift.io/cluster \
                --type merge -p '{"spec":{"defaultRoute":true}}'
            echo "    Waiting for registry route..."
            oc wait --for=jsonpath='{.spec.host}' \
                route/default-route -n openshift-image-registry --timeout=60s
            REGISTRY_ROUTE=$(oc get route default-route -n openshift-image-registry --template='{{ .spec.host }}')
        fi

        echo "    Registry route: $REGISTRY_ROUTE"
        oc registry login --insecure=true

        INTERNAL_IMAGE="$REGISTRY_ROUTE/$NAMESPACE/$IMAGE_NAME"
        echo "    Tagging: $IMAGE -> $INTERNAL_IMAGE"
        "$ENGINE" tag "$IMAGE" "$INTERNAL_IMAGE"

        echo "    Pushing to internal registry..."
        "$ENGINE" push "$INTERNAL_IMAGE" --tls-verify=false 2>/dev/null \
            || "$ENGINE" push "$INTERNAL_IMAGE"

        IN_CLUSTER_IMAGE="image-registry.openshift-image-registry.svc:5000/$NAMESPACE/$IMAGE_NAME"
        echo "    In-cluster image: $IN_CLUSTER_IMAGE"
        ;;
    external)
        echo "==> Pushing image: $IMAGE"
        "$ENGINE" push "$IMAGE"
        ;;
    *)
        echo "Unknown loader: $LOADER"
        usage
        ;;
esac

echo "==> Done"