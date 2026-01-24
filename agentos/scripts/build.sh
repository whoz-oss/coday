#!/bin/bash
set -e

# AgentOS Build Script
# This script provides convenient commands for building and testing AgentOS

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_DIR"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check if gradlew exists
if [ ! -f "gradlew" ]; then
    print_error "Gradle wrapper not found. Please run 'gradle wrapper' first."
    exit 1
fi

# Make gradlew executable
chmod +x gradlew

# Parse command
COMMAND=${1:-help}

case $COMMAND in
    clean)
        print_info "Cleaning build artifacts..."
        ./gradlew clean
        ;;
    
    build)
        print_info "Building AgentOS (SDK + Service)..."
        ./gradlew build
        print_info "Build complete! JARs are in:"
        print_info "  - agentos-sdk/build/libs/"
        print_info "  - agentos-service/build/libs/"
        ;;
    
    build-sdk)
        print_info "Building SDK only..."
        ./gradlew :agentos-sdk:build
        ;;
    
    build-service)
        print_info "Building Service only..."
        ./gradlew :agentos-service:build
        ;;
    
    test)
        print_info "Running all tests..."
        ./gradlew test
        ;;
    
    test-sdk)
        print_info "Running SDK tests..."
        ./gradlew :agentos-sdk:test
        ;;
    
    test-service)
        print_info "Running Service tests..."
        ./gradlew :agentos-service:test
        ;;
    
    run)
        print_info "Starting AgentOS Service..."
        ./gradlew :agentos-service:bootRun
        ;;
    
    docker-build)
        print_info "Building Docker image..."
        docker build -f agentos-service/Dockerfile -t agentos-service:latest .
        print_info "Docker image built: agentos-service:latest"
        ;;
    
    docker-run)
        print_info "Running AgentOS in Docker..."
        docker run -p 8080:8080 \
            -v "$(pwd)/plugins:/app/plugins:ro" \
            -v "$(pwd)/logs:/app/logs" \
            agentos-service:latest
        ;;
    
    docker-up)
        print_info "Starting AgentOS with Docker Compose..."
        docker-compose up -d
        print_info "Service started. Check logs with: docker-compose logs -f"
        ;;
    
    docker-down)
        print_info "Stopping AgentOS Docker Compose..."
        docker-compose down
        ;;
    
    docker-logs)
        docker-compose logs -f
        ;;
    
    health)
        print_info "Checking service health..."
        curl -s http://localhost:8080/actuator/health | jq '.' || \
            print_error "Service is not running or health check failed"
        ;;
    
    agents)
        print_info "Listing available agents..."
        curl -s http://localhost:8080/api/agents | jq '.' || \
            print_error "Failed to retrieve agents list"
        ;;
    
    nx-build)
        print_info "Building with Nx..."
        npx nx run agentos:build
        ;;
    
    nx-test)
        print_info "Testing with Nx..."
        npx nx run agentos:test
        ;;
    
    init-wrapper)
        print_info "Initializing Gradle wrapper..."
        gradle wrapper --gradle-version 8.5
        chmod +x gradlew
        print_info "Gradle wrapper initialized"
        ;;
    
    help|*)
        echo "AgentOS Build Script"
        echo ""
        echo "Usage: $0 <command>"
        echo ""
        echo "Available commands:"
        echo "  clean          - Clean build artifacts"
        echo "  build          - Build entire project (SDK + Service)"
        echo "  build-sdk      - Build SDK only"
        echo "  build-service  - Build Service only"
        echo "  test           - Run all tests"
        echo "  test-sdk       - Run SDK tests"
        echo "  test-service   - Run Service tests"
        echo "  run            - Run the service locally"
        echo ""
        echo "Docker commands:"
        echo "  docker-build   - Build Docker image"
        echo "  docker-run     - Run service in Docker (foreground)"
        echo "  docker-up      - Start service with Docker Compose"
        echo "  docker-down    - Stop Docker Compose"
        echo "  docker-logs    - View Docker Compose logs"
        echo ""
        echo "Nx commands:"
        echo "  nx-build       - Build with Nx"
        echo "  nx-test        - Test with Nx"
        echo ""
        echo "Utility commands:"
        echo "  health         - Check service health"
        echo "  agents         - List available agents"
        echo "  init-wrapper   - Initialize Gradle wrapper"
        echo "  help           - Show this help message"
        echo ""
        ;;
esac
