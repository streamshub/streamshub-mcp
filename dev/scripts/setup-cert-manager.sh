#!/bin/bash

# Deploy or tear down cert-manager for TLS certificate management.
# On Kubernetes: installs from upstream release manifests.
# On OpenShift: installs via the Red Hat cert-manager operator (OLM).
#
# Usage:
#   ./setup-cert-manager.sh deploy              - Deploy on Kubernetes
#   ./setup-cert-manager.sh deploy --ocp        - Deploy on OpenShift via operator
#   ./setup-cert-manager.sh teardown            - Remove from Kubernetes
#   ./setup-cert-manager.sh teardown --ocp      - Remove from OpenShift
#   ./setup-cert-manager.sh help                - Show usage

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CERT_MANAGER_DIR="$SCRIPT_DIR/../manifests/cert-manager"

CERT_MANAGER_VERSION="v1.17.2"
CERT_MANAGER_NS="cert-manager"
CERT_MANAGER_OPERATOR_NS="cert-manager-operator"

OCP=false

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
}

is_cert_manager_installed() {
    kubectl get crd certificates.cert-manager.io &> /dev/null
}

deploy_kubernetes() {
    if is_cert_manager_installed; then
        log_success "cert-manager is already installed"
        return
    fi

    local manifest_url="https://github.com/cert-manager/cert-manager/releases/download/${CERT_MANAGER_VERSION}/cert-manager.yaml"

    log_info "Deploying cert-manager ${CERT_MANAGER_VERSION} from upstream manifests..."
    kubectl apply -f "$manifest_url"

    log_info "Waiting for cert-manager to be ready..."
    kubectl wait --for=condition=Available \
        deployment/cert-manager \
        -n "$CERT_MANAGER_NS" \
        --timeout=120s
    kubectl wait --for=condition=Available \
        deployment/cert-manager-webhook \
        -n "$CERT_MANAGER_NS" \
        --timeout=120s
    kubectl wait --for=condition=Available \
        deployment/cert-manager-cainjector \
        -n "$CERT_MANAGER_NS" \
        --timeout=120s
    log_success "cert-manager ${CERT_MANAGER_VERSION} is ready"
}

deploy_openshift() {
    if is_cert_manager_installed; then
        log_success "cert-manager is already installed"
        return
    fi

    log_info "Deploying cert-manager operator via OLM..."
    kubectl apply -k "$CERT_MANAGER_DIR/openshift/"

    log_info "Waiting for cert-manager operator subscription to be processed..."
    local retries=30
    while [ $retries -gt 0 ]; do
        if is_cert_manager_installed; then
            break
        fi
        sleep 10
        retries=$((retries - 1))
    done

    if ! is_cert_manager_installed; then
        log_error "cert-manager CRDs not found after waiting. Check operator status:"
        log_error "  kubectl get csv -n $CERT_MANAGER_OPERATOR_NS"
        exit 1
    fi

    log_info "Waiting for cert-manager deployments to be ready..."
    kubectl wait --for=condition=Available \
        deployment/cert-manager \
        -n "$CERT_MANAGER_NS" \
        --timeout=180s 2>/dev/null || true
    log_success "cert-manager operator is ready"
}

teardown_kubernetes() {
    local manifest_url="https://github.com/cert-manager/cert-manager/releases/download/${CERT_MANAGER_VERSION}/cert-manager.yaml"

    log_info "Removing cert-manager..."
    kubectl delete -f "$manifest_url" --ignore-not-found
    log_success "cert-manager removed"
}

teardown_openshift() {
    log_info "Removing cert-manager operator..."
    kubectl delete -k "$CERT_MANAGER_DIR/openshift/" --ignore-not-found
    log_success "cert-manager operator removed"
}

deploy() {
    check_kubectl
    if [ "$OCP" = true ]; then
        deploy_openshift
    else
        deploy_kubernetes
    fi
}

teardown() {
    check_kubectl
    if [ "$OCP" = true ]; then
        teardown_openshift
    else
        teardown_kubernetes
    fi
}

show_help() {
    echo "Usage: $(basename "$0") <command> [options]"
    echo ""
    echo "Commands:"
    echo "  deploy   - Deploy cert-manager"
    echo "  teardown - Remove cert-manager"
    echo "  help     - Show this help message"
    echo ""
    echo "Options:"
    echo "  --ocp    Use OpenShift operator (OLM) instead of upstream manifests"
}

# Main
COMMAND="${1:-help}"
shift || true

for arg in "$@"; do
    case "$arg" in
        "--ocp")
            OCP=true
            ;;
        *)
            log_error "Unknown option: $arg"
            exit 1
            ;;
    esac
done

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
