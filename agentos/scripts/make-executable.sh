#!/bin/bash
# Make all scripts executable

cd "$(dirname "$0")"

echo "🔧 Making scripts executable..."

chmod +x build-all.sh
chmod +x run-service.sh
chmod +x publish-sdk.sh
chmod +x make-executable.sh

echo "✅ All scripts are now executable!"
echo ""
echo "Available scripts:"
echo "  - build-all.sh     : Build all modules"
echo "  - run-service.sh   : Run the AgentOS service"
echo "  - publish-sdk.sh   : Publish SDK to GitHub Packages"
