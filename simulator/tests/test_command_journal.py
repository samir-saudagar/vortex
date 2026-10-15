import json
import os

import pytest

from vortex_simulator.command_journal import CommandJournal, JournalError
from vortex_simulator.models import parse_movement_command_envelope


def test_in_progress_and_completed_records_survive_reopen(tmp_path, command_bytes):
    path = tmp_path / "journal.jsonl"
    command = parse_movement_command_envelope(command_bytes)
    with CommandJournal(path) as journal:
        journal.mark_in_progress(command)
    with CommandJournal(path) as journal:
        assert journal.get(command.command_id).state == "IN_PROGRESS"
        journal.mark_completed(command, (b'{"saved":true}',))
    with CommandJournal(path) as journal:
        assert journal.get(command.command_id).state == "COMPLETED"
        assert journal.get(command.command_id).saved_reports == (b'{"saved":true}',)


def test_single_writer_lock_rejects_a_second_simulator(tmp_path):
    path = tmp_path / "journal.jsonl"
    with CommandJournal(path):
        with pytest.raises(JournalError):
            CommandJournal(path)


def test_torn_terminal_append_keeps_intent_and_allows_safe_future_appends(tmp_path, command_bytes):
    path = tmp_path / "journal.jsonl"
    command = parse_movement_command_envelope(command_bytes)
    with CommandJournal(path) as journal:
        journal.mark_in_progress(command)
    with path.open("ab") as output:
        output.write(b'{"state":"COMPLETED","savedReports":[')
    with CommandJournal(path) as journal:
        assert journal.get(command.command_id).state == "IN_PROGRESS"
        journal.mark_completed(command, (b'{"uncertain":true}',))
    with CommandJournal(path) as journal:
        assert journal.get(command.command_id).saved_reports == (b'{"uncertain":true}',)
    assert all(json.loads(line) for line in path.read_text().splitlines())


def test_complete_corrupt_record_fails_closed(tmp_path):
    path = tmp_path / "journal.jsonl"
    path.write_bytes(b'{"bad":true}\n')
    with pytest.raises(JournalError):
        CommandJournal(path)


def test_failed_fsync_does_not_authorize_movement(tmp_path, command_bytes, monkeypatch):
    command = parse_movement_command_envelope(command_bytes)
    with CommandJournal(tmp_path / "journal.jsonl") as journal:
        def fail(_fd):
            raise OSError("injected disk failure")
        monkeypatch.setattr(os, "fsync", fail)
        with pytest.raises(JournalError):
            journal.mark_in_progress(command)
        with pytest.raises(JournalError, match="restart"):
            journal.get(command.command_id)
        with pytest.raises(JournalError):
            journal.mark_in_progress(command)
