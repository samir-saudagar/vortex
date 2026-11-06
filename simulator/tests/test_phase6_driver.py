"""The test-only failure driver must reject application resources before any I/O."""
from dataclasses import replace
from pathlib import Path
import runpy
import sys

import pytest

from vortex_simulator.config import SimulatorConfig
import vortex_simulator.command_journal as journal_module
import vortex_simulator.kafka_adapter as kafka_module


DRIVER = Path(__file__).with_name('phase6_driver.py')


@pytest.mark.parametrize('field,value', [
    ('command_topic', 'vortex.equipment-commands'),
    ('report_topic', 'vortex.simulator-reports'),
    ('quarantine_topic', 'vortex.quarantine'),
    ('consumer_group', 'vortex-python-simulator'),
    ('journal_path', Path(__file__).resolve().parents[1] / '.local' / 'command-journal.jsonl'),
])
def test_phase6_driver_rejects_application_resources_before_io(
        simulator_config, tmp_path, monkeypatch, field, value):
    prefix = 'vortex.phase6-test.guard'
    config = replace(simulator_config, command_topic=prefix + '.commands',
                     report_topic=prefix + '.reports', quarantine_topic=prefix + '.quarantine',
                     consumer_group=prefix + '-python')
    config = replace(config, **{field: value})
    monkeypatch.setattr(SimulatorConfig, 'from_environment', classmethod(lambda cls: config))
    monkeypatch.setattr(sys, 'argv', [str(DRIVER), 'command', str(tmp_path / 'command.json'),
                                    str(tmp_path / 'reports.json')])
    (tmp_path / 'command.json').write_text('{}')

    def forbidden_io(*args, **kwargs):
        pytest.fail('Unsafe driver configuration reached journal or Kafka I/O')

    monkeypatch.setattr(journal_module, 'CommandJournal', forbidden_io)
    monkeypatch.setattr(kafka_module, 'KafkaSimulator', forbidden_io)
    with pytest.raises(ValueError, match='isolated Phase 6'):
        runpy.run_path(str(DRIVER), run_name='__main__')
