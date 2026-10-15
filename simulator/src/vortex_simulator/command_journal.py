"""Append-only, fsynced local command journal used to prevent movement replay."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
import base64
import fcntl
import json
import os
from pathlib import Path
from typing import Any
from uuid import UUID

from vortex_simulator.models import MovementCommand


class JournalError(RuntimeError):
    """The local command journal cannot safely accept or replay work."""


@dataclass(frozen=True, slots=True)
class JournalEntry:
    state: str
    command: MovementCommand
    saved_reports: tuple[bytes, ...]


class CommandJournal:
    def __init__(self, path: Path) -> None:
        self.path = path
        self._latest: dict[UUID, JournalEntry] = {}
        self._file = None
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self._file = self.path.open("a+b")
            fcntl.flock(self._file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            self._load()
        except (OSError, JournalError) as error:
            self.close()
            raise JournalError("could not open, lock, or recover simulator command journal") from error

    def close(self) -> None:
        if self._file is not None:
            self._file.close()
            self._file = None

    def __enter__(self) -> "CommandJournal":
        return self

    def __exit__(self, *_args: object) -> None:
        self.close()

    def get(self, command_id: UUID) -> JournalEntry | None:
        if self._file is None:
            raise JournalError("journal is closed; repair storage and restart the simulator")
        return self._latest.get(command_id)

    def in_progress_entries(self) -> tuple[JournalEntry, ...]:
        if self._file is None:
            raise JournalError("journal is closed; repair storage and restart the simulator")
        return tuple(entry for entry in self._latest.values() if entry.state == "IN_PROGRESS")

    def mark_in_progress(self, command: MovementCommand) -> None:
        self._append({"state": "IN_PROGRESS", "command": _command_to_json(command)})

    def mark_completed(self, command: MovementCommand, reports: tuple[bytes, ...]) -> None:
        self._append({
            "state": "COMPLETED",
            "command": _command_to_json(command),
            "savedReports": [base64.b64encode(report).decode("ascii") for report in reports],
        })

    def _append(self, entry: dict[str, Any]) -> None:
        document = {"commandId": entry["command"]["command_id"], **entry}
        data = json.dumps(document, separators=(",", ":"), sort_keys=True).encode("utf-8") + b"\n"
        try:
            if self._file is None:
                raise OSError("journal is closed")
            self._file.write(data)
            self._file.flush()
            os.fsync(self._file.fileno())
            # Persist the directory entry too, including a newly created journal file.
            directory_fd = os.open(self.path.parent, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        except OSError as error:
            # A partial write must never be followed by another append in this process.
            # Restart recovery will truncate only the torn tail and retain durable intent.
            self.close()
            raise JournalError("could not durably write simulator command journal") from error
        command = _command_from_json(entry["command"])
        reports = tuple(base64.b64decode(value, validate=True) for value in entry.get("savedReports", []))
        self._latest[command.command_id] = JournalEntry(entry["state"], command, reports)

    def _load(self) -> None:
        try:
            self._file.seek(0)
            data = self._file.read()
        except OSError as error:
            raise JournalError("could not read simulator command journal") from error
        lines = data.splitlines(keepends=True)
        valid_bytes = 0
        for line in lines:
            if not line.endswith(b"\n"):
                # begin/complete returns only after a full newline-terminated fsync. A torn final
                # append cannot have authorized movement. Retain its preceding IN_PROGRESS record.
                self._file.truncate(valid_bytes)
                self._file.flush()
                os.fsync(self._file.fileno())
                break
            try:
                document = json.loads(line)
                command = _command_from_json(document["command"])
                command_id = UUID(document["commandId"])
                if command_id != command.command_id:
                    raise ValueError("journal command ID mismatch")
                state = document["state"]
                if state not in {"IN_PROGRESS", "COMPLETED"}:
                    raise ValueError("invalid journal state")
                reports = tuple(base64.b64decode(value, validate=True)
                                for value in document.get("savedReports", []))
                if state == "COMPLETED" and not reports:
                    raise ValueError("completed journal entry has no saved reports")
                self._latest[command_id] = JournalEntry(state, command, reports)
                valid_bytes += len(line)
            except (ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
                raise JournalError("simulator command journal contains an invalid complete record") from error


def _command_to_json(command: MovementCommand) -> dict[str, object]:
    return {
        "command_id": str(command.command_id),
        "bag_id": str(command.bag_id),
        "route_id": str(command.route_id),
        "route_step_no": command.route_step_no,
        "from_node_id": command.from_node_id,
        "to_node_id": command.to_node_id,
        "equipment_id": command.equipment_id,
        "attempt": command.attempt,
        "issued_at": command.issued_at.isoformat(),
        "deadline_at": command.deadline_at.isoformat(),
        "correlation_id": str(command.correlation_id),
    }


def _command_from_json(document: dict[str, Any]) -> MovementCommand:
    return MovementCommand(
        command_id=UUID(document["command_id"]),
        bag_id=UUID(document["bag_id"]),
        route_id=UUID(document["route_id"]),
        route_step_no=int(document["route_step_no"]),
        from_node_id=str(document["from_node_id"]),
        to_node_id=str(document["to_node_id"]),
        equipment_id=str(document["equipment_id"]),
        attempt=int(document["attempt"]),
        issued_at=datetime.fromisoformat(document["issued_at"]),
        deadline_at=datetime.fromisoformat(document["deadline_at"]),
        correlation_id=UUID(document["correlation_id"]),
    )
