#!/bin/bash
set -e

echo "🏗️  Building AgentOS with Nx..."
echo ""

# Check if Nx is installed
if ! command -v nx &> /dev/null; then
    echo "⚠️  Nx not found. Installing globally..."
    npm install -g nx@latest
fi

cd "$(dirname "$0")/.."

echo "📦 Building SDK..."
nx build agentos-sdk

echo ""
echo "🚀 Building Service..."
nx build agentos-service

echo ""
echo "✅ Build complete!"
echo ""
echo "📍 Artifacts:"
echo "   - SDK: agentos-sdk/build/libs/"
echo "   - Service: agentos-service/build/libs/"
