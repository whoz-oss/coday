#!/usr/bin/env bash
# Verifies that the committed OpenAPI spec is up-to-date with the current source code.
# Regenerates the spec and fails with a clear message if it differs from what is committed.
# Run manually: ./check-openapi-spec.sh (from factory-service/ directory)
set -euo pipefail

cd "$(dirname "$0")"

echo "Regenerating Factory Service OpenAPI spec..."
./gradlew generateOpenApiDocs --no-configuration-cache -q

echo "Checking for diff..."
if ! git diff --exit-code openapi/factory-openapi.yaml; then
  echo ""
  echo "❌ Factory Service OpenAPI spec is out of date."
  echo "   Please run: nx run factory-service:generate-openapi-spec"
  echo "   Then commit the updated factory-service/openapi/factory-openapi.yaml"
  exit 1
fi

echo "✅ Factory Service OpenAPI spec is up to date."
