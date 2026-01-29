#!/bin/bash

# Simple script to run Ollama in Podman for local development

set -e

# Configuration
OLLAMA_IMAGE="mirror.gcr.io/ollama/ollama:latest"
CONTAINER_NAME="strimzi-mcp-ollama"
OLLAMA_PORT="11434"
DEFAULT_MODEL="llama3.2:3b"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${BLUE}ℹ️  $1${NC}"
}

log_success() {
    echo -e "${GREEN}✅ $1${NC}"
}

log_warning() {
    echo -e "${YELLOW}⚠️  $1${NC}"
}

log_error() {
    echo -e "${RED}❌ $1${NC}"
}

check_podman() {
    if ! command -v podman &> /dev/null; then
        log_error "Podman is not installed. Please install Podman first."
        echo "  macOS: brew install podman"
        echo "  Linux: Follow instructions at https://podman.io/getting-started/installation"
        exit 1
    fi
    log_success "Podman is available"
}

stop_existing_container() {
    if podman ps -q -f name="$CONTAINER_NAME" | grep -q .; then
        log_info "Stopping existing Ollama container..."
        podman stop "$CONTAINER_NAME" || true
    fi

    if podman ps -a -q -f name="$CONTAINER_NAME" | grep -q .; then
        log_info "Removing existing Ollama container..."
        podman rm "$CONTAINER_NAME" || true
    fi
}

start_ollama() {
    log_info "Starting Ollama container in Podman..."

    # Create volume for Ollama data persistence
    podman volume create ollama-data || true

    # Start Ollama container
    podman run -d \
        --name "$CONTAINER_NAME" \
        --restart unless-stopped \
        -p "${OLLAMA_PORT}:11434" \
        -v ollama-data:/root/.ollama \
        -e OLLAMA_ORIGINS="*" \
        "$OLLAMA_IMAGE"

    log_success "Ollama container started"
    log_info "Waiting for Ollama to be ready..."

    # Wait for Ollama to be ready
    for i in {1..30}; do
        if curl -s http://localhost:${OLLAMA_PORT}/api/tags >/dev/null 2>&1; then
            log_success "Ollama is ready!"
            break
        fi
        echo -n "."
        sleep 2
        if [ $i -eq 30 ]; then
            log_error "Ollama failed to start after 60 seconds"
            exit 1
        fi
    done
    echo ""
}

pull_model() {
    local model="${1:-$DEFAULT_MODEL}"
    log_info "Pulling model: $model"
    log_warning "This may take several minutes for the first time..."

    podman exec "$CONTAINER_NAME" ollama pull "$model"
    log_success "Model $model is ready"
}

show_status() {
    echo ""
    echo "🚀 Ollama Container Started!"
    echo "============================"
    echo "📡 Ollama API:     http://localhost:${OLLAMA_PORT}"
    echo "🐳 Container:      $CONTAINER_NAME"
    echo "🤖 Model:          $DEFAULT_MODEL"
    echo ""
    echo "Environment variables:"
    echo "export LLM_PROVIDER=ollama"
    echo "export OLLAMA_BASE_URL=http://localhost:${OLLAMA_PORT}"
    echo "export OLLAMA_MODEL=$DEFAULT_MODEL"
    echo ""
    echo "Now run your application:"
    echo "mvn quarkus:dev"
}

list_models() {
    log_info "Available models in Ollama:"
    podman exec "$CONTAINER_NAME" ollama list || log_warning "No models installed yet"
}

# Main execution
case "${1:-start}" in
    "start")
        log_info "Setting up local Ollama LLM for Strimzi MCP testing..."
        check_podman
        stop_existing_container
        start_ollama
        pull_model "${2:-$DEFAULT_MODEL}"
        show_status
        ;;
    "stop")
        log_info "Stopping Ollama container..."
        podman stop "$CONTAINER_NAME" 2>/dev/null || log_warning "Container not running"
        log_success "Ollama stopped"
        ;;
    "restart")
        $0 stop
        sleep 2
        $0 start "${2:-$DEFAULT_MODEL}"
        ;;
    "status")
        if podman ps -f name="$CONTAINER_NAME" --format "table {{.Names}}\t{{.Status}}" | grep -q "$CONTAINER_NAME"; then
            log_success "Ollama is running"
            list_models
            echo ""
            echo "API endpoint: http://localhost:${OLLAMA_PORT}"
        else
            log_warning "Ollama container is not running"
            echo "Run: $0 start"
        fi
        ;;
    "logs")
        log_info "Showing Ollama container logs..."
        podman logs -f "$CONTAINER_NAME"
        ;;
    "pull")
        if [ -z "$2" ]; then
            log_error "Model name required. Usage: $0 pull <model-name>"
            echo "Examples:"
            echo "  $0 pull llama3.2:3b"
            echo "  $0 pull llama3.2:1b"
            echo "  $0 pull codellama:7b"
            exit 1
        fi
        pull_model "$2"
        ;;
    "models")
        list_models
        ;;
    "shell")
        log_info "Opening shell in Ollama container..."
        podman exec -it "$CONTAINER_NAME" /bin/bash
        ;;
    "help"|"-h"|"--help")
        echo "Simple Ollama Container Manager"
        echo "Usage: $0 [command] [options]"
        echo ""
        echo "Commands:"
        echo "  start [model]  - Start Ollama container and pull model (default: llama3.2:3b)"
        echo "  stop          - Stop Ollama container"
        echo "  restart [model] - Restart Ollama container"
        echo "  status        - Check Ollama container status"
        echo "  logs          - Show container logs"
        echo "  pull <model>  - Pull a specific model"
        echo "  models        - List available models"
        echo "  shell         - Open shell in container"
        echo "  help          - Show this help"
        echo ""
        echo "Examples:"
        echo "  $0 start                    # Start with default model (llama3.2:3b)"
        echo "  $0 pull llama3.2:1b        # Pull different model"
        echo "  $0 status                   # Check if running"
        echo ""
        echo "After starting, run your application with:"
        echo "  mvn quarkus:dev"
        ;;
    *)
        log_error "Unknown command: $1"
        echo "Use '$0 help' for usage information"
        exit 1
        ;;
esac