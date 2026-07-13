#!/bin/bash

# Deploy or tear down standalone Loki + Alloy for log collection in kind.
# Deploys Loki in monolithic mode with filesystem storage and Grafana Alloy
# for Kubernetes pod log collection via the Kubernetes API.
#
# Usage:
#   ./setup-loki-kind.sh deploy   - Deploy Loki + Alloy
#   ./setup-loki-kind.sh teardown - Remove everything
#   ./setup-loki-kind.sh help     - Show usage

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOKI_KIND_DIR="$SCRIPT_DIR/../manifests/loki-kind"
LOGGING_NS="logging"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info()    { echo -e "${BLUE}[INFO]  $1${NC}"; }
log_success() { echo -e "${GREEN}[OK]    $1${NC}"; }
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

    log_info "Deploying Loki and Alloy..."
    kubectl apply -k "$LOKI_KIND_DIR"

    log_info "Waiting for Loki to be ready..."
    kubectl wait --for=condition=Available \
        deployment/loki \
        -n "$LOGGING_NS" \
        --timeout=120s
    log_success "Loki is ready"

    log_info "Waiting for Alloy to be ready..."
    kubectl wait --for=condition=Available \
        deployment/alloy \
        -n "$LOGGING_NS" \
        --timeout=120s
    log_success "Alloy is ready"

    log_success "Loki + Alloy deployed successfully"
    log_info "In-cluster URL: http://loki.logging.svc.cluster.local:3100"
}

teardown() {
    check_kubectl

    log_info "Removing Loki and Alloy..."
    kubectl delete -k "$LOKI_KIND_DIR" --ignore-not-found

    log_success "Loki teardown complete"
}

show_help() {
    echo "Usage: $(basename "$0") <command>"
    echo ""
    echo "Commands:"
    echo "  deploy   - Deploy Loki and Alloy for log collection"
    echo "  teardown - Remove Loki and Alloy"
    echo "  help     - Show this help message"
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