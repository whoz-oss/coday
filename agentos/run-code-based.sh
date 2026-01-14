#!/bin/bash
set -e

echo "Building code-based plugin..."
cd code-based-plugin
../gradlew clean jar
cd ..

echo "Verifying plugin structure..."
if command -v jar &> /dev/null; then
    echo "Checking for META-INF/extensions.idx..."
    if jar tf code-based-plugin/build/libs/code-based-plugin-1.0.0.jar | grep -q "META-INF/extensions.idx"; then
        echo "✓ extensions.idx found"
        echo "Extensions registered:"
        unzip -p code-based-plugin/build/libs/code-based-plugin-1.0.0.jar META-INF/extensions.idx
    else
        echo "⚠️  WARNING: META-INF/extensions.idx not found!"
        echo "   PF4J will not be able to discover @Extension classes"
    fi
fi

echo "Copying plugin to plugins directory..."
mkdir -p plugins
cp code-based-plugin/build/libs/code-based-plugin-1.0.0.jar plugins/

echo "Starting AgentOS..."
./gradlew bootRun
