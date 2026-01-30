#!/bin/bash
# Gradle Self-Healing Script
# Automatically cleans and retries on Gradle cache corruption

# Note: We don't use 'set -e' here because we want to handle errors explicitly
# and implement retry logic

PROJECT_NAME="${1:-agentos-sdk}"
GRADLE_TASK="${2:-test}"
MAX_RETRIES="${MAX_RETRIES:-2}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AGENTOS_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

log_info() {
    echo "ℹ️  [INFO] $1"
}

log_warning() {
    echo "⚠️  [WARN] $1"
}

log_error() {
    echo "❌ [ERROR] $1"
}

log_success() {
    echo "✅ [SUCCESS] $1"
}

clean_gradle_cache() {
    log_warning "Cleaning Gradle cache and build directories..."
    
    if ! cd "$AGENTOS_DIR"; then
        log_error "Failed to change directory to $AGENTOS_DIR"
        return 1
    fi
    
    # Clean specific project
    ./gradlew ":${PROJECT_NAME}:clean" --no-daemon --no-build-cache 2>/dev/null || true
    
    # Remove build directories
    rm -rf "${PROJECT_NAME}/build" 2>/dev/null || true
    rm -rf ".gradle" 2>/dev/null || true
    
    # Clear Gradle daemon
    ./gradlew --stop 2>/dev/null || true
    
    log_success "Cache cleaned successfully"
    return 0
}

run_gradle_task() {
    local attempt=$1
    log_info "Attempt ${attempt}/${MAX_RETRIES}: Running ./gradlew :${PROJECT_NAME}:${GRADLE_TASK}"
    
    if ! cd "$AGENTOS_DIR"; then
        log_error "Failed to change directory to $AGENTOS_DIR"
        return 1
    fi
    
    if ./gradlew ":${PROJECT_NAME}:${GRADLE_TASK}" --no-daemon; then
        log_success "Task completed successfully on attempt ${attempt}"
        return 0
    else
        local exit_code=$?
        log_error "Task failed on attempt ${attempt} with exit code ${exit_code}"
        return ${exit_code}
    fi
}

# Main execution
main() {
    log_info "Starting Gradle task with self-healing for project: ${PROJECT_NAME}, task: ${GRADLE_TASK}"
    log_info "Working directory: ${AGENTOS_DIR}"
    
    # Verify gradlew exists
    if [ ! -f "${AGENTOS_DIR}/gradlew" ]; then
        log_error "gradlew not found in ${AGENTOS_DIR}"
        exit 1
    fi
    
    local attempt=1
    
    while [ "$attempt" -le "$MAX_RETRIES" ]; do
        if run_gradle_task "$attempt"; then
            exit 0
        fi
        
        # If not the last attempt, clean and retry
        if [ "$attempt" -lt "$MAX_RETRIES" ]; then
            log_warning "Self-healing triggered after failure on attempt ${attempt}"
            if ! clean_gradle_cache; then
                log_error "Failed to clean cache, aborting"
                exit 1
            fi
            log_info "Retrying..."
            attempt=$((attempt + 1))
        else
            log_error "All ${MAX_RETRIES} attempts failed. Giving up."
            exit 1
        fi
    done
}

main
