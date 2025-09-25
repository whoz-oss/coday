#!/bin/bash

# Simple wrapper Docker avec délai configurable
# Usage: remplace "docker" par "./docker-with-delay.sh" dans ta config MCP

# Délai en secondes (défaut: 15s)
DELAY=${DOCKER_DELAY:-15}

# Path vers le vrai Docker (à ajuster selon ton système)
REAL_DOCKER="/usr/local/bin/docker"

echo "[DOCKER-DELAY] Attente de ${DELAY}s..." >&2
sleep $DELAY

echo "[DOCKER-DELAY] Exécution: $REAL_DOCKER $*" >&2
exec "$REAL_DOCKER" "$@"