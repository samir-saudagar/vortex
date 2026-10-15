#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

docker compose up -d --wait postgres kafka
"$ROOT_DIR/scripts/kafka-topics.sh"
docker compose ps
