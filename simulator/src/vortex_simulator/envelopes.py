"""Wire serialization for the approved VORTEX event envelope and report payloads."""

from __future__ import annotations

from datetime import UTC, datetime
import json
from uuid import UUID

from vortex_simulator.models import (
    MAX_ENVELOPE_BYTES, EnvelopeValidationError, CommandResult,
    EquipmentStatusReport, MovementCommand, ScannerObservation,
)


def serialize_command_result(result: CommandResult, command: MovementCommand) -> bytes:
    payload: dict[str, object] = {
        "commandId": str(result.command_id),
        "bagId": str(result.bag_id),
        "routeId": str(result.route_id),
        "routeStepNo": result.route_step_no,
        "equipmentId": result.equipment_id,
        "status": result.status.value,
        "reportedAt": _timestamp(result.reported_at),
    }
    if result.failure_code is not None:
        payload["failureCode"] = result.failure_code
    if result.safe_reason is not None:
        payload["safeReason"] = result.safe_reason
    return serialize_envelope(
        event_id=result.event_id,
        event_type="EquipmentCommandResult",
        occurred_at=result.reported_at,
        aggregate_id=str(result.bag_id),
        aggregate_type="BAG",
        correlation_id=command.correlation_id,
        causation_id=result.command_id,
        payload=payload,
    )


def serialize_observation(observation: ScannerObservation, command: MovementCommand) -> bytes:
    return serialize_envelope(
        event_id=observation.observation_id,
        event_type="ScannerObservation",
        occurred_at=observation.observed_at,
        aggregate_id=str(observation.bag_id),
        aggregate_type="BAG",
        correlation_id=command.correlation_id,
        causation_id=observation.command_id,
        payload={
            "observationId": str(observation.observation_id),
            "bagId": str(observation.bag_id),
            "routeId": str(observation.route_id),
            "commandId": str(observation.command_id),
            "routeStepNo": observation.route_step_no,
            "equipmentId": observation.equipment_id,
            "nodeId": observation.node_id,
            "scannerId": observation.scanner_id,
            "observationType": observation.observation_type,
            "observedAt": _timestamp(observation.observed_at),
        },
    )


def serialize_equipment_status(report: EquipmentStatusReport) -> bytes:
    payload: dict[str, object] = {
        "reportId": str(report.report_id),
        "equipmentId": report.equipment_id,
        "status": report.status.value,
        "reportedAt": _timestamp(report.reported_at),
    }
    if report.reason is not None:
        payload["reason"] = report.reason
    return serialize_envelope(
        event_id=report.report_id,
        event_type="EquipmentStatusReported",
        occurred_at=report.reported_at,
        aggregate_id=report.equipment_id,
        aggregate_type="EQUIPMENT",
        correlation_id=report.report_id,
        causation_id=None,
        payload=payload,
    )


def serialize_envelope(
    *,
    event_id: UUID,
    event_type: str,
    occurred_at: datetime,
    aggregate_id: str,
    aggregate_type: str,
    correlation_id: UUID,
    causation_id: UUID | None,
    payload: dict[str, object],
) -> bytes:
    envelope = {
        "eventId": str(event_id),
        "eventType": event_type,
        "eventVersion": 1,
        "occurredAt": _timestamp(occurred_at),
        "producer": "python-equipment-simulator",
        "aggregateType": aggregate_type,
        "aggregateId": aggregate_id,
        "correlationId": str(correlation_id),
        "causationId": None if causation_id is None else str(causation_id),
        "payload": payload,
    }
    encoded = json.dumps(envelope, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    if len(encoded) > MAX_ENVELOPE_BYTES:
        raise EnvelopeValidationError("ENVELOPE_TOO_LARGE", "event envelope exceeds the 1048576-byte limit")
    return encoded


def _timestamp(value: datetime) -> str:
    if value.tzinfo is None or value.utcoffset() != UTC.utcoffset(value):
        raise ValueError("event timestamps must be timezone-aware UTC")
    return value.astimezone(UTC).isoformat().replace("+00:00", "Z")
