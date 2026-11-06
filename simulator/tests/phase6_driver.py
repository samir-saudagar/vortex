"""Test-only process driver: controlled time and fsynced intent crash; no application topics."""
from datetime import datetime
import json
import os
from pathlib import Path
import signal
import sys
import tempfile

from vortex_simulator.command_journal import CommandJournal
from vortex_simulator.config import SimulatorConfig
from vortex_simulator.kafka_adapter import KafkaSimulator
from vortex_simulator.models import parse_movement_command_envelope
from vortex_simulator.service import SimulatorService

config = SimulatorConfig.from_environment()
if not all(value.startswith('vortex.phase6-test.') for value in (
        config.command_topic, config.report_topic, config.quarantine_topic, config.consumer_group)):
    raise ValueError('Only isolated Phase 6 topics and consumer groups are permitted')
temporary_root = Path(tempfile.gettempdir()).resolve()
application_local = Path(__file__).resolve().parents[1] / '.local'
journal_path = config.journal_path.resolve()
if (not journal_path.is_relative_to(temporary_root)
        or journal_path.is_relative_to(application_local.resolve())):
    raise ValueError('Only isolated Phase 6 temporary journals are permitted')
if len(sys.argv) != 4 or sys.argv[1] not in ('serve', 'intent', 'command'):
    raise ValueError('Expected isolated Phase 6 mode, command path and report path')
if any(not Path(value).resolve().is_relative_to(journal_path.parent) for value in sys.argv[2:]):
    raise ValueError('Only isolated Phase 6 command and report files are permitted')
now = lambda: datetime.fromisoformat('2030-01-15T10:00:00+00:00')
if sys.argv[1] == 'serve':
    adapter = KafkaSimulator(config)
    adapter.service.now = now
    signal.signal(signal.SIGTERM, lambda *_: adapter.stop_requested.set())
    adapter.run_forever()
else:
    command = Path(sys.argv[2]).read_bytes()
    with CommandJournal(config.journal_path) as journal:
        if sys.argv[1] == 'intent':
            journal.mark_in_progress(parse_movement_command_envelope(command))
            # No shutdown hooks: only the durable intent survives this process.
            os._exit(0)
        service = SimulatorService(config, lambda _: None, journal=journal, now=now)
        reports = service.process_command(command)
        Path(sys.argv[3]).write_text(json.dumps([json.loads(r) for r in reports]))
