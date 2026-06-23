#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENGINE="${CONTAINER_ENGINE:-podman}"
PLATFORMS="${CONTAINER_PLATFORM:-linux/amd64,linux/arm64}"
PUSH="${CONNECT_IMAGE_PUSH:-true}"

usage() {
    echo "Usage: $0 <image> [BUILD_ARG=VALUE ...]"
    echo ""
    echo "Build and optionally push a KafkaConnect image with the Camel Timer Source plugin."
    echo ""
    echo "Arguments:"
    echo "  <image>      Target image name (e.g. quay.io/streamshub/mcp-connect-test:latest)"
    echo ""
    echo "Extra arguments are passed as --build-arg flags to the container build."
    echo ""
    echo "Environment variables:"
    echo "  CONTAINER_ENGINE        Container engine to use (default: podman)"
    echo "  CONTAINER_PLATFORM      Target platforms (default: linux/amd64,linux/arm64)"
    echo "  CONNECT_IMAGE_PUSH      Push after build: true (default) or false"
    echo ""
    echo "Examples:"
    echo "  $0 quay.io/streamshub/mcp-connect-test:latest"
    echo "  $0 quay.io/streamshub/mcp-connect-test:latest STRIMZI_VERSION=1.1.0"
    echo "  CONNECT_IMAGE_PUSH=false $0 localhost/mcp-connect-test:test"
    echo "  CONTAINER_ENGINE=docker $0 quay.io/streamshub/mcp-connect-test:latest"
    exit 1
}

if [ $# -lt 1 ]; then
    usage
fi

IMAGE="$1"
shift

# Collect remaining args as --build-arg flags
BUILD_ARGS=()
for arg in "$@"; do
    BUILD_ARGS+=("--build-arg" "$arg")
done

if [ "$PUSH" = "false" ]; then
    echo "==> Building Connect image: $IMAGE (engine: $ENGINE, platform: $PLATFORMS, push: disabled)"
    "$ENGINE" build \
        -t "$IMAGE" \
        --platform "$PLATFORMS" \
        --pull=always \
        "${BUILD_ARGS[@]}" \
        -f "$SCRIPT_DIR/Dockerfile" "$SCRIPT_DIR"
else
    echo "==> Building multi-arch Connect image: $IMAGE (engine: $ENGINE, platforms: $PLATFORMS)"

    if [ "$ENGINE" = "docker" ]; then
        docker buildx build \
            --platform "$PLATFORMS" \
            --pull \
            --push \
            -t "$IMAGE" \
            "${BUILD_ARGS[@]}" \
            -f "$SCRIPT_DIR/Dockerfile" "$SCRIPT_DIR"
    else
        "$ENGINE" manifest rm "$IMAGE" 2>/dev/null || true

        IFS=',' read -ra PLATFORM_LIST <<< "$PLATFORMS"
        for PLATFORM in "${PLATFORM_LIST[@]}"; do
            echo "==> Building for $PLATFORM"
            "$ENGINE" build \
                --platform "$PLATFORM" \
                --manifest "$IMAGE" \
                --pull=always \
                "${BUILD_ARGS[@]}" \
                -f "$SCRIPT_DIR/Dockerfile" "$SCRIPT_DIR"
        done

        echo "==> Pushing manifest: $IMAGE"
        "$ENGINE" manifest push --all "$IMAGE" "docker://$IMAGE"
    fi
fi

echo "==> Done"
