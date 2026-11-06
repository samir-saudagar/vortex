#!/usr/bin/env bash
# Same four-topic policy as scripts/kafka-topics.sh, executed inside the full stack.
set -Eeuo pipefail
bootstrap=kafka:29092
for topic in vortex.workflow-events vortex.equipment-commands vortex.simulator-reports vortex.quarantine; do
  partitions=3
  bytes=2097152
  if [[ "$topic" == vortex.quarantine ]]; then partitions=1; bytes=33554432; fi
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$bootstrap" --create --if-not-exists \
    --topic "$topic" --partitions "$partitions" --replication-factor 1 \
    --config retention.ms=604800000 --config "max.message.bytes=$bytes"
  /opt/kafka/bin/kafka-configs.sh --bootstrap-server "$bootstrap" --entity-type topics \
    --entity-name "$topic" --alter --add-config "retention.ms=604800000,max.message.bytes=$bytes"
  description="$(/opt/kafka/bin/kafka-topics.sh --bootstrap-server "$bootstrap" --describe --topic "$topic")"
  config="$(/opt/kafka/bin/kafka-configs.sh --bootstrap-server "$bootstrap" --entity-type topics --entity-name "$topic" --describe)"
  [[ "$description" =~ PartitionCount:[[:space:]]$partitions([[:space:]]|$) ]]
  [[ "$description" =~ ReplicationFactor:[[:space:]]1([[:space:]]|$) ]]
  [[ "$config" =~ retention.ms=604800000([[:space:],}]|$) ]]
  [[ "$config" =~ max.message.bytes=$bytes([[:space:],}]|$) ]]
  printf '%s\n%s\n' "$description" "$config"
done

