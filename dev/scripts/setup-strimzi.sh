#!/bin/bash

# Deploy or tear down Strimzi operator and Kafka cluster for local development

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STRIMZI_DIR="$SCRIPT_DIR/../manifests/strimzi"

OPERATOR_NS="strimzi"
KAFKA_NS="strimzi-kafka"
CLUSTER_NAME="mcp-cluster"

INSTALL_PROMETHEUS=false
INSTALL_LOKI=false
INSTALL_DRAIN_CLEANER=false
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
    "$SCRIPT_DIR/setup-prometheus.sh" deploy
}

teardown_prometheus() {
    "$SCRIPT_DIR/setup-prometheus.sh" teardown
}

deploy_loki() {
    "$SCRIPT_DIR/setup-loki.sh" deploy
}

teardown_loki() {
    "$SCRIPT_DIR/setup-loki.sh" teardown
}

deploy_cert_manager() {
    if [ "$OCP" = true ]; then
        "$SCRIPT_DIR/setup-cert-manager.sh" deploy --ocp
    else
        "$SCRIPT_DIR/setup-cert-manager.sh" deploy
    fi
}

teardown_cert_manager() {
    if [ "$OCP" = true ]; then
        "$SCRIPT_DIR/setup-cert-manager.sh" teardown --ocp
    else
        "$SCRIPT_DIR/setup-cert-manager.sh" teardown
    fi
}

deploy_drain_cleaner() {
    local drain_cleaner_dir="$STRIMZI_DIR/drain-cleaner"
    local drain_cleaner_ns="strimzi-drain-cleaner"

    deploy_cert_manager

    if [ "$OCP" = true ]; then
        log_info "Deploying Strimzi Drain Cleaner (OpenShift)..."
        kubectl apply -k "$drain_cleaner_dir/openshift/"
    else
        log_info "Deploying Strimzi Drain Cleaner (cert-manager)..."
        kubectl apply -k "$drain_cleaner_dir/certmanager/"
    fi

    log_info "Waiting for Drain Cleaner to be ready..."
    kubectl wait --for=condition=Available \
        deployment/strimzi-drain-cleaner \
        -n "$drain_cleaner_ns" \
        --timeout=120s
    log_success "Strimzi Drain Cleaner is ready"
}

teardown_drain_cleaner() {
    local drain_cleaner_dir="$STRIMZI_DIR/drain-cleaner"

    log_info "Removing Strimzi Drain Cleaner..."
    if [ "$OCP" = true ]; then
        kubectl delete -k "$drain_cleaner_dir/openshift/" --ignore-not-found
    else
        kubectl delete -k "$drain_cleaner_dir/certmanager/" --ignore-not-found
    fi
    log_success "Drain Cleaner removed"
}

deploy() {
    check_kubectl

    if [ "$INSTALL_PROMETHEUS" = true ]; then
        deploy_prometheus
    fi

    if [ "$INSTALL_LOKI" = true ]; then
        deploy_loki
    fi

    if [ "$INSTALL_DRAIN_CLEANER" = true ]; then
        deploy_drain_cleaner
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

    log_info "Waiting for KafkaUsers to be ready..."
    kubectl wait kafkauser -l strimzi.io/cluster="$CLUSTER_NAME" \
        --for=condition=Ready \
        -n "$KAFKA_NS" \
        --timeout=120s
    log_success "KafkaUsers are ready"

    echo ""
    log_success "Deployment complete"
    echo ""
    if [ "$INSTALL_PROMETHEUS" = true ]; then
        echo "Monitoring namespace: monitoring"
    fi
    if [ "$INSTALL_LOKI" = true ]; then
        echo "Logging namespace:   openshift-logging"
    fi
    if [ "$INSTALL_DRAIN_CLEANER" = true ]; then
        echo "Drain Cleaner ns:    strimzi-drain-cleaner"
    fi
    echo "Operator namespace:  $OPERATOR_NS"
    echo "Kafka namespace:     $KAFKA_NS"
    echo "Cluster name:        $CLUSTER_NAME"
    echo ""
    echo "Verify with:"
    if [ "$INSTALL_PROMETHEUS" = true ]; then
        echo "  kubectl get pods -n monitoring"
    fi
    if [ "$INSTALL_LOKI" = true ]; then
        echo "  kubectl get pods -n openshift-logging"
    fi
    if [ "$INSTALL_DRAIN_CLEANER" = true ]; then
        echo "  kubectl get pods -n strimzi-drain-cleaner"
    fi
    echo "  kubectl get pods -n $OPERATOR_NS"
    echo "  kubectl get pods -n $KAFKA_NS"
    echo "  kubectl get kafka -n $KAFKA_NS"
    echo "  kubectl get kafkauser -n $KAFKA_NS"
}

teardown() {
    check_kubectl

    log_info "Removing Kafka cluster..."
    if [ "$OCP" = true ]; then
        kubectl delete -k "$STRIMZI_DIR/kafka/" --ignore-not-found
    else
        kubectl delete -k "$STRIMZI_DIR/kafka-kubernetes/" --ignore-not-found
    fi

    if [ "$INSTALL_DRAIN_CLEANER" = true ]; then
        teardown_drain_cleaner
    fi

    log_info "Removing Strimzi operator..."
    kubectl delete -k "$STRIMZI_DIR/strimzi-operator/" --ignore-not-found

    if [ "$INSTALL_PROMETHEUS" = true ]; then
        teardown_prometheus
    fi

    if [ "$INSTALL_LOKI" = true ]; then
        teardown_loki
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
        "--loki")
            INSTALL_LOKI=true
            ;;
        "--drain-cleaner")
            INSTALL_DRAIN_CLEANER=true
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
        echo "  --ocp            Use OpenShift route listener (default: NodePort)"
        echo "  --prometheus     Also deploy/remove Prometheus for metrics collection"
        echo "  --loki           Also deploy/remove Loki for log collection (OpenShift only)"
        echo "  --drain-cleaner  Also deploy/remove Strimzi Drain Cleaner"
        ;;
    *)
        log_error "Unknown command: $COMMAND"
        echo "Use '$0 help' for usage information"
        exit 1
        ;;
esac
