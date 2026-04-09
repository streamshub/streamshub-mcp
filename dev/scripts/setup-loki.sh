#!/bin/bash

# Deploy or tear down Loki via the Loki Operator on OpenShift.
# Uses SeaweedFS as S3 storage backend for dev/test environments.
#
# Usage:
#   ./setup-loki.sh deploy   - Deploy Loki Operator + SeaweedFS + LokiStack
#   ./setup-loki.sh teardown - Remove everything
#
# After deployment, configure the MCP server to use the Loki log provider:
#   MCP_LOG_PROVIDER=loki
#   quarkus.rest-client.loki.url=https://logging-loki-gateway-http.openshift-logging.svc:8080/api/logs/v1/application

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MANIFESTS_DIR="$SCRIPT_DIR/../manifests/loki"

LOKI_NS="openshift-logging"
OPERATOR_NS="openshift-operators-redhat"

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

check_prerequisites() {
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

wait_for_crd() {
    local crd_name="$1"
    local timeout="${2:-300}"
    local elapsed=0

    log_info "Waiting for CRD '$crd_name' (timeout: ${timeout}s)..."
    while [ $elapsed -lt $timeout ]; do
        if kubectl get crd "$crd_name" &>/dev/null; then
            log_success "CRD '$crd_name' available"
            return 0
        fi
        sleep 5
        elapsed=$((elapsed + 5))
    done

    log_error "Timed out waiting for CRD '$crd_name'"
    log_error "Check operator status: kubectl get csv -n $OPERATOR_NS"
    return 1
}

deploy() {
    check_prerequisites

    # Check loki-operator channel (pick latest stable, don't trust defaultChannel)
    local channel
    channel=$(kubectl get packagemanifest loki-operator -n openshift-marketplace \
        -o jsonpath='{range .status.channels[*]}{.name}{"\n"}{end}' 2>/dev/null \
        | grep '^stable-' | sort -V | tail -1)
    if [ -z "$channel" ]; then
        log_error "loki-operator not found in openshift-marketplace."
        log_error "Ensure the OperatorHub catalog sources are available."
        exit 1
    fi
    log_success "Found loki-operator (channel: $channel)"

    # Update channel in manifest if different
    sed -i.bak "s/channel: stable-.*/channel: $channel/" "$MANIFESTS_DIR/loki-operator.yaml"
    rm -f "$MANIFESTS_DIR/loki-operator.yaml.bak"

    # Check for available StorageClass
    local sc
    sc=$(kubectl get storageclass -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || echo "")
    if [ -n "$sc" ]; then
        log_info "Using StorageClass: $sc"
        sed -i.bak "s/storageClassName: .*/storageClassName: $sc/" "$MANIFESTS_DIR/lokistack.yaml"
        rm -f "$MANIFESTS_DIR/lokistack.yaml.bak"
    fi

    # Phase 1: Namespace + Operator
    log_info "Phase 1: Installing Loki Operator..."
    kubectl apply -f "$MANIFESTS_DIR/namespace.yaml"
    kubectl create namespace "$OPERATOR_NS" --dry-run=client -o yaml | kubectl apply -f -
    kubectl apply -f "$MANIFESTS_DIR/loki-operator.yaml"

    # Phase 2: Wait for LokiStack CRD
    wait_for_crd "lokistacks.loki.grafana.com" 300

    # Phase 3: SeaweedFS + LokiStack
    log_info "Phase 3: Deploying SeaweedFS storage..."
    kubectl apply -f "$MANIFESTS_DIR/s3-storage.yaml"

    log_info "Waiting for SeaweedFS to be ready..."
    kubectl wait --for=condition=Available deployment/seaweedfs \
        -n "$LOKI_NS" --timeout=120s
    log_success "SeaweedFS is ready"

    log_info "Waiting for SeaweedFS bucket creation..."
    kubectl wait --for=condition=Complete job/seaweedfs-create-bucket \
        -n "$LOKI_NS" --timeout=120s 2>/dev/null || true
    log_success "SeaweedFS bucket ready"

    log_info "Phase 4: Creating LokiStack..."
    kubectl apply -f "$MANIFESTS_DIR/lokistack.yaml"

    log_info "Waiting for LokiStack to be ready (this may take a few minutes)..."
    local retries=60
    while [ $retries -gt 0 ]; do
        local ready
        ready=$(kubectl get lokistack logging-loki -n "$LOKI_NS" \
            -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || echo "")
        if [ "$ready" = "True" ]; then
            break
        fi
        retries=$((retries - 1))
        sleep 10
    done

    if [ $retries -eq 0 ]; then
        log_warning "LokiStack not yet ready. Check status:"
        log_warning "  kubectl get lokistack logging-loki -n $LOKI_NS -o yaml"
        log_warning "  kubectl get pods -n $LOKI_NS -l app.kubernetes.io/instance=logging-loki"
    else
        log_success "LokiStack is ready"
    fi

    # Phase 5: Install Cluster Logging Operator + ClusterLogForwarder
    log_info "Phase 5: Installing Cluster Logging Operator..."

    local logging_channel
    logging_channel=$(kubectl get packagemanifest cluster-logging -n openshift-marketplace \
        -o jsonpath='{range .status.channels[*]}{.name}{"\n"}{end}' 2>/dev/null \
        | grep '^stable-' | sort -V | tail -1)

    if [ -n "$logging_channel" ]; then
        log_success "Found cluster-logging (channel: $logging_channel)"
        sed -i.bak "s/channel: stable-.*/channel: $logging_channel/" "$MANIFESTS_DIR/cluster-logging.yaml"
        rm -f "$MANIFESTS_DIR/cluster-logging.yaml.bak"

        # Apply operator + ServiceAccount + RBAC first
        kubectl apply -f "$MANIFESTS_DIR/cluster-logging.yaml" 2>/dev/null || true

        # Wait for ClusterLogForwarder CRD
        wait_for_crd "clusterlogforwarders.observability.openshift.io" 300

        # Apply again to create the ClusterLogForwarder
        kubectl apply -f "$MANIFESTS_DIR/cluster-logging.yaml"
        log_success "ClusterLogForwarder created — logs from strimzi namespaces will be forwarded to Loki"

        # Wait for collector pods
        log_info "Waiting for log collector pods..."
        local collector_retries=30
        while [ $collector_retries -gt 0 ]; do
            local collector_count
            collector_count=$(kubectl get pods -n "$LOKI_NS" -l app.kubernetes.io/component=collector --no-headers 2>/dev/null | wc -l | tr -d ' ')
            if [ "$collector_count" -gt 0 ]; then
                log_success "Log collector pods running ($collector_count)"
                break
            fi
            collector_retries=$((collector_retries - 1))
            sleep 10
        done
    else
        log_warning "cluster-logging operator not found in marketplace. Skipping log forwarding."
        log_warning "Logs can be pushed manually to Loki via the API."
    fi

    # Phase 6: Create Route for external access
    log_info "Phase 6: Creating Route for external access..."
    kubectl apply -f "$MANIFESTS_DIR/route.yaml"

    local route_host
    route_host=$(kubectl get route logging-loki -n "$LOKI_NS" \
        -o jsonpath='{.spec.host}' 2>/dev/null || echo "")

    echo ""
    log_success "Loki deployment complete"
    echo ""
    echo "Namespace:      $LOKI_NS"
    echo "LokiStack:      logging-loki"
    echo "Gateway (in-cluster): https://logging-loki-gateway-http.$LOKI_NS.svc:8080"
    if [ -n "$route_host" ]; then
        echo "Route (external):     https://$route_host"
    fi
    echo ""
    echo "MCP server configuration (local dev mode):"
    if [ -n "$route_host" ]; then
        echo "  MCP_LOG_PROVIDER=loki"
        echo "  QUARKUS_REST_CLIENT_LOKI_URL=https://$route_host/api/logs/v1/application"
        echo "  QUARKUS_TLS_TRUST_ALL=true"
        echo "  MCP_LOG_LOKI_AUTH_MODE=bearer-token"
        echo "  MCP_LOG_LOKI_BEARER_TOKEN=\$(oc whoami -t)"
    else
        echo "  MCP_LOG_PROVIDER=loki"
        echo "  QUARKUS_REST_CLIENT_LOKI_URL=https://logging-loki-gateway-http.$LOKI_NS.svc:8080/api/logs/v1/application"
    fi
    echo ""
    echo "Verify with:"
    echo "  kubectl get lokistack logging-loki -n $LOKI_NS"
    echo "  kubectl get pods -n $LOKI_NS -l component=collector"
}

teardown() {
    check_prerequisites

    log_info "Removing Route..."
    kubectl delete -f "$MANIFESTS_DIR/route.yaml" --ignore-not-found 2>/dev/null || true

    log_info "Removing Cluster Logging..."
    kubectl delete -f "$MANIFESTS_DIR/cluster-logging.yaml" --ignore-not-found 2>/dev/null || true

    # Clean up Cluster Logging CSV
    local logging_csv
    logging_csv=$(kubectl get csv -n "$LOKI_NS" -o name 2>/dev/null | grep cluster-logging || echo "")
    if [ -n "$logging_csv" ]; then
        kubectl delete "$logging_csv" -n "$LOKI_NS" --ignore-not-found 2>/dev/null || true
    fi

    log_info "Removing LokiStack..."
    kubectl delete lokistack logging-loki -n "$LOKI_NS" --ignore-not-found 2>/dev/null || true

    log_info "Removing SeaweedFS..."
    kubectl delete -f "$MANIFESTS_DIR/s3-storage.yaml" --ignore-not-found 2>/dev/null || true

    log_info "Removing S3 secret..."
    kubectl delete secret logging-loki-s3 -n "$LOKI_NS" --ignore-not-found 2>/dev/null || true

    log_info "Removing Loki Operator..."
    kubectl delete -f "$MANIFESTS_DIR/loki-operator.yaml" --ignore-not-found 2>/dev/null || true

    # Clean up CSV
    local csv
    csv=$(kubectl get csv -n "$OPERATOR_NS" -o name 2>/dev/null | grep loki || echo "")
    if [ -n "$csv" ]; then
        kubectl delete "$csv" -n "$OPERATOR_NS" --ignore-not-found 2>/dev/null || true
    fi

    log_success "Loki teardown complete"
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
        echo "  deploy   - Deploy Loki Operator + SeaweedFS + LokiStack (default)"
        echo "  teardown - Remove everything"
        echo "  help     - Show this help"
        echo ""
        echo "Deploys the Loki Operator from OperatorHub with SeaweedFS as"
        echo "S3 storage backend. For dev/test environments only."
        ;;
    *)
        log_error "Unknown command: $1"
        echo "Use '$0 help' for usage information"
        exit 1
        ;;
esac
