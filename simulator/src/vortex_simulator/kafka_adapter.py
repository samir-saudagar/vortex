"""kafka-python transport adapter with manual commit and bounded poison handling."""

from __future__ import annotations

from datetime import UTC, datetime
import base64
import json
import logging
import signal
import time
from collections import deque
from threading import Event
from typing import Any

from kafka import KafkaConsumer, KafkaProducer
from kafka.errors import KafkaError
from kafka.structs import TopicPartition, OffsetAndMetadata

from vortex_simulator.command_journal import JournalError
from vortex_simulator.config import SimulatorConfig
from vortex_simulator.models import MAX_ENVELOPE_BYTES, EnvelopeValidationError, parse_movement_command_envelope
from vortex_simulator.service import SimulatorService


LOG = logging.getLogger(__name__)
MESSAGE_RETRY_DELAYS = (1, 5, 15)
# Only quarantine needs this capacity: preserve Base64 payloads and escaped malformed keys.
# The three source topics remain 2 MiB and event envelopes remain 1 MiB.
QUARANTINE_MAX_REQUEST_BYTES = 33_554_432


class KafkaSimulator:
    def __init__(self, config: SimulatorConfig, *, producer: Any = None, consumer: Any = None,
                 sleep=time.sleep) -> None:
        self.config = config
        self.sleep = sleep
        self._pending = deque()
        self.stop_requested = Event()
        self.producer = producer if producer is not None else KafkaProducer(
            bootstrap_servers=config.bootstrap_servers.split(","),
            acks="all",
            retries=2_147_483_647,
            max_in_flight_requests_per_connection=1,
            key_serializer=None,
            value_serializer=None,
            enable_idempotence=True,
            max_request_size=QUARANTINE_MAX_REQUEST_BYTES,
        )
        self.consumer = consumer if consumer is not None else KafkaConsumer(
            config.command_topic,
            bootstrap_servers=config.bootstrap_servers.split(","),
            group_id=config.consumer_group,
            enable_auto_commit=False,
            auto_offset_reset="earliest",
            max_poll_records=1,
            max_poll_interval_ms=max(300_000, int((2 * config.simulation.delay_seconds + 150) * 1_000)),
            key_deserializer=None,
            value_deserializer=None,
        )
        try:
            self.service = SimulatorService(config, self.publish_report, sleep=sleep)
        except BaseException:
            try:
                self.consumer.close(autocommit=False)
            finally:
                self.producer.close(timeout=10)
            raise

    def publish_report(self, envelope: bytes) -> None:
        document = json.loads(envelope)
        key = str(document["aggregateId"]).encode("utf-8")
        self.producer.send(self.config.report_topic, key=key, value=envelope).get(timeout=10)

    def publish_quarantine(self, record: Any, error: Exception) -> None:
        original = record.value or b""
        try:
            if len(original) > MAX_ENVELOPE_BYTES:
                raise ValueError("oversized original retained as raw bytes")
            original_envelope = json.loads(original.decode("utf-8", errors="strict"),
                                           parse_constant=lambda value: (_ for _ in ()).throw(ValueError(value)))
        except (UnicodeDecodeError, ValueError, RecursionError):
            original_envelope = None
        wrapper: dict[str, object] = {
            "sourceTopic": record.topic,
            "sourcePartition": record.partition,
            "sourceOffset": record.offset,
            "consumerGroup": self.config.consumer_group,
            "attempts": 4,
            "failedAt": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
            "failureCode": error.code if isinstance(error, EnvelopeValidationError) else "SIMULATOR_COMMAND_FAILED",
            "failureClass": f"{type(error).__module__}.{type(error).__qualname__}",
            "safeReason": "The simulator could not process this command after four attempts",
            "originalKey": record.key.decode("utf-8", errors="replace") if record.key is not None else None,
            "originalEnvelope": original_envelope,
            "originalRawBase64": base64.b64encode(original).decode("ascii"),
        }
        if record.key is not None:
            wrapper["originalKeyBase64"] = base64.b64encode(record.key).decode("ascii")
        key = record.key
        value = json.dumps(wrapper, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        self.producer.send(self.config.quarantine_topic, key=key, value=value).get(timeout=10)
        LOG.warning("Quarantined command from %s partition %d offset %d after four attempts",
                    record.topic, record.partition, record.offset)

    def handle_record(self, record: Any) -> None:
        for attempt in range(1, 5):
            try:
                command = parse_movement_command_envelope(record.value or b"")
                if record.key != str(command.bag_id).encode("utf-8"):
                    raise EnvelopeValidationError("INVALID_IDENTIFIER", "Kafka key must match command bagId")
                self.service.process_command(record.value or b"")
                return
            except (KafkaError, JournalError):
                # Transport and local durable-storage outages are not poison messages.
                raise
            except Exception as error:
                if attempt == 4:
                    self.publish_quarantine(record, error)
                    return
                self.sleep(MESSAGE_RETRY_DELAYS[attempt - 1])

    def poll_once(self) -> bool:
        if not self._pending:
            self._buffer_poll()
        if not self._pending:
            return False
        record = self._pending[0]
        self.handle_record(record)
        partition = TopicPartition(record.topic, record.partition)
        # Commit exactly this completed record; never commit the position of another fetched record.
        self.consumer.commit({partition: OffsetAndMetadata(record.offset + 1, "")})
        self._pending.popleft()
        return True

    def _buffer_poll(self, timeout_ms: int = 1_000) -> None:
        records = self.consumer.poll(timeout_ms=timeout_ms, max_records=1)
        for partition_records in records.values():
            self._pending.extend(partition_records)

    def _dependency_wait(self, delay: int) -> None:
        # Keep polling for group membership while retaining the failed record in _pending.
        paused = self.consumer.assignment()
        self.consumer.pause(*paused)
        deadline = time.monotonic() + delay
        try:
            while not self.stop_requested.is_set() and time.monotonic() < deadline:
                try:
                    self._buffer_poll(timeout_ms=min(1_000, max(1, int((deadline - time.monotonic()) * 1_000))))
                except KafkaError:
                    self.stop_requested.wait(min(1, delay))
        finally:
            self.consumer.resume(*(paused & self.consumer.assignment()))

    def run_forever(self) -> None:
        dependency_backoff = 1
        try:
            initialized = False
            while not self.stop_requested.is_set():
                try:
                    if not initialized:
                        self.service.publish_startup_equipment_status()
                        self.service.recover_interrupted()
                        initialized = True
                        LOG.info("Simulator READY; consuming %s, publishing %s; no PostgreSQL connection",
                                 self.config.command_topic, self.config.report_topic)
                    self.poll_once()
                    dependency_backoff = 1
                except (KafkaError, JournalError) as error:
                    LOG.warning("Simulator dependency unavailable (%s); the current offset remains uncommitted",
                                type(error).__name__)
                    self._dependency_wait(dependency_backoff)
                    dependency_backoff = min(30, dependency_backoff * 2)
        finally:
            self.close()

    def close(self) -> None:
        try:
            self.consumer.close(autocommit=False)
        finally:
            try:
                self.producer.close(timeout=10)
            finally:
                self.service.journal.close()


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    config = SimulatorConfig.from_environment()
    simulator = KafkaSimulator(config)
    previous = signal.signal(signal.SIGTERM, lambda *_args: simulator.stop_requested.set())
    try:
        simulator.run_forever()
    except KeyboardInterrupt:
        pass
    finally:
        signal.signal(signal.SIGTERM, previous)
    return 0
