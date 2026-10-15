"""Typed input and output records for the pure one-edge movement core."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from enum import Enum
import json
from math import isfinite
import re
from typing import Any
from uuid import UUID


def _require_utc(value: datetime, name: str) -> None:
    if value.tzinfo is None or value.utcoffset() != timedelta(0):
        raise ValueError(f"{name} must be timezone-aware UTC")


def _require_text(value: str, name: str) -> None:
    if not value or not value.strip():
        raise ValueError(f"{name} must not be blank")


class EquipmentStatus(str, Enum):
    AVAILABLE = "AVAILABLE"
    BLOCKED = "BLOCKED"
    OFFLINE = "OFFLINE"
    MAINTENANCE = "MAINTENANCE"


class CommandResultStatus(str, Enum):
    ACCEPTED = "ACCEPTED"
    STARTED = "STARTED"
    SUCCEEDED = "SUCCEEDED"
    REJECTED = "REJECTED"
    FAILED = "FAILED"


@dataclass(frozen=True, slots=True)
class MovementCommand:
    command_id: UUID
    bag_id: UUID
    route_id: UUID
    route_step_no: int
    from_node_id: str
    to_node_id: str
    equipment_id: str
    attempt: int
    issued_at: datetime
    deadline_at: datetime
    correlation_id: UUID = UUID(int=0)

    def __post_init__(self) -> None:
        if not all(isinstance(value, UUID) for value in
                   (self.command_id, self.bag_id, self.route_id, self.correlation_id)):
            raise ValueError("command and correlation identifiers must be UUID values")
        if self.route_step_no < 1 or self.attempt < 1:
            raise ValueError("route_step_no and attempt must be positive")
        for value, name in (
            (self.from_node_id, "from_node_id"),
            (self.to_node_id, "to_node_id"),
            (self.equipment_id, "equipment_id"),
        ):
            _require_text(value, name)
        _require_utc(self.issued_at, "issued_at")
        _require_utc(self.deadline_at, "deadline_at")
        if self.deadline_at <= self.issued_at:
            raise ValueError("deadline_at must be after issued_at")


@dataclass(frozen=True, slots=True)
class EquipmentSnapshot:
    equipment_id: str
    status: EquipmentStatus
    configured_enabled: bool = True

    def __post_init__(self) -> None:
        _require_text(self.equipment_id, "equipment_id")
        if not isinstance(self.status, EquipmentStatus):
            raise ValueError("status must be an EquipmentStatus")


@dataclass(frozen=True, slots=True)
class CommandResult:
    event_id: UUID
    command_id: UUID
    bag_id: UUID
    route_id: UUID
    route_step_no: int
    equipment_id: str
    status: CommandResultStatus
    reported_at: datetime
    failure_code: str | None = None
    safe_reason: str | None = None

    def __post_init__(self) -> None:
        if not all(isinstance(value, UUID) for value in (self.event_id, self.command_id, self.bag_id, self.route_id)):
            raise ValueError("result identifiers must be UUID values")
        if self.route_step_no < 1:
            raise ValueError("route_step_no must be positive")
        _require_text(self.equipment_id, "equipment_id")
        if not isinstance(self.status, CommandResultStatus):
            raise ValueError("status must be a CommandResultStatus")
        _require_utc(self.reported_at, "reported_at")


@dataclass(frozen=True, slots=True)
class ScannerObservation:
    observation_id: UUID
    bag_id: UUID
    route_id: UUID
    command_id: UUID
    route_step_no: int
    equipment_id: str
    node_id: str
    scanner_id: str
    observation_type: str
    observed_at: datetime

    def __post_init__(self) -> None:
        if not all(isinstance(value, UUID) for value in
                   (self.observation_id, self.bag_id, self.route_id, self.command_id)):
            raise ValueError("observation identifiers must be UUID values")
        if self.route_step_no < 1:
            raise ValueError("route_step_no must be positive")
        for value, name in (
            (self.equipment_id, "equipment_id"),
            (self.node_id, "node_id"),
            (self.scanner_id, "scanner_id"),
            (self.observation_type, "observation_type"),
        ):
            _require_text(value, name)
        _require_utc(self.observed_at, "observed_at")
        if self.observation_type != "BAG_DETECTED":
            raise ValueError("observation_type must be BAG_DETECTED")


@dataclass(frozen=True, slots=True)
class SimulationConfig:
    delay_seconds: float = 0.0

    def __post_init__(self) -> None:
        if not isfinite(self.delay_seconds) or self.delay_seconds < 0:
            raise ValueError("delay_seconds must be finite and non-negative")


@dataclass(frozen=True, slots=True)
class SimulationOutcome:
    command_results: tuple[CommandResult, ...]
    observation: ScannerObservation | None
    delay_seconds: float


@dataclass(frozen=True, slots=True)
class EquipmentStatusReport:
    report_id: UUID
    equipment_id: str
    status: EquipmentStatus
    reported_at: datetime
    reason: str | None = None

    def __post_init__(self) -> None:
        if not isinstance(self.report_id, UUID):
            raise ValueError("report_id must be a UUID")
        _require_text(self.equipment_id, "equipment_id")
        if not isinstance(self.status, EquipmentStatus):
            raise ValueError("status must be an EquipmentStatus")
        _require_utc(self.reported_at, "reported_at")


MAX_ENVELOPE_BYTES = 1_048_576


class EnvelopeValidationError(ValueError):
    """Malformed or unsupported command envelope at the simulator boundary."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


def parse_movement_command_envelope(utf8_json: bytes) -> MovementCommand:
    """Validate the shared v1 envelope and decode an EquipmentMovementCommand payload."""
    if not utf8_json:
        raise EnvelopeValidationError("EMPTY_MESSAGE", "event envelope is empty")
    if len(utf8_json) > MAX_ENVELOPE_BYTES:
        raise EnvelopeValidationError("ENVELOPE_TOO_LARGE", "event envelope exceeds the 1048576-byte limit")
    try:
        text = utf8_json.decode("utf-8", errors="strict")
    except UnicodeDecodeError as error:
        raise EnvelopeValidationError("INVALID_UTF8", "event envelope is not valid UTF-8") from error
    try:
        envelope = json.loads(text, parse_constant=_reject_non_json_constant)
    except (json.JSONDecodeError, ValueError) as error:
        raise EnvelopeValidationError("INVALID_JSON", "event envelope is not valid JSON") from error
    if not isinstance(envelope, dict):
        raise EnvelopeValidationError("ENVELOPE_NOT_OBJECT", "event envelope must be a JSON object")

    event_id = _uuid(_required(envelope, "eventId"), "eventId")
    if _required(envelope, "eventType") != "EquipmentMovementCommand":
        raise EnvelopeValidationError("UNSUPPORTED_EVENT_TYPE", "simulator accepts EquipmentMovementCommand only")
    version = _required(envelope, "eventVersion")
    if type(version) is not int:
        raise EnvelopeValidationError("INVALID_FIELD_TYPE", "eventVersion must be an integer")
    if version != 1:
        raise EnvelopeValidationError("UNSUPPORTED_EVENT_VERSION", "only eventVersion 1 is supported")
    _utc_timestamp(_required(envelope, "occurredAt"), "occurredAt")
    _text(_required(envelope, "producer"), "producer")
    if _required(envelope, "aggregateType") != "BAG":
        raise EnvelopeValidationError("INVALID_FIELD_TYPE", "EquipmentMovementCommand aggregateType must be BAG")
    aggregate_id = _uuid(_required(envelope, "aggregateId"), "aggregateId")
    correlation_id = _uuid(_required(envelope, "correlationId"), "correlationId")
    causation_id = _uuid(_required(envelope, "causationId"), "causationId")

    payload = _required(envelope, "payload")
    if not isinstance(payload, dict):
        raise EnvelopeValidationError("INVALID_FIELD_TYPE", "payload must be a JSON object")
    command_id = _uuid(_required(payload, "commandId"), "commandId")
    bag_id = _uuid(_required(payload, "bagId"), "bagId")
    route_id = _uuid(_required(payload, "routeId"), "routeId")
    if command_id != event_id:
        raise EnvelopeValidationError("INVALID_IDENTIFIER", "command eventId must equal commandId")
    if bag_id != aggregate_id:
        raise EnvelopeValidationError("INVALID_IDENTIFIER", "aggregateId must match payload bagId")
    route_step_no = _positive_integer(_required(payload, "routeStepNo"), "routeStepNo")
    attempt = _positive_integer(_required(payload, "attempt"), "attempt")
    issued_at = _utc_timestamp(_required(payload, "issuedAt"), "issuedAt")
    deadline_at = _utc_timestamp(_required(payload, "deadlineAt"), "deadlineAt")
    if deadline_at <= issued_at:
        raise EnvelopeValidationError("INVALID_PAYLOAD", "deadlineAt must be after issuedAt")
    return MovementCommand(
        command_id=command_id,
        bag_id=bag_id,
        route_id=route_id,
        route_step_no=route_step_no,
        from_node_id=_text(_required(payload, "fromNodeId"), "fromNodeId"),
        to_node_id=_text(_required(payload, "toNodeId"), "toNodeId"),
        equipment_id=_text(_required(payload, "equipmentId"), "equipmentId"),
        attempt=attempt,
        issued_at=issued_at,
        deadline_at=deadline_at,
        correlation_id=correlation_id,
    )


def _required(document: dict[str, Any], field: str) -> Any:
    if field not in document:
        raise EnvelopeValidationError("REQUIRED_FIELD_MISSING", f"required field is missing: {field}")
    return document[field]


def _text(value: Any, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise EnvelopeValidationError("INVALID_FIELD_TYPE", f"{field} must be a non-empty string")
    return value


def _uuid(value: Any, field: str) -> UUID:
    if value is None:
        raise EnvelopeValidationError("INVALID_IDENTIFIER", f"{field} must be a UUID")
    text = _text(value, field)
    if not re.fullmatch(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", text):
        raise EnvelopeValidationError("INVALID_IDENTIFIER", f"{field} must be a canonical UUID")
    try:
        return UUID(text)
    except ValueError as error:
        raise EnvelopeValidationError("INVALID_IDENTIFIER", f"{field} must be a canonical UUID") from error


def _positive_integer(value: Any, field: str) -> int:
    if type(value) is not int or value <= 0:
        raise EnvelopeValidationError("INVALID_PAYLOAD", f"{field} must be a positive integer")
    return value


def _utc_timestamp(value: Any, field: str) -> datetime:
    text = _text(value, field)
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})", text):
        raise EnvelopeValidationError("INVALID_TIMESTAMP", f"{field} must be RFC 3339 UTC")
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError as error:
        raise EnvelopeValidationError("INVALID_TIMESTAMP", f"{field} must be RFC 3339 UTC") from error
    if parsed.tzinfo is None or parsed.utcoffset() != timedelta(0):
        raise EnvelopeValidationError("INVALID_TIMESTAMP", f"{field} must use UTC offset")
    return parsed.astimezone(UTC)


def _reject_non_json_constant(value: str) -> None:
    raise ValueError(f"invalid JSON constant: {value}")
