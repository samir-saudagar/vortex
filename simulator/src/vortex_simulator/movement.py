"""Pure deterministic simulation of one edge movement command."""

from __future__ import annotations

from datetime import datetime, timedelta
from uuid import UUID, uuid5

from vortex_simulator.models import (
    CommandResult,
    CommandResultStatus,
    EquipmentSnapshot,
    EquipmentStatus,
    EquipmentStatusReport,
    MovementCommand,
    ScannerObservation,
    SimulationConfig,
    SimulationOutcome,
)
from vortex_simulator.scenarios import FailureMode


_REPORT_NAMESPACE = UUID("8f2b4537-c0d6-4e4d-bcc2-830d1569430d")


def simulate_movement(
    command: MovementCommand,
    equipment: EquipmentSnapshot,
    *,
    failure_mode: FailureMode = FailureMode.SUCCESS,
    config: SimulationConfig = SimulationConfig(),
    expected_scanner_id: str,
) -> SimulationOutcome:
    """Return ordered reports for one command; callers own delay and transport."""
    if command.equipment_id != equipment.equipment_id:
        raise ValueError("command equipment_id must match the equipment snapshot")
    if not isinstance(failure_mode, FailureMode):
        raise ValueError("failure_mode must be a FailureMode")
    if not isinstance(config, SimulationConfig):
        raise ValueError("config must be a SimulationConfig")
    if not expected_scanner_id or not expected_scanner_id.strip():
        raise ValueError("expected_scanner_id must not be blank")

    equipment_unavailable = (
        not equipment.configured_enabled or equipment.status is not EquipmentStatus.AVAILABLE
    )
    if failure_mode is FailureMode.BLOCKED_EQUIPMENT:
        terminal_status = CommandResultStatus.REJECTED
        failure_code = "EQUIPMENT_NOT_AVAILABLE"
        safe_reason = "Configured equipment is blocked for this simulation"
    elif equipment_unavailable:
        terminal_status = CommandResultStatus.REJECTED
        failure_code = "EQUIPMENT_NOT_AVAILABLE" if equipment.status is not EquipmentStatus.AVAILABLE else "EQUIPMENT_DISABLED"
        safe_reason = "Equipment is not available for movement"
    elif failure_mode is FailureMode.REJECT:
        terminal_status = CommandResultStatus.REJECTED
        failure_code = "SIMULATED_COMMAND_REJECTION"
        safe_reason = "The configured scenario rejected the command"
    elif failure_mode is FailureMode.MOVEMENT_FAILURE:
        terminal_status = CommandResultStatus.FAILED
        failure_code = "SIMULATED_MOVEMENT_FAILURE"
        safe_reason = "The configured scenario failed during movement"
    else:
        terminal_status = CommandResultStatus.SUCCEEDED
        failure_code = None
        safe_reason = None

    result_rows: list[CommandResult] = [
        _result(command, CommandResultStatus.ACCEPTED, command.issued_at),
    ]
    observation = None
    if terminal_status is CommandResultStatus.REJECTED:
        result_rows.append(_result(
            command,
            terminal_status,
            command.issued_at + timedelta(seconds=config.delay_seconds),
            failure_code,
            safe_reason,
        ))
    else:
        started_at = command.issued_at + timedelta(seconds=config.delay_seconds)
        result_rows.append(_result(command, CommandResultStatus.STARTED, started_at))
        completed_at = started_at + timedelta(seconds=config.delay_seconds)
        result_rows.append(_result(command, terminal_status, completed_at, failure_code, safe_reason))
        if terminal_status is CommandResultStatus.SUCCEEDED and failure_mode is not FailureMode.MISSING_OBSERVATION:
            observation = ScannerObservation(
                observation_id=_stable_id(command.command_id, "ScannerObservation"),
                bag_id=command.bag_id,
                route_id=command.route_id,
                command_id=command.command_id,
                route_step_no=command.route_step_no,
                equipment_id=command.equipment_id,
                node_id=command.to_node_id,
                scanner_id=expected_scanner_id,
                observation_type="BAG_DETECTED",
                observed_at=completed_at,
            )

    return SimulationOutcome(tuple(result_rows), observation, config.delay_seconds)


def equipment_status_report(
    equipment_id: str,
    status: EquipmentStatus,
    reported_at: datetime,
    *,
    scenario_id: str,
    reason: str | None = None,
) -> EquipmentStatusReport:
    """Create a stable, repeatable equipment-status fixture/report for a scenario."""
    if not equipment_id or not equipment_id.strip() or not scenario_id or not scenario_id.strip():
        raise ValueError("equipment_id and scenario_id must not be blank")
    report_id = uuid5(_REPORT_NAMESPACE, f"EquipmentStatusReported:{scenario_id}:{equipment_id}:{status.value}")
    return EquipmentStatusReport(report_id, equipment_id, status, reported_at, reason)


def uncertain_command_result(command: MovementCommand, reported_at: datetime) -> CommandResult:
    """Return the stable terminal report used after an IN_PROGRESS journal recovery."""
    return _result(command, CommandResultStatus.FAILED, reported_at,
                   "SIMULATOR_EXECUTION_UNCERTAIN",
                   "The simulator restarted while the command was in progress")


def failed_command_result(
    command: MovementCommand, reported_at: datetime, failure_code: str, safe_reason: str
) -> CommandResult:
    """Create a deterministic terminal failure without claiming movement or location."""
    return _result(command, CommandResultStatus.FAILED, reported_at, failure_code, safe_reason)


def _result(
    command: MovementCommand,
    status: CommandResultStatus,
    reported_at: datetime,
    failure_code: str | None = None,
    safe_reason: str | None = None,
) -> CommandResult:
    return CommandResult(
        event_id=_stable_id(command.command_id, f"EquipmentCommandResult:{status.value}"),
        command_id=command.command_id,
        bag_id=command.bag_id,
        route_id=command.route_id,
        route_step_no=command.route_step_no,
        equipment_id=command.equipment_id,
        status=status,
        reported_at=reported_at,
        failure_code=failure_code,
        safe_reason=safe_reason,
    )


def _stable_id(command_id: UUID, report_kind: str) -> UUID:
    return uuid5(_REPORT_NAMESPACE, f"{command_id}:{report_kind}")
