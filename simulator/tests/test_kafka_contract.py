"""Opt-in contract/restart tests using only dedicated diagnostic Kafka topics."""

from dataclasses import replace
import base64
import json
import os
import time
from uuid import uuid4

import pytest
from kafka import KafkaConsumer
from kafka.admin import KafkaAdminClient, NewTopic, ConfigResource, ConfigResourceType
from kafka.structs import TopicPartition

from vortex_simulator.kafka_adapter import KafkaSimulator
from vortex_simulator.models import MAX_ENVELOPE_BYTES, parse_movement_command_envelope
import vortex_simulator.service as service_module


pytestmark = pytest.mark.skipif(os.environ.get("VORTEX_SIMULATOR_KAFKA_TEST") != "true",
                                reason="Set VORTEX_SIMULATOR_KAFKA_TEST=true for the local Kafka contract test")
TOPICS = {"vortex.phase3-test.workflow": 3, "vortex.phase3-test.commands": 3,
          "vortex.phase3-test.reports": 3, "vortex.phase3-test.quarantine": 1}


def _consumer(bootstrap, topic, *, group=None, start_at_end=False):
    consumer = KafkaConsumer(bootstrap_servers=bootstrap, group_id=group, enable_auto_commit=False,
                             auto_offset_reset="earliest", max_poll_records=1)
    partitions = [TopicPartition(topic, partition) for partition in sorted(consumer.partitions_for_topic(topic))]
    consumer.assign(partitions)
    if start_at_end:
        consumer.seek_to_end(*partitions)
    for partition in partitions:
        consumer.position(partition)
    if group is not None and start_at_end:
        consumer.commit()  # Establish only this new diagnostic group's baseline before publishing fixtures.
    return consumer


def _read(observer, command_id, count, *, source_offset=None, source_partition=None, consumer_group=None):
    found = []
    deadline = time.monotonic() + 20
    while len(found) < count and time.monotonic() < deadline:
        for records in observer.poll(timeout_ms=100).values():
            for record in records:
                document = json.loads(record.value)
                identity = document.get("payload", {}).get("commandId")
                if document.get("originalEnvelope"):
                    identity = document["originalEnvelope"].get("payload", {}).get("commandId")
                matches = (identity == command_id if source_offset is None else
                           document.get("sourceOffset") == source_offset
                           and document.get("sourcePartition") == source_partition
                           and document.get("consumerGroup") == consumer_group)
                if matches:
                    found.append((record, document))
    assert len(found) == count
    return found


def _process_one(adapter):
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        if adapter.poll_once():
            return
    raise AssertionError("simulator did not consume the diagnostic command")


def _fresh(document):
    document = json.loads(json.dumps(document))
    command_id, bag_id = str(uuid4()), str(uuid4())
    document["eventId"] = document["payload"]["commandId"] = command_id
    document["aggregateId"] = document["payload"]["bagId"] = bag_id
    document["payload"]["routeId"] = str(uuid4())
    document["correlationId"] = str(uuid4())
    return document


def test_exact_broker_command_reports_restart_duplicate_uncertainty_and_quarantine(
        simulator_config, command_document, monkeypatch):
    bootstrap = os.environ.get("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
    admin = KafkaAdminClient(bootstrap_servers=bootstrap, client_id="vortex-phase3-contract")
    try:
        existing = set(admin.list_topics())
        missing = [NewTopic(topic, partitions, 1,
                            topic_configs={"retention.ms": "604800000", "max.message.bytes":
                                           "33554432" if topic.endswith(".quarantine") else "2097152"})
                   for topic, partitions in TOPICS.items() if topic not in existing]
        if missing:
            admin.create_topics(missing)
        # Change only the diagnostic quarantine capacity, preserving retention and existing data.
        admin.alter_configs([ConfigResource(ConfigResourceType.TOPIC, "vortex.phase3-test.quarantine",
                                           {"max.message.bytes": "33554432"})], incremental=True)
    finally:
        admin.close()
    config = replace(simulator_config, bootstrap_servers=bootstrap,
                     command_topic="vortex.phase3-test.commands", report_topic="vortex.phase3-test.reports",
                     quarantine_topic="vortex.phase3-test.quarantine", consumer_group="vortex-python-contract-" + str(uuid4()))
    report_observer = _consumer(bootstrap, config.report_topic, start_at_end=True)
    quarantine_observer = _consumer(bootstrap, config.quarantine_topic, start_at_end=True)
    waits = []
    adapter = KafkaSimulator(config, consumer=_consumer(bootstrap, config.command_topic, group=config.consumer_group,
                                                       start_at_end=True), sleep=waits.append)
    try:
        first = _fresh(command_document)
        raw = json.dumps(first, separators=(",", ":")).encode()
        source = adapter.producer.send(config.command_topic, key=first["aggregateId"].encode(), value=raw).get(timeout=10)
        _process_one(adapter)
        reports = _read(report_observer, first["eventId"], 4)
        assert [document["payload"].get("status", document["eventType"]) for _, document in reports] == [
            "ACCEPTED", "STARTED", "SUCCEEDED", "ScannerObservation"]
        for record, document in reports:
            assert record.key.decode() == first["aggregateId"]
            assert document["eventVersion"] == 1
            assert document["aggregateType"] == "BAG" and document["aggregateId"] == first["aggregateId"]
            assert document["correlationId"] == first["correlationId"]
            assert document["causationId"] == first["eventId"]
            assert document["payload"]["routeId"] == first["payload"]["routeId"]
        assert adapter.consumer.committed(TopicPartition(source.topic, source.partition)) == source.offset + 1
        first_bytes = [record.value for record, _ in reports]
        adapter.close()

        def forbidden(*_args, **_kwargs):
            raise AssertionError("completed or interrupted command must not execute movement again")
        monkeypatch.setattr(service_module, "simulate_movement", forbidden)
        adapter = KafkaSimulator(config, consumer=_consumer(bootstrap, config.command_topic, group=config.consumer_group),
                                 sleep=waits.append)
        adapter.producer.send(config.command_topic, key=first["aggregateId"].encode(), value=raw).get(timeout=10)
        _process_one(adapter)
        replayed = _read(report_observer, first["eventId"], 4)
        assert [record.value for record, _ in replayed] == first_bytes

        interrupted = _fresh(command_document)
        interrupted_raw = json.dumps(interrupted).encode()
        adapter.service.journal.mark_in_progress(parse_movement_command_envelope(interrupted_raw))
        source = adapter.producer.send(config.command_topic, key=interrupted["aggregateId"].encode(), value=interrupted_raw).get(timeout=10)
        adapter.close()
        adapter = KafkaSimulator(config, consumer=_consumer(bootstrap, config.command_topic, group=config.consumer_group),
                                 sleep=waits.append)
        adapter.service.recover_interrupted()
        _process_one(adapter)
        uncertain = _read(report_observer, interrupted["eventId"], 2)
        assert uncertain[0][0].value == uncertain[1][0].value
        assert all(document["eventType"] == "EquipmentCommandResult" for _, document in uncertain)
        assert all(document["payload"]["status"] == "FAILED" and
                   document["payload"]["failureCode"] == "SIMULATOR_EXECUTION_UNCERTAIN" for _, document in uncertain)
        assert adapter.consumer.committed(TopicPartition(source.topic, source.partition)) == source.offset + 1

        poison = _fresh(command_document)
        poison["eventVersion"] = 9
        poison["payload"]["padding"] = ""
        base = len(json.dumps(poison, separators=(",", ":")).encode())
        poison["payload"]["padding"] = "x" * (MAX_ENVELOPE_BYTES - base)
        poison_raw = json.dumps(poison, separators=(",", ":")).encode()
        assert len(poison_raw) == MAX_ENVELOPE_BYTES
        source = adapter.producer.send(config.command_topic, key=poison["aggregateId"].encode(), value=poison_raw).get(timeout=10)
        _process_one(adapter)
        wrapper = _read(quarantine_observer, poison["eventId"], 1)[0][1]
        assert wrapper["attempts"] == 4 and wrapper["failureCode"] == "UNSUPPORTED_EVENT_VERSION"
        assert wrapper["consumerGroup"] == config.consumer_group
        assert wrapper["sourceOffset"] == source.offset and wrapper["sourcePartition"] == source.partition
        assert wrapper["originalEnvelope"] == poison
        assert waits == [1, 5, 15]
        assert adapter.consumer.committed(TopicPartition(source.topic, source.partition)) == source.offset + 1

        # Full source-sized malformed payloads and escaped keys must also survive quarantine.
        for size, large_key in [(1_600_000, False), (2_096_128, False), (1, True)]:
            raw = b"\xff" * size
            identity = str(uuid4()).encode()
            key = identity + b"\0" * (2_096_128 - len(identity)) if large_key else identity
            source = adapter.producer.send(config.command_topic, key=key, value=raw).get(timeout=10)
            _process_one(adapter)
            wrapper_record, wrapper = _read(quarantine_observer, None, 1, source_offset=source.offset,
                                           source_partition=source.partition, consumer_group=config.consumer_group)[0]
            assert len(wrapper_record.value) > 2_097_152
            assert base64.b64decode(wrapper["originalRawBase64"], validate=True) == raw
            assert base64.b64decode(wrapper["originalKeyBase64"], validate=True) == key
            assert wrapper_record.key == key
            assert wrapper["failureCode"] == ("INVALID_UTF8" if large_key else "ENVELOPE_TOO_LARGE")
            assert wrapper["attempts"] == 4
            assert adapter.consumer.committed(TopicPartition(source.topic, source.partition)) == source.offset + 1
        assert waits == [1, 5, 15] * 4
        print("Kafka 4.3.1 contract operations passed with kafka-python 3.0.11/Python 3.13: success, restart, duplicate, uncertain failure, poison and oversized malformed quarantine")
    finally:
        adapter.close()
        report_observer.close(autocommit=False)
        quarantine_observer.close(autocommit=False)


def test_manual_quarantine_redrive_preserves_bytes_and_executes_movement_once(
        simulator_config, command_document, monkeypatch):
    """Operator-selected redrive after fixing a transient handler bug, through normal validation."""
    prefix = 'vortex.phase6-test.' + str(uuid4())
    topics = [prefix + suffix for suffix in ('.commands', '.reports', '.quarantine')]
    bootstrap = os.environ.get('KAFKA_BOOTSTRAP_SERVERS', simulator_config.bootstrap_servers)
    admin = KafkaAdminClient(bootstrap_servers=bootstrap)
    admin.create_topics([NewTopic(topic, 1, 1) for topic in topics])
    config = replace(simulator_config, bootstrap_servers=bootstrap, command_topic=topics[0], report_topic=topics[1],
                     quarantine_topic=topics[2], consumer_group=prefix + '-python')
    delays = []
    adapter = KafkaSimulator(config, sleep=delays.append)
    quarantine = _consumer(config.bootstrap_servers, config.quarantine_topic, start_at_end=True)
    reports = _consumer(config.bootstrap_servers, config.report_topic, start_at_end=True)
    document = _fresh(command_document)
    original = json.dumps(document, separators=(',', ':')).encode()
    key = document['aggregateId'].encode()
    simulate = service_module.simulate_movement
    calls = []
    def broken(*args, **kwargs):
        raise RuntimeError('test-only handler bug before movement')
    try:
        # Fail before journal intent, so this fault has not authorized a movement.
        process = adapter.service.process_command
        monkeypatch.setattr(adapter.service, 'process_command', broken)
        adapter.producer.send(config.command_topic, key=key, value=original).get(timeout=10)
        _process_one(adapter)
        wrapper = _read(quarantine, document['eventId'], 1)[0][1]
        assert wrapper['attempts'] == 4
        assert delays == [1, 5, 15]
        assert base64.b64decode(wrapper['originalRawBase64'], validate=True) == original
        assert base64.b64decode(wrapper['originalKeyBase64'], validate=True) == key
        monkeypatch.setattr(adapter.service, 'process_command', process)
        def counted(*args, **kwargs):
            calls.append(1)
            return simulate(*args, **kwargs)
        monkeypatch.setattr(service_module, 'simulate_movement', counted)
        saved = None
        for _ in range(2):
            # The documented manual procedure: original topic/key/bytes, with no identifier rewrite.
            adapter.producer.send(wrapper['sourceTopic'],
                                  key=base64.b64decode(wrapper['originalKeyBase64'], validate=True),
                                  value=base64.b64decode(wrapper['originalRawBase64'], validate=True)).get(timeout=10)
            _process_one(adapter)
            delivered = _read(reports, document['eventId'], 4)
            wire = [record.value for record, _ in delivered]
            if saved is not None:
                assert wire == saved
            saved = wire
            assert all(envelope['correlationId'] == document['correlationId'] for _, envelope in delivered)
            assert delivered[-1][1]['payload']['routeId'] == document['payload']['routeId']
        assert calls == [1], 'A second manual redrive must only replay journaled reports'
    finally:
        adapter.close()
        quarantine.close()
        reports.close()
        admin.close()
