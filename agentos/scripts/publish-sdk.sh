#!/bin/bash
set -e

echo "📦 Publishing AgentOS SDK to GitHub Packages..."
echo ""

# Check for required environment variables
if [ -z "$GITHUB_ACTOR" ] && [ -z "$GPR_USER" ]; then
    echo "❌ Error: GITHUB_ACTOR or GPR_USER environment variable not set"
    echo "   Set one of these variables with your GitHub username"
    exit 1
fi

if [ -z "$GITHUB_TOKEN" ] && [ -z "$GPR_KEY" ]; then
    echo "❌ Error: GITHUB_TOKEN or GPR_KEY environment variable not set"
    echo "   Create a token at: https://github.com/settings/tokens"
    echo "   Required scopes: write:packages, read:packages"
    exit 1
fi

cd "$(dirname "$0")/.."

echo "🏗️  Building SDK..."
if command -v nx &> /dev/null; then
    nx build agentos-sdk
else
    ./gradlew :agentos-sdk:build
fi

echo ""
echo "📤 Publishing..."
if command -v nx &> /dev/null; then
    nx publish agentos-sdk
else
    ./gradlew :agentos-sdk:publish
fi

echo ""
echo "✅ SDK published successfully!"
echo ""
echo "📋 To use in your plugin:"
echo ""
echo "repositories {"
echo "    maven {"
echo "        url = uri(\"https://maven.pkg.github.com/whoz-oss/coday\")"
echo "        credentials {"
echo "            username = System.getenv(\"GITHUB_ACTOR\")"
echo "            password = System.getenv(\"GITHUB_TOKEN\")"
echo "        }"
echo "    }"
echo "}"
echo ""
echo "dependencies {"
echo "    compileOnly(\"io.biznet.agentos:agentos-sdk:1.0.0\")"
echo "}"
