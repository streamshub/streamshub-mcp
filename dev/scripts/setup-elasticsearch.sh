#!/bin/bash

# Deploy or tear down Elasticsearch via the ECK Operator on OpenShift.
# Uses Cluster Logging Operator for log forwarding from Strimzi namespaces.
#
# Usage:
#   ./setup-elasticsearch.sh deploy   - Deploy ECK + Elasticsearch + log forwarding
#   ./setup-elasticsearch.sh teardown - Remove everything

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MANIFESTS_DIR="$SCRIPT_DIR/../manifests/elasticsearch"

ES_NS="elasticsearch-logging"
OPERATOR_NS="openshift-operators"

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
    return 1
}

deploy() {
    check_prerequisites

    # Ensure namespace
    kubectl apply -f "$MANIFESTS_DIR/namespace.yaml"

    # Phase 1: Install ECK Operator
    local existing_csv
    existing_csv=$(kubectl get csv -n "$OPERATOR_NS" -o name 2>/dev/null | grep elastic-cloud-eck || echo "")

    if [ -n "$existing_csv" ]; then
        log_success "ECK Operator already installed ($existing_csv)"
    else
        log_info "Phase 1: Installing ECK Operator..."
        kubectl apply -f "$MANIFESTS_DIR/eck-operator.yaml"
    fi

    # Phase 2: Wait for Elasticsearch CRD
    wait_for_crd "elasticsearches.elasticsearch.k8s.elastic.co" 300

    # Phase 3: Create Elasticsearch instance
    log_info "Phase 3: Creating Elasticsearch instance..."
    kubectl apply -f "$MANIFESTS_DIR/elasticsearch-cr.yaml"

    log_info "Waiting for Elasticsearch to be ready (this may take a few minutes)..."
    local retries=60
    while [ $retries -gt 0 ]; do
        local health
        health=$(kubectl get elasticsearch elasticsearch -n "$ES_NS" \
            -o jsonpath='{.status.health}' 2>/dev/null || echo "")
        if [ "$health" = "green" ] || [ "$health" = "yellow" ]; then
            break
        fi
        retries=$((retries - 1))
        sleep 10
    done

    if [ $retries -eq 0 ]; then
        log_warning "Elasticsearch not yet healthy. Check status:"
        log_warning "  kubectl get elasticsearch elasticsearch -n $ES_NS"
    else
        log_success "Elasticsearch is healthy ($health)"
    fi

    # Phase 4: Create Elasticsearch API keys (write for log collection, read for MCP server)
    log_info "Phase 4: Creating Elasticsearch API keys..."
    local es_password
    es_password=$(kubectl get secret elasticsearch-es-elastic-user -n "$ES_NS" \
        -o jsonpath='{.data.elastic}' 2>/dev/null | base64 -d)

    if [ -n "$es_password" ]; then
        # Create write API key for ClusterLogForwarder
        local write_key_response
        write_key_response=$(kubectl exec -n "$ES_NS" elasticsearch-es-default-0 -- \
            curl -sk -u "elastic:${es_password}" -X POST \
            "https://localhost:9200/_security/api_key" \
            -H 'Content-Type: application/json' \
            -d '{
              "name": "logcollector-write-key",
              "role_descriptors": {
                "logcollector": {
                  "cluster": ["monitor"],
                  "indices": [
                    {
                      "names": ["kubernetes*"],
                      "privileges": ["create_index", "write", "create", "auto_configure"]
                    }
                  ]
                }
              }
            }' 2>/dev/null)

        local write_token
        write_token=$(echo "$write_key_response" | grep -o '"encoded":"[^"]*"' | cut -d'"' -f4)

        if [ -n "$write_token" ]; then
            kubectl create secret generic elasticsearch-logforwarder-token -n "$ES_NS" \
                --from-literal=token="$write_token" \
                --dry-run=client -o yaml | kubectl apply -f -
            log_success "Write API key created for ClusterLogForwarder"
        fi

        # Create read API key for MCP server
        local read_key_response
        read_key_response=$(kubectl exec -n "$ES_NS" elasticsearch-es-default-0 -- \
            curl -sk -u "elastic:${es_password}" -X POST \
            "https://localhost:9200/_security/api_key" \
            -H 'Content-Type: application/json' \
            -d '{
              "name": "mcp-server-read-key",
              "role_descriptors": {
                "mcp-reader": {
                  "cluster": ["monitor"],
                  "indices": [
                    {
                      "names": ["kubernetes*"],
                      "privileges": ["read", "view_index_metadata"]
                    }
                  ]
                }
              }
            }' 2>/dev/null)

        local read_token
        read_token=$(echo "$read_key_response" | grep -o '"encoded":"[^"]*"' | cut -d'"' -f4)

        if [ -n "$read_token" ]; then
            kubectl create secret generic elasticsearch-mcp-read-token -n "$ES_NS" \
                --from-literal=token="$read_token" \
                --dry-run=client -o yaml | kubectl apply -f -
            log_success "Read API key created for MCP server"
        fi

        if [ -z "$write_token" ] || [ -z "$read_token" ]; then
            log_warning "Could not create API keys. Write response: $write_key_response, Read response: $read_key_response"
        fi
    else
        log_warning "Could not retrieve ECK password, skipping API key creation"
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

        kubectl apply -f "$MANIFESTS_DIR/cluster-logging.yaml" 2>/dev/null || true
        wait_for_crd "clusterlogforwarders.observability.openshift.io" 300
        kubectl apply -f "$MANIFESTS_DIR/cluster-logging.yaml"
        log_success "ClusterLogForwarder created"

        log_info "Waiting for log collector pods..."
        local collector_retries=30
        while [ $collector_retries -gt 0 ]; do
            local collector_count
            collector_count=$(kubectl get pods -n "$ES_NS" -l app.kubernetes.io/component=collector --no-headers 2>/dev/null | wc -l | tr -d ' ')
            if [ "$collector_count" -gt 0 ]; then
                log_success "Log collector pods running ($collector_count)"
                break
            fi
            collector_retries=$((collector_retries - 1))
            sleep 10
        done
    else
        log_warning "cluster-logging operator not found. Skipping log forwarding."
    fi

    # Phase 6: Create Route
    log_info "Phase 6: Creating Route..."
    kubectl apply -f "$MANIFESTS_DIR/route.yaml"

    local route_host
    route_host=$(kubectl get route elasticsearch -n "$ES_NS" \
        -o jsonpath='{.spec.host}' 2>/dev/null || echo "")

    echo ""
    log_success "Elasticsearch deployment complete"
    echo ""
    echo "Namespace:       $ES_NS"
    echo "In-cluster URL:  https://elasticsearch-es-http.$ES_NS.svc:9200"
    if [ -n "$route_host" ]; then
        echo "Route (external): https://$route_host"
    fi
    echo ""
    echo "MCP server configuration:"
    echo "  MCP_LOG_PROVIDER=streamshub-elasticsearch"
    echo "  QUARKUS_REST_CLIENT_ELASTICSEARCH_URL=https://elasticsearch-es-http.$ES_NS.svc:9200"
    echo "  MCP_LOG_ELASTICSEARCH_AUTH_MODE=bearer-token"
    echo "  MCP_LOG_ELASTICSEARCH_BEARER_TOKEN=\$(oc get secret elasticsearch-mcp-read-token -n $ES_NS -o jsonpath='{.data.token}' | base64 -d)"
    echo "  MCP_LOG_ELASTICSEARCH_INDEX_PATTERN=kubernetes"
    echo "  QUARKUS_TLS_TRUST_ALL=true"
    echo ""
    echo "Note: ClusterLogForwarder writes to 'kubernetes' index (no date suffix)."
    echo "For Kind clusters with Fluent Bit, use 'kubernetes-*' pattern instead."
    echo ""
    echo "Or use basic auth with elastic superuser (not recommended for production):"
    echo "  MCP_LOG_ELASTICSEARCH_AUTH_MODE=basic"
    echo "  QUARKUS_REST_CLIENT_ELASTICSEARCH_USERNAME=elastic"
    echo "  QUARKUS_REST_CLIENT_ELASTICSEARCH_PASSWORD=\$(oc get secret elasticsearch-es-elastic-user -n $ES_NS -o jsonpath='{.data.elastic}' | base64 -d)"
}

teardown() {
    check_prerequisites

    log_info "Removing Route..."
    kubectl delete -f "$MANIFESTS_DIR/route.yaml" --ignore-not-found 2>/dev/null || true

    log_info "Removing Cluster Logging..."
    kubectl delete -f "$MANIFESTS_DIR/cluster-logging.yaml" --ignore-not-found 2>/dev/null || true

    local logging_csv
    logging_csv=$(kubectl get csv -n "$ES_NS" -o name 2>/dev/null | grep cluster-logging || echo "")
    if [ -n "$logging_csv" ]; then
        kubectl delete "$logging_csv" -n "$ES_NS" --ignore-not-found 2>/dev/null || true
    fi

    log_info "Removing Elasticsearch..."
    kubectl delete -f "$MANIFESTS_DIR/elasticsearch-cr.yaml" --ignore-not-found 2>/dev/null || true

    log_info "Removing ECK Operator..."
    kubectl delete -f "$MANIFESTS_DIR/eck-operator.yaml" --ignore-not-found 2>/dev/null || true

    local eck_csv
    eck_csv=$(kubectl get csv -n "$OPERATOR_NS" -o name 2>/dev/null | grep elastic-cloud-eck || echo "")
    if [ -n "$eck_csv" ]; then
        kubectl delete "$eck_csv" -n "$OPERATOR_NS" --ignore-not-found 2>/dev/null || true
    fi

    log_success "Elasticsearch teardown complete"
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
        echo "  deploy   - Deploy ECK Operator + Elasticsearch + log forwarding (default)"
        echo "  teardown - Remove everything"
        echo "  help     - Show this help"
        echo ""
        echo "Deploys Elasticsearch via ECK Operator from OperatorHub with"
        echo "Cluster Logging Operator for log forwarding. For dev/test only."
        ;;
    *)
        log_error "Unknown command: $1"
        echo "Use '$0 help' for usage information"
        exit 1
        ;;
esac
