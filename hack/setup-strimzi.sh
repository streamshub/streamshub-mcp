#!/bin/bash

# Deploy or tear down Strimzi operator and Kafka cluster for local development

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXAMPLES_DIR="$SCRIPT_DIR/../examples/strimzi"

OPERATOR_NS="strimzi"
KAFKA_NS="strimzi-kafka"
CLUSTER_NAME="mcp-cluster"

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

    log_info "Deploying Strimzi operator..."
    kubectl apply -k "$EXAMPLES_DIR/strimzi-operator/"

    log_info "Waiting for Strimzi operator to be ready..."
    kubectl wait --for=condition=Available \
        deployment/strimzi-cluster-operator \
        -n "$OPERATOR_NS" \
        --timeout=120s
    log_success "Strimzi operator is ready"

    log_info "Deploying Kafka cluster '$CLUSTER_NAME'..."
    kubectl apply -k "$EXAMPLES_DIR/kafka/"

    log_info "Waiting for Kafka cluster to be ready (this may take a few minutes)..."
    kubectl wait kafka/"$CLUSTER_NAME" \
        --for=condition=Ready \
        -n "$KAFKA_NS" \
        --timeout=600s
    log_success "Kafka cluster '$CLUSTER_NAME' is ready"

    echo ""
    log_success "Strimzi deployment complete"
    echo ""
    echo "Operator namespace:  $OPERATOR_NS"
    echo "Kafka namespace:     $KAFKA_NS"
    echo "Cluster name:        $CLUSTER_NAME"
    echo ""
    echo "Verify with:"
    echo "  kubectl get pods -n $OPERATOR_NS"
    echo "  kubectl get pods -n $KAFKA_NS"
    echo "  kubectl get kafka -n $KAFKA_NS"
}

teardown() {
    check_kubectl

    log_info "Removing Kafka cluster..."
    kubectl delete -k "$EXAMPLES_DIR/kafka/" --ignore-not-found

    log_info "Removing Strimzi operator..."
    kubectl delete -k "$EXAMPLES_DIR/strimzi-operator/" --ignore-not-found

    log_success "Strimzi teardown complete"
}

case "${1:-deploy}" in
    "deploy"|"up")
        deploy
        ;;
    "teardown"|"down"|"delete")
        teardown
        ;;
    "help"|"-h"|"--help")
        echo "Usage: $0 [command]"
        echo ""
        echo "Commands:"
        echo "  deploy   - Deploy Strimzi operator and Kafka cluster (default)"
        echo "  teardown - Remove Strimzi operator and Kafka cluster"
        echo "  help     - Show this help"
        ;;
    *)
        log_error "Unknown command: $1"
        echo "Use '$0 help' for usage information"
        exit 1
        ;;
esac