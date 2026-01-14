#!/bin/bash

# Test script to verify both run configurations work correctly
# This script tests both profiles without actually starting the server

set -e

echo "🧪 Testing AgentOS Run Configurations"
echo "======================================"
echo ""

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Test 1: Check if plugins can be built
echo "${BLUE}Test 1: Building plugins...${NC}"
echo ""

echo "  Building code-based plugin..."
cd code-based-plugin
../gradlew jar --quiet
if [ $? -eq 0 ]; then
    echo "${GREEN}  ✓ Code-based plugin built successfully${NC}"
else
    echo "  ✗ Failed to build code-based plugin"
    exit 1
fi
cd ..

echo "  Building filesystem plugin..."
cd filesystem-plugin
../gradlew jar --quiet
if [ $? -eq 0 ]; then
    echo "${GREEN}  ✓ Filesystem plugin built successfully${NC}"
else
    echo "  ✗ Failed to build filesystem plugin"
    exit 1
fi
cd ..

echo ""

# Test 2: Check if plugin JARs exist
echo "${BLUE}Test 2: Checking plugin JARs...${NC}"
echo ""

if [ -f "code-based-plugin/build/libs/code-based-plugin-1.0.0.jar" ]; then
    SIZE=$(du -h code-based-plugin/build/libs/code-based-plugin-1.0.0.jar | cut -f1)
    echo "${GREEN}  ✓ Code-based plugin JAR exists ($SIZE)${NC}"
else
    echo "  ✗ Code-based plugin JAR not found"
    exit 1
fi

if [ -f "filesystem-plugin/build/libs/filesystem-plugin-1.0.0.jar" ]; then
    SIZE=$(du -h filesystem-plugin/build/libs/filesystem-plugin-1.0.0.jar | cut -f1)
    echo "${GREEN}  ✓ Filesystem plugin JAR exists ($SIZE)${NC}"
else
    echo "  ✗ Filesystem plugin JAR not found"
    exit 1
fi

echo ""

# Test 3: Check if directories can be created
echo "${BLUE}Test 3: Checking directory setup...${NC}"
echo ""

mkdir -p plugins-code-based
if [ -d "plugins-code-based" ]; then
    echo "${GREEN}  ✓ plugins-code-based/ directory ready${NC}"
fi

mkdir -p plugins-filesystem
if [ -d "plugins-filesystem" ]; then
    echo "${GREEN}  ✓ plugins-filesystem/ directory ready${NC}"
fi

mkdir -p agents
if [ -d "agents" ]; then
    echo "${GREEN}  ✓ agents/ directory ready${NC}"
fi

echo ""

# Test 4: Check if plugins can be copied
echo "${BLUE}Test 4: Testing plugin deployment...${NC}"
echo ""

cp code-based-plugin/build/libs/code-based-plugin-1.0.0.jar plugins-code-based/
if [ -f "plugins-code-based/code-based-plugin-1.0.0.jar" ]; then
    echo "${GREEN}  ✓ Code-based plugin deployed to plugins-code-based/${NC}"
fi

cp filesystem-plugin/build/libs/filesystem-plugin-1.0.0.jar plugins-filesystem/
if [ -f "plugins-filesystem/filesystem-plugin-1.0.0.jar" ]; then
    echo "${GREEN}  ✓ Filesystem plugin deployed to plugins-filesystem/${NC}"
fi

echo ""

# Test 5: Check if configuration files exist
echo "${BLUE}Test 5: Checking configuration files...${NC}"
echo ""

if [ -f "src/main/resources/application-code-based.yml" ]; then
    echo "${GREEN}  ✓ application-code-based.yml exists${NC}"
else
    echo "  ✗ application-code-based.yml not found"
    exit 1
fi

if [ -f "src/main/resources/application-filesystem.yml" ]; then
    echo "${GREEN}  ✓ application-filesystem.yml exists${NC}"
else
    echo "  ✗ application-filesystem.yml not found"
    exit 1
fi

echo ""

# Test 6: Check if run scripts exist and are executable
echo "${BLUE}Test 6: Checking run scripts...${NC}"
echo ""

if [ -f "run-code-based.sh" ]; then
    chmod +x run-code-based.sh
    echo "${GREEN}  ✓ run-code-based.sh exists and is executable${NC}"
else
    echo "  ✗ run-code-based.sh not found"
    exit 1
fi

if [ -f "run-filesystem.sh" ]; then
    chmod +x run-filesystem.sh
    echo "${GREEN}  ✓ run-filesystem.sh exists and is executable${NC}"
else
    echo "  ✗ run-filesystem.sh not found"
    exit 1
fi

echo ""

# Test 7: Check if agent YAML files exist
echo "${BLUE}Test 7: Checking agent YAML files...${NC}"
echo ""

YAML_COUNT=$(ls -1 agents/*.yaml 2>/dev/null | wc -l)
if [ $YAML_COUNT -gt 0 ]; then
    echo "${GREEN}  ✓ Found $YAML_COUNT agent YAML file(s):${NC}"
    ls -1 agents/*.yaml | while read file; do
        echo "    - $(basename $file)"
    done
else
    echo "${YELLOW}  ⚠ No agent YAML files found in agents/ directory${NC}"
    echo "    This is OK for code-based profile, but filesystem profile needs YAML files"
fi

echo ""

# Test 8: Check Gradle tasks
echo "${BLUE}Test 8: Checking Gradle tasks...${NC}"
echo ""

if ./gradlew tasks --quiet | grep -q "bootRunCodeBased"; then
    echo "${GREEN}  ✓ bootRunCodeBased task is available${NC}"
else
    echo "  ✗ bootRunCodeBased task not found"
    exit 1
fi

if ./gradlew tasks --quiet | grep -q "bootRunFilesystem"; then
    echo "${GREEN}  ✓ bootRunFilesystem task is available${NC}"
else
    echo "  ✗ bootRunFilesystem task not found"
    exit 1
fi

echo ""

# Summary
echo "======================================"
echo "${GREEN}✅ All tests passed!${NC}"
echo ""
echo "You can now run AgentOS with:"
echo ""
echo "  ${BLUE}Code-Based Profile:${NC}"
echo "    ./run-code-based.sh"
echo "    or"
echo "    ./gradlew bootRunCodeBased"
echo ""
echo "  ${BLUE}Filesystem Profile:${NC}"
echo "    ./run-filesystem.sh"
echo "    or"
echo "    ./gradlew bootRunFilesystem"
echo ""
echo "After starting, test with:"
echo "  curl http://localhost:8080/api/plugins | jq"
echo "  curl http://localhost:8080/api/agents | jq"
echo ""
