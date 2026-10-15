from __future__ import annotations

import json
from uuid import UUID

import pytest

from vortex_simulator.models import (
    MAX_ENVELOPE_BYTES,
    EnvelopeValidationError,
    parse_movement_command_envelope,
)


EVENT_ID = "d3ea48b1-d9ad-4fea-b09c-9421c8f4865f"
BAG_ID = "e57ed129-6923-4ec0-8ea8-7e7ce4dd1ad0"
ROUTE_ID = "e2c4c35b-73b3-4ac6-a7ba-73eacaa64e2c"


def test_valid_command_envelope_decodes_to_typed_command() -> None:
    command = parse_movement_command_envelope(_encoded(_valid_envelope()))

    assert command.command_id == UUID(EVENT_ID)
    assert command.bag_id == UUID(BAG_ID)
    assert command.route_id == UUID(ROUTE_ID)
    assert command.route_step_no == 1
    assert command.to_node_id == "junction-west"


@pytest.mark.parametrize(
    ("mutate", "expected_code"),
    [
        (lambda envelope: envelope.pop("correlationId"), "REQUIRED_FIELD_MISSING"),
        (lambda envelope: envelope.__setitem__("eventVersion", 2), "UNSUPPORTED_EVENT_VERSION"),
        (lambda envelope: envelope.__setitem__("eventType", "ScannerObservation"), "UNSUPPORTED_EVENT_TYPE"),
        (lambda envelope: envelope["payload"].pop("attempt"), "REQUIRED_FIELD_MISSING"),
        (lambda envelope: envelope["payload"].__setitem__("commandId", "not-a-uuid"), "INVALID_IDENTIFIER"),
        (lambda envelope: envelope.__setitem__("aggregateId", ROUTE_ID), "INVALID_IDENTIFIER"),
        (lambda envelope: envelope.__setitem__("occurredAt", "2030-01-15T10:00:00+01:00"), "INVALID_TIMESTAMP"),
        (lambda envelope: envelope["payload"].__setitem__("routeStepNo", True), "INVALID_PAYLOAD"),
    ],
)
def test_rejects_invalid_envelope_and_command_payload(mutate, expected_code: str) -> None:
    envelope = _valid_envelope()
    mutate(envelope)

    with pytest.raises(EnvelopeValidationError) as error:
        parse_movement_command_envelope(_encoded(envelope))

    assert error.value.code == expected_code


def test_accepts_exactly_one_mibibyte_and_rejects_one_byte_over() -> None:
    at_limit = _envelope_at_size(MAX_ENVELOPE_BYTES)
    assert len(at_limit) == MAX_ENVELOPE_BYTES
    parse_movement_command_envelope(at_limit)

    above_limit = _envelope_at_size(MAX_ENVELOPE_BYTES + 1)
    assert len(above_limit) == MAX_ENVELOPE_BYTES + 1
    with pytest.raises(EnvelopeValidationError) as error:
        parse_movement_command_envelope(above_limit)
    assert error.value.code == "ENVELOPE_TOO_LARGE"


def test_envelope_limit_uses_encoded_utf8_bytes_not_character_count() -> None:
    envelope = _valid_envelope()
    envelope["payload"]["padding"] = "é" * 530_000
    encoded = _encoded(envelope)
    assert len(json.dumps(envelope, ensure_ascii=False)) < MAX_ENVELOPE_BYTES
    assert len(encoded) > MAX_ENVELOPE_BYTES

    with pytest.raises(EnvelopeValidationError) as error:
        parse_movement_command_envelope(encoded)
    assert error.value.code == "ENVELOPE_TOO_LARGE"


def test_rejects_invalid_utf8_and_non_json_constants() -> None:
    with pytest.raises(EnvelopeValidationError) as invalid_utf8:
        parse_movement_command_envelope(b"\xc3(")
    assert invalid_utf8.value.code == "INVALID_UTF8"

    with pytest.raises(EnvelopeValidationError) as invalid_json:
        parse_movement_command_envelope(b"{\"value\":NaN}")
    assert invalid_json.value.code == "INVALID_JSON"


def _envelope_at_size(size: int) -> bytes:
    envelope = _valid_envelope()
    envelope["payload"]["padding"] = ""
    base = len(_encoded(envelope))
    envelope["payload"]["padding"] = "x" * (size - base)
    return _encoded(envelope)


def _encoded(envelope: dict[str, object]) -> bytes:
    return json.dumps(envelope, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def _valid_envelope() -> dict[str, object]:
    return {
        "eventId": EVENT_ID,
        "eventType": "EquipmentMovementCommand",
        "eventVersion": 1,
        "occurredAt": "2030-01-15T10:15:30Z",
        "producer": "movement-orchestration",
        "aggregateType": "BAG",
        "aggregateId": BAG_ID,
        "correlationId": "b8b2944b-7f62-45d3-98f1-1d1f5b406f4a",
        "causationId": "61fce2ca-1d1c-4ce6-9d06-0aa49f84344d",
        "payload": {
            "commandId": EVENT_ID,
            "bagId": BAG_ID,
            "routeId": ROUTE_ID,
            "routeStepNo": 1,
            "fromNodeId": "checkin-main",
            "toNodeId": "junction-west",
            "equipmentId": "belt-west",
            "attempt": 1,
            "issuedAt": "2030-01-15T10:15:30Z",
            "deadlineAt": "2030-01-15T10:16:00Z",
        },
    }
