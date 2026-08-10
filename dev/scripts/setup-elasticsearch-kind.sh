#!/bin/bash

# Deploy or tear down standalone Elasticsearch + Fluent Bit for log collection in kind.
# Deploys Elasticsearch in single-node mode with Fluent Bit DaemonSet
# for Kubernetes pod log collection.
#
# Usage:
#   ./setup-elasticsearch-kind.sh deploy   - Deploy Elasticsearch + Fluent Bit
#   ./setup-elasticsearch-kind.sh teardown - Remove everything
#   ./setup-elasticsearch-kind.sh help     - Show usage

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ES_KIND_DIR="$SCRIPT_DIR/../manifests/elasticsearch-kind"
ES_NS="elasticsearch"

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

    log_info "Deploying Elasticsearch and Fluent Bit..."
    kubectl apply -k "$ES_KIND_DIR"

    log_info "Waiting for Elasticsearch to be ready..."
    kubectl wait --for=condition=Available \
        deployment/elasticsearch \
        -n "$ES_NS" \
        --timeout=180s
    log_success "Elasticsearch is ready"

    log_info "Waiting for Fluent Bit to be ready..."
    kubectl rollout status daemonset/fluent-bit -n "$ES_NS" --timeout=120s
    log_success "Fluent Bit is ready"

    log_success "Elasticsearch + Fluent Bit deployed successfully"
    log_info "In-cluster URL: http://elasticsearch.elasticsearch.svc.cluster.local:9200"
}

teardown() {
    check_kubectl

    log_info "Removing Elasticsearch and Fluent Bit..."
    kubectl delete -k "$ES_KIND_DIR" --ignore-not-found

    log_success "Elasticsearch teardown complete"
}

show_help() {
    echo "Usage: $(basename "$0") <command>"
    echo ""
    echo "Commands:"
    echo "  deploy   - Deploy Elasticsearch and Fluent Bit for log collection"
    echo "  teardown - Remove Elasticsearch and Fluent Bit"
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
