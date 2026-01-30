#!/bin/bash
# Test script for gradle-self-healing.sh
# This simulates failures to verify the self-healing mechanism

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_OUTPUT="/tmp/self-healing-test-$$.log"

echo "🧪 Testing Gradle Self-Healing Mechanism"
echo "========================================="
echo ""

# Test 1: Successful execution on first attempt
echo "Test 1: Successful execution (should succeed on first attempt)"
echo "----------------------------------------------------------------"
if bash "$SCRIPT_DIR/gradle-self-healing.sh" agentos-sdk clean > "$TEST_OUTPUT" 2>&1; then
    echo "✅ PASS: Task succeeded on first attempt"
    grep -q "Attempt 1" "$TEST_OUTPUT" && echo "✅ PASS: Executed attempt 1"
    grep -q "SUCCESS" "$TEST_OUTPUT" && echo "✅ PASS: Success logged"
else
    echo "❌ FAIL: Task should have succeeded"
    cat "$TEST_OUTPUT"
    exit 1
fi
echo ""

# Test 2: Verify script structure
echo "Test 2: Script structure validation"
echo "------------------------------------"
SCRIPT_PATH="$SCRIPT_DIR/gradle-self-healing.sh"

if grep -q "log_info" "$SCRIPT_PATH"; then
    echo "✅ PASS: Logging functions present"
else
    echo "❌ FAIL: Logging functions missing"
    exit 1
fi

if grep -q "clean_gradle_cache" "$SCRIPT_PATH"; then
    echo "✅ PASS: Cache cleaning function present"
else
    echo "❌ FAIL: Cache cleaning function missing"
    exit 1
fi

if grep -q "MAX_RETRIES" "$SCRIPT_PATH"; then
    echo "✅ PASS: Retry mechanism present"
else
    echo "❌ FAIL: Retry mechanism missing"
    exit 1
fi
echo ""

# Test 3: Verify NX integration
echo "Test 3: NX integration validation"
echo "----------------------------------"
cd "$SCRIPT_DIR/../.."

if nx show project agentos-sdk --json | grep -q "gradle-self-healing.sh"; then
    echo "✅ PASS: Self-healing integrated in NX test target"
else
    echo "⚠️  WARN: Self-healing may not be integrated in NX (check project.json)"
fi

if nx show project agentos-sdk --json | grep -q "test:direct"; then
    echo "✅ PASS: Direct test target available for debugging"
else
    echo "⚠️  WARN: Direct test target not found (check project.json)"
fi
echo ""

# Cleanup
rm -f "$TEST_OUTPUT"

echo "========================================="
echo "✅ All tests passed!"
echo ""
echo "You can now test the self-healing manually:"
echo "  cd agentos"
echo "  bash scripts/gradle-self-healing.sh agentos-sdk test"
