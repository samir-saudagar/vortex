from __future__ import annotations

from datetime import UTC, datetime, timedelta
from uuid import UUID

import pytest

from vortex_simulator.models import (
    CommandResultStatus,
    EquipmentSnapshot,
    EquipmentStatus,
    MovementCommand,
    SimulationConfig,
)
from vortex_simulator.movement import equipment_status_report, simulate_movement
from vortex_simulator.scenarios import FailureMode


ISSUED_AT = datetime(2030, 1, 15, 10, 15, 30, tzinfo=UTC)
COMMAND = MovementCommand(
    command_id=UUID("d3ea48b1-d9ad-4fea-b09c-9421c8f4865f"),
    bag_id=UUID("e57ed129-6923-4ec0-8ea8-7e7ce4dd1ad0"),
    route_id=UUID("e2c4c35b-73b3-4ac6-a7ba-73eacaa64e2c"),
    route_step_no=1,
    from_node_id="checkin-main",
    to_node_id="junction-west",
    equipment_id="belt-west",
    attempt=1,
    issued_at=ISSUED_AT,
    deadline_at=ISSUED_AT + timedelta(seconds=30),
)
AVAILABLE = EquipmentSnapshot("belt-west", EquipmentStatus.AVAILABLE)


def test_success_emits_ordered_command_results_then_checkpoint_observation() -> None:
    outcome = simulate_movement(COMMAND, AVAILABLE, expected_scanner_id="scanner-west")

    assert [result.status for result in outcome.command_results] == [
        CommandResultStatus.ACCEPTED,
        CommandResultStatus.STARTED,
        CommandResultStatus.SUCCEEDED,
    ]
    assert outcome.observation is not None
    assert outcome.observation.node_id == COMMAND.to_node_id
    assert outcome.observation.scanner_id == "scanner-west"
    assert outcome.observation.command_id == COMMAND.command_id


@pytest.mark.parametrize(
    ("mode", "status", "failure_code"),
    [
        (FailureMode.REJECT, CommandResultStatus.REJECTED, "SIMULATED_COMMAND_REJECTION"),
        (FailureMode.BLOCKED_EQUIPMENT, CommandResultStatus.REJECTED, "EQUIPMENT_NOT_AVAILABLE"),
        (FailureMode.MOVEMENT_FAILURE, CommandResultStatus.FAILED, "SIMULATED_MOVEMENT_FAILURE"),
    ],
)
def test_configured_failure_modes_never_emit_scanner_observation(mode, status, failure_code) -> None:
    outcome = simulate_movement(COMMAND, AVAILABLE, failure_mode=mode, expected_scanner_id="scanner-west")

    assert outcome.command_results[-1].status is status
    assert outcome.command_results[-1].failure_code == failure_code
    assert outcome.observation is None


def test_blocked_equipment_snapshot_rejects_movement() -> None:
    blocked = EquipmentSnapshot("belt-west", EquipmentStatus.BLOCKED)

    outcome = simulate_movement(COMMAND, blocked, expected_scanner_id="scanner-west")

    assert [result.status for result in outcome.command_results] == [
        CommandResultStatus.ACCEPTED,
        CommandResultStatus.REJECTED,
    ]
    assert outcome.command_results[-1].failure_code == "EQUIPMENT_NOT_AVAILABLE"
    assert outcome.observation is None


def test_missing_observation_keeps_successful_command_result_but_no_location_fact() -> None:
    outcome = simulate_movement(COMMAND, AVAILABLE, failure_mode=FailureMode.MISSING_OBSERVATION,
                                expected_scanner_id="scanner-west")

    assert outcome.command_results[-1].status is CommandResultStatus.SUCCEEDED
    assert outcome.observation is None


def test_delay_configuration_is_returned_and_determines_simulated_timestamps_without_sleeping() -> None:
    outcome = simulate_movement(COMMAND, AVAILABLE, config=SimulationConfig(delay_seconds=2.5),
                                expected_scanner_id="scanner-west")

    assert outcome.delay_seconds == 2.5
    assert outcome.command_results[1].reported_at == ISSUED_AT + timedelta(seconds=2.5)
    assert outcome.command_results[2].reported_at == ISSUED_AT + timedelta(seconds=5)
    assert outcome.observation is not None
    assert outcome.observation.observed_at == outcome.command_results[2].reported_at


def test_result_and_observation_identifiers_are_stable_for_a_command() -> None:
    first = simulate_movement(COMMAND, AVAILABLE, expected_scanner_id="scanner-west")
    second = simulate_movement(COMMAND, AVAILABLE, expected_scanner_id="scanner-west")

    assert [result.event_id for result in first.command_results] == [result.event_id for result in second.command_results]
    assert first.observation is not None and second.observation is not None
    assert first.observation.observation_id == second.observation.observation_id


def test_equipment_status_scenario_report_has_deterministic_report_id() -> None:
    first = equipment_status_report("belt-west", EquipmentStatus.BLOCKED, ISSUED_AT,
                                    scenario_id="demo-blocked", reason="DEMO_SCENARIO")
    second = equipment_status_report("belt-west", EquipmentStatus.BLOCKED, ISSUED_AT,
                                     scenario_id="demo-blocked", reason="DEMO_SCENARIO")

    assert first.report_id == second.report_id
    assert first.status is EquipmentStatus.BLOCKED


def test_rejects_invalid_commands_and_negative_or_non_finite_delay() -> None:
    with pytest.raises(ValueError):
        MovementCommand(COMMAND.command_id, COMMAND.bag_id, COMMAND.route_id, 0,
                        COMMAND.from_node_id, COMMAND.to_node_id, COMMAND.equipment_id, 1,
                        COMMAND.issued_at, COMMAND.deadline_at)
    with pytest.raises(ValueError):
        SimulationConfig(delay_seconds=-0.1)
    with pytest.raises(ValueError):
        SimulationConfig(delay_seconds=float("inf"))
