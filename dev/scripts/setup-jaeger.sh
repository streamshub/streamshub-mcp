#!/bin/bash

# Deploy or tear down Jaeger all-in-one for tracing.
# Provides an OTLP collector endpoint for the MCP server's OpenTelemetry
# integration and a UI for viewing traces.
#
# Usage:
#   ./setup-jaeger.sh deploy   - Deploy Jaeger all-in-one
#   ./setup-jaeger.sh teardown - Remove everything
#   ./setup-jaeger.sh help     - Show usage
#
# After deployment, configure the MCP server to export traces:
#   QUARKUS_OTEL_ENABLED=true
#   QUARKUS_OTEL_EXPORTER_OTLP_ENDPOINT=http://jaeger-collector.observability.svc.cluster.local:4317

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAEGER_DIR="$SCRIPT_DIR/../manifests/jaeger"
JAEGER_NS="observability"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info()    { echo -e "${BLUE}[INFO]  $1${NC}"; }
log_success() { echo -e "${GREEN}[OK]    $1${NC}"; }
log_warning() { echo -e "${YELLOW}[WARN]  $1${NC}"; }
log_error()   { echo -e "${RED}[ERROR] $1${NC}"; }

check_kubectl() {
    if ! command -v kubectl &> /dev/null; then
        log_error "kubectl is not installed."
        exit 1
    fi
    if ! kubectl cluster-info &> /dev/null; then
        log_error "Cannot connect to Kubernetes cluster. Check your kubeconfig."
        exit 1
    fi
    log_success "kubectl connected to cluster"
}

deploy() {
    check_kubectl

    log_info "Deploying Jaeger all-in-one..."
    kubectl apply -k "$JAEGER_DIR"

    log_info "Waiting for Jaeger to be ready..."
    kubectl wait --for=condition=Available \
        deployment/jaeger \
        -n "$JAEGER_NS" \
        --timeout=120s
    log_success "Jaeger is ready"

    # Create OpenShift Routes if the CRD is available
    local ui_route_host=""
    local collector_route_host=""
    if kubectl api-resources --api-group=route.openshift.io &>/dev/null && \
       kubectl api-resources --api-group=route.openshift.io | grep -q routes; then
        log_info "OpenShift detected — creating Routes for Jaeger UI and collector..."
        kubectl apply -f "$JAEGER_DIR/route.yaml"
        ui_route_host=$(kubectl get route jaeger-ui -n "$JAEGER_NS" \
            -o jsonpath='{.spec.host}' 2>/dev/null || echo "")
        collector_route_host=$(kubectl get route jaeger-collector -n "$JAEGER_NS" \
            -o jsonpath='{.spec.host}' 2>/dev/null || echo "")
    fi

    echo ""
    log_success "Jaeger deployment complete"
    echo ""
    echo "Namespace:  $JAEGER_NS"
    echo "Collector:  jaeger-collector.$JAEGER_NS.svc.cluster.local:4317 (OTLP gRPC)"
    echo "            jaeger-collector.$JAEGER_NS.svc.cluster.local:4318 (OTLP HTTP)"
    echo ""
    echo "MCP server configuration (in-cluster):"
    echo "  QUARKUS_OTEL_ENABLED=true"
    echo "  QUARKUS_OTEL_EXPORTER_OTLP_ENDPOINT=http://jaeger-collector.$JAEGER_NS.svc.cluster.local:4317"
    echo ""
    if [ -n "$collector_route_host" ]; then
        echo "MCP server configuration (local dev mode via Route):"
        echo "  QUARKUS_OTEL_ENABLED=true"
        echo "  QUARKUS_OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf"
        echo "  QUARKUS_OTEL_EXPORTER_OTLP_ENDPOINT=https://$collector_route_host"
        echo "  QUARKUS_OTEL_EXPORTER_OTLP_HEADERS=Authorization=Bearer \$(oc whoami -t)"
        echo ""
    fi
    echo "Access the Jaeger UI:"
    if [ -n "$ui_route_host" ]; then
        echo "  https://$ui_route_host (OpenShift Route)"
    else
        log_warning "Route not available (not on OpenShift?). Use port-forward instead:"
        echo "  kubectl port-forward -n $JAEGER_NS svc/jaeger-ui 16686:16686"
        echo "  Then open http://localhost:16686"
    fi
}

teardown() {
    check_kubectl

    log_info "Removing Jaeger Route..."
    kubectl delete -f "$JAEGER_DIR/route.yaml" --ignore-not-found 2>/dev/null || true

    log_info "Removing Jaeger..."
    kubectl delete -k "$JAEGER_DIR" --ignore-not-found

    log_success "Jaeger teardown complete"
}

show_help() {
    echo "Usage: $(basename "$0") <command>"
    echo ""
    echo "Commands:"
    echo "  deploy   - Deploy Jaeger all-in-one (with OTLP collector)"
    echo "  teardown - Remove Jaeger"
    echo "  help     - Show this help message"
    echo ""
    echo "Deploys Jaeger all-in-one for dev/test tracing."
    echo "The OTLP collector accepts traces on port 4317 (gRPC) and 4318 (HTTP)."
}

# Main
COMMAND="${1:-help}"

case "$COMMAND" in
    deploy)
        deploy
        ;;
    teardown)
        teardown
        ;;
    help|--help|-h)
        show_help
        ;;
    *)
        log_error "Unknown command: $COMMAND"
        show_help
        exit 1
        ;;
esac
