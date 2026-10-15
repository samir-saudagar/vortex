"""Kafka-independent command/journal coordinator for the Python simulator."""

from __future__ import annotations

from datetime import UTC, datetime
import time
from typing import Callable
from uuid import uuid4

from vortex_simulator.command_journal import CommandJournal, JournalEntry
from vortex_simulator.config import SimulatorConfig
from vortex_simulator.envelopes import (
    serialize_command_result,
    serialize_equipment_status,
    serialize_observation,
)
from vortex_simulator.models import CommandResultStatus, EnvelopeValidationError, parse_movement_command_envelope
from vortex_simulator.movement import (
    equipment_status_report,
    failed_command_result,
    simulate_movement,
    uncertain_command_result,
)


class SimulatorService:
    def __init__(self, config: SimulatorConfig, publish: Callable[[bytes], None], *,
                 journal: CommandJournal | None = None, sleep: Callable[[float], None] = time.sleep,
                 now: Callable[[], datetime] = lambda: datetime.now(UTC)):
        self.config = config
        self.publish = publish
        self.journal = journal or CommandJournal(config.journal_path)
        self.sleep = sleep
        self.now = now
        self._startup_id = str(uuid4())
        self._startup_reports: tuple[bytes, ...] | None = None

    def publish_startup_equipment_status(self) -> None:
        if self._startup_reports is None:
            reported_at = self.now()
            self._startup_reports = tuple(
                serialize_equipment_status(equipment_status_report(
                    equipment_id, equipment.status, reported_at, scenario_id=self._startup_id))
                for equipment_id, equipment in sorted(self.config.equipment.items())
            )
        self._publish_reports(self._startup_reports)

    def recover_interrupted(self) -> None:
        for entry in self.journal.in_progress_entries():
            reports = self._uncertain_reports(entry)
            self.journal.mark_completed(entry.command, reports)
            self._publish_reports(reports)

    def process_command(self, command_envelope: bytes) -> tuple[bytes, ...]:
        command = parse_movement_command_envelope(command_envelope)
        entry = self.journal.get(command.command_id)
        if entry is not None and entry.command != command:
            raise EnvelopeValidationError("COMMAND_ID_CONFLICT", "commandId was reused with different command fields")
        if entry is not None and entry.state == "COMPLETED":
            self._publish_reports(entry.saved_reports)
            return entry.saved_reports
        if entry is not None and entry.state == "IN_PROGRESS":
            reports = self._uncertain_reports(entry)
            self.journal.mark_completed(entry.command, reports)
            self._publish_reports(reports)
            return reports

        self.journal.mark_in_progress(command)
        equipment = self.config.equipment.get(command.equipment_id)
        scanner_id = self.config.scanners_by_node.get(command.to_node_id)
        if equipment is None:
            result = failed_command_result(command, self.now(), "SIMULATOR_EQUIPMENT_UNKNOWN",
                                           "The command references equipment not configured in the simulator")
            reports = (serialize_command_result(result, command),)
        elif scanner_id is None:
            result = failed_command_result(command, self.now(), "SIMULATOR_SCANNER_NOT_CONFIGURED",
                                           "The destination has no configured scanner")
            reports = (serialize_command_result(result, command),)
        else:
            outcome = simulate_movement(
                command,
                equipment,
                failure_mode=self.config.failure_mode,
                config=self.config.simulation,
                expected_scanner_id=scanner_id,
            )
            rows = [serialize_command_result(result, command) for result in outcome.command_results]
            if outcome.observation is not None:
                rows.append(serialize_observation(outcome.observation, command))
            reports = tuple(rows)
            prefix = 0
            for result in outcome.command_results:
                if result.status not in {CommandResultStatus.ACCEPTED, CommandResultStatus.STARTED}:
                    break
                if result.status is CommandResultStatus.STARTED and outcome.delay_seconds:
                    self.sleep(outcome.delay_seconds)
                self.publish(reports[prefix])
                prefix += 1
            if outcome.delay_seconds:
                self.sleep(outcome.delay_seconds)
            # Movement delays and nonterminal reports occur while the journal remains IN_PROGRESS.
            # Persist every report before publishing terminal results or location observations.
            self.journal.mark_completed(command, reports)
            self._publish_reports(reports[prefix:])
            return reports
        self.journal.mark_completed(command, reports)
        self._publish_reports(reports)
        return reports

    def _uncertain_reports(self, entry: JournalEntry) -> tuple[bytes, ...]:
        result = uncertain_command_result(entry.command, self.now())
        return (serialize_command_result(result, entry.command),)

    def _publish_reports(self, reports: tuple[bytes, ...]) -> None:
        for report in reports:
            self.publish(report)
