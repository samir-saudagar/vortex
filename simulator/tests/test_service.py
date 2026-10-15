from dataclasses import replace
from datetime import UTC, datetime
import json

import pytest

from vortex_simulator.command_journal import CommandJournal
from vortex_simulator.envelopes import serialize_envelope
from vortex_simulator.models import MAX_ENVELOPE_BYTES, EnvelopeValidationError, SimulationConfig, parse_movement_command_envelope
from vortex_simulator.service import SimulatorService
import vortex_simulator.service as service_module


def test_terminal_reports_are_saved_before_publication_and_keep_correlation(simulator_config, command_bytes, command_document):
    observed = []
    service = None
    command = parse_movement_command_envelope(command_bytes)
    def publish(raw):
        envelope = json.loads(raw)
        status = envelope["payload"].get("status")
        terminal = status in {"SUCCEEDED", "FAILED", "REJECTED"} or envelope["eventType"] == "ScannerObservation"
        assert service.journal.get(command.command_id).state == ("COMPLETED" if terminal else "IN_PROGRESS")
        observed.append(envelope)
    service = SimulatorService(simulator_config, publish)
    try:
        reports = service.process_command(command_bytes)
        assert [row["payload"].get("status", row["eventType"]) for row in observed] == [
            "ACCEPTED", "STARTED", "SUCCEEDED", "ScannerObservation"]
        assert all(row["correlationId"] == command_document["correlationId"] for row in observed)
        assert all(row["causationId"] == command_document["eventId"] for row in observed)
        assert all(row["aggregateId"] == command_document["aggregateId"] for row in observed)
        assert service.journal.get(command.command_id).saved_reports == reports
    finally:
        service.journal.close()


def test_completed_duplicate_after_restart_only_republishes_saved_bytes(simulator_config, command_bytes, monkeypatch):
    emitted = []
    first = SimulatorService(simulator_config, emitted.append)
    saved = first.process_command(command_bytes)
    first.journal.close()
    def forbidden(*_args, **_kwargs):
        raise AssertionError("duplicate must not execute movement")
    monkeypatch.setattr(service_module, "simulate_movement", forbidden)
    restarted = SimulatorService(simulator_config, emitted.append)
    try:
        assert restarted.process_command(command_bytes) == saved
        assert tuple(emitted[:4]) == tuple(emitted[4:])
    finally:
        restarted.journal.close()


def test_in_progress_restart_reports_uncertain_without_observation_or_movement(simulator_config, command_bytes, monkeypatch):
    command = parse_movement_command_envelope(command_bytes)
    with CommandJournal(simulator_config.journal_path) as journal:
        journal.mark_in_progress(command)
    def forbidden(*_args, **_kwargs):
        raise AssertionError("interrupted movement must not be repeated")
    monkeypatch.setattr(service_module, "simulate_movement", forbidden)
    emitted = []
    restarted = SimulatorService(simulator_config, emitted.append)
    try:
        restarted.recover_interrupted()
        restarted.process_command(command_bytes)
        assert len(emitted) == 2 and emitted[0] == emitted[1]
        envelope = json.loads(emitted[0])
        assert envelope["eventType"] == "EquipmentCommandResult"
        assert envelope["payload"]["status"] == "FAILED"
        assert envelope["payload"]["failureCode"] == "SIMULATOR_EXECUTION_UNCERTAIN"
        assert envelope["correlationId"] == str(command.correlation_id)
    finally:
        restarted.journal.close()


def test_crash_during_delay_remains_in_progress_and_never_emits_observation(simulator_config, command_bytes):
    config = replace(simulator_config, simulation=SimulationConfig(delay_seconds=1))
    emitted = []
    def interrupted(_seconds):
        raise SystemExit("injected process crash")
    first = SimulatorService(config, emitted.append, sleep=interrupted)
    try:
        with pytest.raises(SystemExit):
            first.process_command(command_bytes)
        command = parse_movement_command_envelope(command_bytes)
        assert first.journal.get(command.command_id).state == "IN_PROGRESS"
        assert [json.loads(row)["payload"]["status"] for row in emitted] == ["ACCEPTED"]
    finally:
        first.journal.close()
    restarted = SimulatorService(config, emitted.append)
    try:
        restarted.recover_interrupted()
        assert json.loads(emitted[-1])["payload"]["failureCode"] == "SIMULATOR_EXECUTION_UNCERTAIN"
        assert all(json.loads(row)["eventType"] != "ScannerObservation" for row in emitted)
    finally:
        restarted.journal.close()


def test_same_command_id_with_changed_fields_is_rejected(simulator_config, command_bytes, command_document):
    service = SimulatorService(simulator_config, lambda _: None)
    try:
        saved = service.process_command(command_bytes)
        command_document["payload"]["toNodeId"] = "makeup-lhr"
        with pytest.raises(EnvelopeValidationError, match="commandId was reused"):
            service.process_command(json.dumps(command_document).encode())
        assert service.journal.get(parse_movement_command_envelope(command_bytes).command_id).saved_reports == saved
    finally:
        service.journal.close()


def test_delay_is_applied_while_journal_is_in_progress(simulator_config, command_bytes):
    command = parse_movement_command_envelope(command_bytes)
    waits = []
    service = None
    def sleep(seconds):
        assert service.journal.get(command.command_id).state == "IN_PROGRESS"
        waits.append(seconds)
    service = SimulatorService(replace(simulator_config, simulation=SimulationConfig(2.5)), lambda _: None, sleep=sleep)
    try:
        service.process_command(command_bytes)
        assert waits == [2.5, 2.5]
    finally:
        service.journal.close()


def test_startup_reports_all_equipment_with_fresh_ids_per_startup(simulator_config):
    first_output = []
    first = SimulatorService(simulator_config, first_output.append)
    first.publish_startup_equipment_status()
    first.publish_startup_equipment_status()
    first.journal.close()
    second_output = []
    second = SimulatorService(simulator_config, second_output.append)
    try:
        second.publish_startup_equipment_status()
        assert len(first_output) == 18 and first_output[:9] == first_output[9:]
        assert len(second_output) == 9
        assert {json.loads(row)["eventId"] for row in first_output}.isdisjoint(
            {json.loads(row)["eventId"] for row in second_output})
        for raw in second_output:
            envelope = json.loads(raw)
            assert envelope["aggregateType"] == "EQUIPMENT" and envelope["causationId"] is None
            assert envelope["eventId"] == envelope["payload"]["reportId"]
            assert envelope["aggregateId"] == envelope["payload"]["equipmentId"]
    finally:
        second.journal.close()


def test_outgoing_envelope_limit_is_inclusive_utf8_bytes(command_bytes):
    command = parse_movement_command_envelope(command_bytes)
    fields = dict(event_id=command.command_id, event_type="EquipmentCommandResult", occurred_at=command.issued_at,
                  aggregate_id=str(command.bag_id), aggregate_type="BAG", correlation_id=command.correlation_id,
                  causation_id=command.command_id)
    base = serialize_envelope(**fields, payload={"padding": ""})
    padding = "x" * (MAX_ENVELOPE_BYTES - len(base))
    assert len(serialize_envelope(**fields, payload={"padding": padding})) == MAX_ENVELOPE_BYTES
    with pytest.raises(EnvelopeValidationError):
        serialize_envelope(**fields, payload={"padding": padding + "x"})
    with pytest.raises(EnvelopeValidationError):
        serialize_envelope(**fields, payload={"padding": "é" * 530_000})
