#!/bin/bash

# Deploy or tear down Prometheus operator and instance for monitoring.
# Installs the Prometheus operator CRDs (including PodMonitor) required
# by Strimzi operator manifests.
#
# Usage:
#   ./setup-prometheus.sh deploy   - Deploy Prometheus operator + instance
#   ./setup-prometheus.sh teardown - Remove everything
#   ./setup-prometheus.sh help     - Show usage

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROMETHEUS_DIR="$SCRIPT_DIR/../manifests/prometheus"
MONITORING_NS="monitoring"

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

    log_info "Deploying Prometheus operator..."
    kubectl apply --server-side -k "$PROMETHEUS_DIR/operator/"

    log_info "Waiting for Prometheus operator to be ready..."
    kubectl wait --for=condition=Available \
        deployment/prometheus-operator \
        -n "$MONITORING_NS" \
        --timeout=120s
    log_success "Prometheus operator is ready"

    log_info "Deploying Prometheus additional scrape config..."
    kubectl apply -k "$PROMETHEUS_DIR/additional-properties/"

    log_info "Deploying Prometheus instance..."
    kubectl apply -k "$PROMETHEUS_DIR/instance/"

    log_info "Waiting for Prometheus instance to be ready..."
    kubectl wait --for=condition=Available \
        prometheus/prometheus \
        -n "$MONITORING_NS" \
        --timeout=120s 2>/dev/null || \
    kubectl rollout status statefulset/prometheus-prometheus \
        -n "$MONITORING_NS" \
        --timeout=120s 2>/dev/null || true
    log_success "Prometheus is ready"
}

teardown() {
    check_kubectl

    log_info "Removing Prometheus instance..."
    kubectl delete -k "$PROMETHEUS_DIR/instance/" --ignore-not-found

    log_info "Removing Prometheus additional scrape config..."
    kubectl delete -k "$PROMETHEUS_DIR/additional-properties/" --ignore-not-found

    log_info "Removing Prometheus operator..."
    kubectl delete -k "$PROMETHEUS_DIR/operator/" --ignore-not-found

    log_success "Prometheus teardown complete"
}

show_help() {
    echo "Usage: $(basename "$0") <command>"
    echo ""
    echo "Commands:"
    echo "  deploy   - Deploy Prometheus operator and instance"
    echo "  teardown - Remove Prometheus operator and instance"
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
