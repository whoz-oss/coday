#!/bin/bash
set -e

echo "Building filesystem plugin..."
cd filesystem-plugin
../gradlew clean jar
cd ..

echo "Verifying plugin structure..."
if command -v jar &> /dev/null; then
    echo "Checking for META-INF/extensions.idx..."
    if jar tf filesystem-plugin/build/libs/filesystem-plugin-1.0.0.jar | grep -q "META-INF/extensions.idx"; then
        echo "✓ extensions.idx found"
        echo "Extensions registered:"
        unzip -p filesystem-plugin/build/libs/filesystem-plugin-1.0.0.jar META-INF/extensions.idx
    else
        echo "⚠️  WARNING: META-INF/extensions.idx not found!"
        echo "   PF4J will not be able to discover @Extension classes"
    fi
fi

echo "Copying plugin to plugins directory..."
mkdir -p plugins
cp filesystem-plugin/build/libs/filesystem-plugin-1.0.0.jar plugins/

echo "Creating agents directory..."
mkdir -p agents

echo "Checking for YAML agent files..."
YAML_COUNT=$(find agents -name "*.yaml" -o -name "*.yml" 2>/dev/null | wc -l)
if [ "$YAML_COUNT" -gt 0 ]; then
    echo "✓ Found $YAML_COUNT YAML agent file(s):"
    find agents -name "*.yaml" -o -name "*.yml" 2>/dev/null | sed 's/^/    /'
else
    echo "⚠️  No YAML agent files found in agents/ directory"
    echo "   Example files: howzi.yaml, security-scanner.yaml, api-architect.yaml"
    echo "   Plugin will load but provide 0 agents"
fi
echo ""

echo "Starting AgentOS..."
./gradlew bootRun
