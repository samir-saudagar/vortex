"""Container-only probes must match the locked client API and remain read-only."""
from pathlib import Path
import runpy

import fcntl
from kafka import KafkaConsumer
import kafka
import pytest
from vortex_simulator.config import SimulatorConfig

PROBE = Path(__file__).resolve().parents[2] / 'docker/simulator/healthcheck.py'


def test_container_health_uses_supported_options_without_joining_a_group(simulator_config, monkeypatch):
    simulator_config.journal_path.touch()
    monkeypatch.setattr(SimulatorConfig, 'from_environment', classmethod(lambda cls: simulator_config))
    monkeypatch.setattr(fcntl, 'flock', lambda *args: (_ for _ in ()).throw(BlockingIOError()))
    calls = []

    def consumer(**options):
        assert not set(options).difference(KafkaConsumer.DEFAULT_CONFIG), 'Unsupported locked Kafka client option'
        assert options['group_id'] is None and options['enable_auto_commit'] is False
        assert options['request_timeout_ms'] > options.get('session_timeout_ms', 45000)
        class ProbeConsumer:
            def partitions_for_topic(self, topic):
                assert topic == simulator_config.command_topic
                return {0, 1, 2}
            def close(self, *, autocommit):
                assert autocommit is False
                calls.append('closed')
        return ProbeConsumer()

    monkeypatch.setattr(kafka, 'KafkaConsumer', consumer)
    runpy.run_path(str(PROBE), run_name='__main__')
    assert calls == ['closed']


def test_container_health_refuses_unlocked_journal_before_connecting(simulator_config, monkeypatch):
    simulator_config.journal_path.touch()
    monkeypatch.setattr(SimulatorConfig, 'from_environment', classmethod(lambda cls: simulator_config))
    monkeypatch.setattr(fcntl, 'flock', lambda *args: None)
    monkeypatch.setattr(kafka, 'KafkaConsumer', lambda **kwargs: pytest.fail('Unexpected broker I/O'))
    with pytest.raises(SystemExit, match='does not hold'):
        runpy.run_path(str(PROBE), run_name='__main__')
