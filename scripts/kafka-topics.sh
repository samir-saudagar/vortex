#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

create_topic() {
  local topic="$1"
  local partitions="$2"
  local message_bytes="$3"
  docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server kafka:29092 \
    --create --if-not-exists \
    --topic "$topic" \
    --partitions "$partitions" \
    --replication-factor 1
  docker compose exec -T kafka /opt/kafka/bin/kafka-configs.sh \
    --bootstrap-server kafka:29092 \
    --entity-type topics --entity-name "$topic" \
    --alter --add-config "retention.ms=604800000,max.message.bytes=${message_bytes}"
}

create_topic vortex.workflow-events 3 2097152
create_topic vortex.equipment-commands 3 2097152
create_topic vortex.simulator-reports 3 2097152
# Allow full malformed keys as well as Base64 payloads; never truncate diagnostics.
create_topic vortex.quarantine 1 33554432

for topic in vortex.workflow-events vortex.equipment-commands vortex.simulator-reports vortex.quarantine; do
  description="$(docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server kafka:29092 --describe --topic "$topic")"
  if [[ "$topic" == "vortex.quarantine" ]]; then
    expected_partitions=1
    expected_message_bytes=33554432
  else
    expected_partitions=3
    expected_message_bytes=2097152
  fi
  if ! grep -Eq "PartitionCount: ${expected_partitions}([[:space:]]|$)" <<<"$description"; then
    printf 'Topic %s has a partition count that differs from the approved policy.\n%s\n' "$topic" "$description" >&2
    exit 1
  fi

  config="$(docker compose exec -T kafka /opt/kafka/bin/kafka-configs.sh \
    --bootstrap-server kafka:29092 \
    --entity-type topics --entity-name "$topic" --describe)"
  if ! grep -Eq 'retention\.ms=604800000([[:space:],}]|$)' <<<"$config"; then
    printf 'Topic %s does not report the approved explicit retention.\n%s\n' "$topic" "$config" >&2
    exit 1
  fi
  if ! grep -Eq "max\\.message\\.bytes=${expected_message_bytes}([[:space:],}]|$)" <<<"$config"; then
    printf 'Topic %s does not report the required transport capacity.\n%s\n' "$topic" "$config" >&2
    exit 1
  fi
  printf '%s\n' "$description" "$config"
done
