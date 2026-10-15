import base64
from dataclasses import replace
import json
from types import SimpleNamespace
from unittest.mock import Mock

import pytest
from kafka.errors import CommitFailedError, KafkaTimeoutError
from kafka.structs import TopicPartition, OffsetAndMetadata

from vortex_simulator.command_journal import CommandJournal, JournalError
from vortex_simulator.kafka_adapter import KafkaSimulator, QUARANTINE_MAX_REQUEST_BYTES
from vortex_simulator.scenarios import FailureMode


def _record(value, key=b"e57ed129-6923-4ec0-8ea8-7e7ce4dd1ad0", offset=17):
    return SimpleNamespace(topic="vortex.equipment-commands", partition=1, offset=offset, key=key, value=value)


def _transport(config, record, *, send_failure=False, commit_failure=False):
    producer, consumer = Mock(), Mock()
    producer.acknowledged = []
    sent = []
    def send(topic, *, key, value):
        sent.append((topic, key, value))
        future = Mock()
        if send_failure and len(sent) == 1:
            future.get.side_effect = KafkaTimeoutError("injected broker failure")
        else:
            sequence = len(sent)
            future.get.side_effect = lambda **_kwargs: producer.acknowledged.append(sequence)
        return future
    producer.send.side_effect = send
    consumer.poll.side_effect = [{TopicPartition(record.topic, record.partition): [record]}, {}]
    if commit_failure:
        consumer.commit.side_effect = [CommitFailedError("injected lost commit"), None]
    waits = []
    adapter = KafkaSimulator(config, producer=producer, consumer=consumer, sleep=waits.append)
    return adapter, producer, consumer, sent, waits


def test_invalid_version_gets_four_attempts_and_quarantine_ack_before_commit(simulator_config, command_document):
    command_document["eventVersion"] = 99
    record = _record(json.dumps(command_document).encode())
    adapter, producer, consumer, sent, waits = _transport(simulator_config, record)
    def require_ack(_offsets):
        assert producer.acknowledged == [1]
    consumer.commit.side_effect = require_ack
    try:
        assert adapter.poll_once()
        assert waits == [1, 5, 15]
        assert len(sent) == 1 and sent[0][0] == "vortex.quarantine"
        wrapper = json.loads(sent[0][2])
        assert wrapper["attempts"] == 4 and wrapper["failureCode"] == "UNSUPPORTED_EVENT_VERSION"
        assert wrapper["originalEnvelope"] == command_document
        assert base64.b64decode(wrapper["originalRawBase64"], validate=True) == record.value
        assert wrapper["sourceTopic"] == record.topic and wrapper["sourceOffset"] == record.offset
        assert wrapper["consumerGroup"] == simulator_config.consumer_group
        consumer.commit.assert_called_once_with({TopicPartition(record.topic, 1): OffsetAndMetadata(18, "")})
    finally:
        adapter.close()


def test_alternating_exception_types_still_quarantine_after_four_attempts(simulator_config, command_bytes):
    adapter, producer, consumer, sent, waits = _transport(simulator_config, _record(command_bytes))
    adapter.service.process_command = Mock(side_effect=[ValueError("poison"), TypeError("poison"),
                                                       ValueError("poison"), TypeError("poison")])
    consumer.commit.side_effect = lambda _offsets: producer.acknowledged == [1] or pytest.fail("commit before quarantine ack")
    try:
        assert adapter.poll_once()
        assert adapter.service.process_command.call_count == 4
        assert waits == [1, 5, 15]
        assert len(sent) == 1 and sent[0][0] == simulator_config.quarantine_topic
        wrapper = json.loads(sent[0][2])
        assert wrapper["attempts"] == 4 and wrapper["failureClass"] == "builtins.TypeError"
        consumer.commit.assert_called_once()
    finally:
        adapter.close()


@pytest.mark.parametrize("size", [1_600_000, 2_096_128])
def test_oversized_malformed_records_preserve_all_bytes_before_source_commit(simulator_config, size):
    original = b"\xff" * size
    adapter, producer, consumer, sent, waits = _transport(simulator_config, _record(original))
    def require_ack(_offsets):
        assert producer.acknowledged == [1]
        assert len(sent[0][2]) > 2_097_152
        assert len(sent[0][1]) + len(sent[0][2]) + 1024 < QUARANTINE_MAX_REQUEST_BYTES
    consumer.commit.side_effect = require_ack
    try:
        assert adapter.poll_once()
        assert waits == [1, 5, 15]
        wrapper = json.loads(sent[0][2])
        assert base64.b64decode(wrapper["originalRawBase64"], validate=True) == original
        assert wrapper["failureCode"] == "ENVELOPE_TOO_LARGE" and wrapper["attempts"] == 4
        assert wrapper["sourceOffset"] == 17
        consumer.commit.assert_called_once()
    finally:
        adapter.close()


def test_large_malformed_key_preserves_full_diagnostics_within_transport_budget(simulator_config):
    key = b"\0" * 2_096_128
    adapter, _, consumer, sent, _ = _transport(simulator_config, _record(b"\xff", key))
    try:
        assert adapter.poll_once()
        wrapper = json.loads(sent[0][2])
        assert base64.b64decode(wrapper["originalKeyBase64"], validate=True) == key
        assert wrapper["originalKey"].encode() == key
        assert len(sent[0][2]) + len(key) > 16 * 1024 * 1024
        assert len(sent[0][2]) + len(key) + 1024 < QUARANTINE_MAX_REQUEST_BYTES
        consumer.commit.assert_called_once()
    finally:
        adapter.close()


def test_report_send_failure_keeps_same_record_and_never_commits_past_it(simulator_config, command_bytes):
    adapter, _, consumer, sent, _ = _transport(simulator_config, _record(command_bytes), send_failure=True)
    try:
        with pytest.raises(KafkaTimeoutError):
            adapter.poll_once()
        consumer.commit.assert_not_called()
        assert adapter.poll_once()
        assert consumer.poll.call_count == 1
        consumer.commit.assert_called_once()
        assert len(sent) == 2
        assert json.loads(sent[-1][2])["payload"]["failureCode"] == "SIMULATOR_EXECUTION_UNCERTAIN"
        assert all(topic != "vortex.quarantine" for topic, _, _ in sent)
    finally:
        adapter.close()


def test_lost_offset_commit_republishes_completed_reports_without_movement(simulator_config, command_bytes):
    adapter, _, consumer, sent, _ = _transport(simulator_config, _record(command_bytes), commit_failure=True)
    try:
        with pytest.raises(CommitFailedError):
            adapter.poll_once()
        first = [value for _, _, value in sent]
        assert len(first) == 4
        assert adapter.poll_once()
        assert [value for _, _, value in sent[4:]] == first
        assert consumer.poll.call_count == 1
        assert consumer.commit.call_count == 2
    finally:
        adapter.close()


def test_quarantine_publish_failure_does_not_acknowledge_source(simulator_config):
    adapter, _, consumer, sent, waits = _transport(simulator_config, _record(b"not JSON"), send_failure=True)
    try:
        with pytest.raises(KafkaTimeoutError):
            adapter.poll_once()
        consumer.commit.assert_not_called()
        assert adapter.poll_once()
        assert consumer.poll.call_count == 1
        assert waits == [1, 5, 15, 1, 5, 15]
        assert len(sent) == 2 and all(topic == "vortex.quarantine" for topic, _, _ in sent)
        consumer.commit.assert_called_once()
    finally:
        adapter.close()


def test_journal_outage_is_not_quarantined(simulator_config, command_bytes):
    adapter, _, consumer, sent, waits = _transport(simulator_config, _record(command_bytes))
    adapter.service.process_command = Mock(side_effect=JournalError("injected journal outage"))
    try:
        with pytest.raises(JournalError):
            adapter.poll_once()
        assert not sent and not waits
        consumer.commit.assert_not_called()
    finally:
        adapter.close()


@pytest.mark.parametrize("value,key,code", [
    (b"\xc3(", b"bag", "INVALID_UTF8"),
    (b'{"value":NaN}', b"bag", "INVALID_JSON"),
])
def test_malformed_original_bytes_survive_quarantine(simulator_config, value, key, code):
    adapter, _, consumer, sent, _ = _transport(simulator_config, _record(value, key))
    try:
        adapter.poll_once()
        wrapper = json.loads(sent[0][2])
        assert wrapper["failureCode"] == code
        assert base64.b64decode(wrapper["originalRawBase64"]) == value
        assert base64.b64decode(wrapper["originalKeyBase64"]) == key
    finally:
        adapter.close()


def test_wrong_bag_key_is_quarantined(simulator_config, command_bytes):
    adapter, _, _, sent, waits = _transport(simulator_config, _record(command_bytes, b"wrong-key"))
    try:
        adapter.poll_once()
        assert waits == [1, 5, 15]
        assert json.loads(sent[0][2])["failureCode"] == "INVALID_IDENTIFIER"
    finally:
        adapter.close()


def test_expected_movement_failure_is_a_terminal_report_not_quarantine(simulator_config, command_bytes):
    config = replace(simulator_config, failure_mode=FailureMode.MOVEMENT_FAILURE)
    adapter, _, _, sent, waits = _transport(config, _record(command_bytes))
    try:
        adapter.poll_once()
        assert not waits
        assert len(sent) == 3 and all(topic == "vortex.simulator-reports" for topic, _, _ in sent)
        assert json.loads(sent[-1][2])["payload"]["failureCode"] == "SIMULATED_MOVEMENT_FAILURE"
    finally:
        adapter.close()


def test_failed_journal_lock_closes_newly_created_kafka_clients(simulator_config):
    producer, consumer = Mock(), Mock()
    with CommandJournal(simulator_config.journal_path):
        with pytest.raises(JournalError):
            KafkaSimulator(simulator_config, producer=producer, consumer=consumer)
    consumer.close.assert_called_once_with(autocommit=False)
    producer.close.assert_called_once_with(timeout=10)
