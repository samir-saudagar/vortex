#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
limit="${1:-20}"
if (( $# > 1 )) || [[ ! "$limit" =~ ^[1-9][0-9]*$ ]]; then
  printf 'Usage: %s [positive-message-limit]\n' "$0" >&2
  exit 2
fi

# This diagnostic consumer commits no offsets and changes no topic or application data.
# The console consumer exits nonzero on its five-second idle timeout, including an empty topic.
exec docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka:29092 --topic vortex.quarantine --from-beginning \
  --consumer-property enable.auto.commit=false --max-messages "$limit" --timeout-ms 5000 \
  --property print.key=true --property print.partition=true --property print.offset=true
