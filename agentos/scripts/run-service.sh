#!/bin/bash
set -e

echo "🚀 Starting AgentOS Service..."
echo ""

cd "$(dirname "$0")/.."

# Check if Nx is available
if command -v nx &> /dev/null; then
    echo "Using Nx..."
    nx bootRun agentos-service
else
    echo "Using Gradle directly..."
    ./gradlew :agentos-service:bootRun
fi
