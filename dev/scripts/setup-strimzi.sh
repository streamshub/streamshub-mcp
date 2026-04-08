#!/bin/bash

# Deploy or tear down Strimzi operator and Kafka cluster for local development

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STRIMZI_DIR="$SCRIPT_DIR/../manifests/strimzi"
PROMETHEUS_DIR="$SCRIPT_DIR/../manifests/prometheus"

OPERATOR_NS="strimzi"
KAFKA_NS="strimzi-kafka"
CLUSTER_NAME="mcp-cluster"
MONITORING_NS="monitoring"

INSTALL_PROMETHEUS=false
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
    log_success "kubectl connected to cluster"
}

deploy_prometheus() {
    log_info "Deploying Prometheus operator and instance..."
    kubectl apply --server-side -k "$PROMETHEUS_DIR/operator/"

    log_info "Waiting for Prometheus operator to be ready..."
    kubectl wait --for=condition=Available \
        deployment/prometheus-operator \
        -n "$MONITORING_NS" \
        --timeout=120s
    log_success "Prometheus operator is ready"

    kubectl apply -k "$PROMETHEUS_DIR/additional-properties/"
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

teardown_prometheus() {
    log_info "Removing Prometheus instance..."
    kubectl delete -k "$PROMETHEUS_DIR/instance/" --ignore-not-found
    kubectl delete -k "$PROMETHEUS_DIR/additional-properties/" --ignore-not-found

    log_info "Removing Prometheus operator..."
    kubectl delete -k "$PROMETHEUS_DIR/operator/" --ignore-not-found

    log_success "Prometheus teardown complete"
}

deploy() {
    check_kubectl

    if [ "$INSTALL_PROMETHEUS" = true ]; then
        deploy_prometheus
    fi

    log_info "Deploying Strimzi operator..."
    kubectl apply -k "$STRIMZI_DIR/strimzi-operator/"

    log_info "Waiting for Strimzi operator to be ready..."
    kubectl wait --for=condition=Available \
        deployment/strimzi-cluster-operator \
        -n "$OPERATOR_NS" \
        --timeout=120s
    log_success "Strimzi operator is ready"

    log_info "Deploying Kafka cluster '$CLUSTER_NAME'..."
    if [ "$OCP" = true ]; then
        log_info "Using OpenShift route listener"
        kubectl apply -k "$STRIMZI_DIR/kafka/"
    else
        log_info "Using NodePort listener"
        kubectl apply -k "$STRIMZI_DIR/kafka-kubernetes/"
    fi

    log_info "Waiting for Kafka cluster to be ready (this may take a few minutes)..."
    kubectl wait kafka/"$CLUSTER_NAME" \
        --for=condition=Ready \
        -n "$KAFKA_NS" \
        --timeout=600s
    log_success "Kafka cluster '$CLUSTER_NAME' is ready"

    echo ""
    log_success "Deployment complete"
    echo ""
    if [ "$INSTALL_PROMETHEUS" = true ]; then
        echo "Monitoring namespace: $MONITORING_NS"
    fi
    echo "Operator namespace:  $OPERATOR_NS"
    echo "Kafka namespace:     $KAFKA_NS"
    echo "Cluster name:        $CLUSTER_NAME"
    echo ""
    echo "Verify with:"
    if [ "$INSTALL_PROMETHEUS" = true ]; then
        echo "  kubectl get pods -n $MONITORING_NS"
    fi
    echo "  kubectl get pods -n $OPERATOR_NS"
    echo "  kubectl get pods -n $KAFKA_NS"
    echo "  kubectl get kafka -n $KAFKA_NS"
}

teardown() {
    check_kubectl

    log_info "Removing Kafka cluster..."
    if [ "$OCP" = true ]; then
        kubectl delete -k "$STRIMZI_DIR/kafka/" --ignore-not-found
    else
        kubectl delete -k "$STRIMZI_DIR/kafka-kubernetes/" --ignore-not-found
    fi

    log_info "Removing Strimzi operator..."
    kubectl delete -k "$STRIMZI_DIR/strimzi-operator/" --ignore-not-found

    if [ "$INSTALL_PROMETHEUS" = true ]; then
        teardown_prometheus
    fi

    log_success "Teardown complete"
}

COMMAND="${1:-deploy}"
shift || true

for arg in "$@"; do
    case "$arg" in
        "--prometheus")
            INSTALL_PROMETHEUS=true
            ;;
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
    "deploy"|"up")
        deploy
        ;;
    "teardown"|"down"|"delete")
        teardown
        ;;
    "help"|"-h"|"--help")
        echo "Usage: $0 [command] [options]"
        echo ""
        echo "Commands:"
        echo "  deploy   - Deploy Strimzi operator and Kafka cluster (default)"
        echo "  teardown - Remove Strimzi operator and Kafka cluster"
        echo "  help     - Show this help"
        echo ""
        echo "Options:"
        echo "  --ocp         Use OpenShift route listener (default: NodePort)"
        echo "  --prometheus  Also deploy/remove Prometheus for metrics collection"
        ;;
    *)
        log_error "Unknown command: $COMMAND"
        echo "Use '$0 help' for usage information"
        exit 1
        ;;
esac