#!/usr/bin/env bash
# Verifies that the committed OpenAPI spec is up-to-date with the current source code.
# Regenerates the spec and fails with a clear message if it differs from what is committed.
# Run manually: ./check-openapi-spec.sh (from agentos/ directory)
set -euo pipefail

cd "$(dirname "$0")/.."  # move to agentos/

echo "Regenerating OpenAPI spec..."
./gradlew :agentos-service:generateOpenApiDocs --no-configuration-cache -q

echo "Checking for diff..."
if ! git diff --exit-code openapi/agentos-openapi.yaml; then
  echo ""
  echo "❌ OpenAPI spec is out of date."
  echo "   Please run: nx run agentos-service:generate-openapi-spec"
  echo "   Then commit the updated agentos/openapi/agentos-openapi.yaml"
  exit 1
fi

echo "✅ OpenAPI spec is up to date."
