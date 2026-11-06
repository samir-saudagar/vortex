"""Read-only broker metadata and journal-lock probe; never joins a consumer group."""
import fcntl
from kafka import KafkaConsumer
from vortex_simulator.config import SimulatorConfig

config = SimulatorConfig.from_environment()
with config.journal_path.open('rb') as journal:
    try:
        fcntl.flock(journal, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        pass  # The main simulator owns the journal lock.
    else:
        raise SystemExit('Simulator does not hold its command journal lock')
consumer = KafkaConsumer(bootstrap_servers=config.bootstrap_servers.split(','),
                         group_id=None, enable_auto_commit=False,
                         bootstrap_timeout_ms=3000, request_timeout_ms=5000,
                         session_timeout_ms=4000, heartbeat_interval_ms=1000)
try:
    if not consumer.partitions_for_topic(config.command_topic):
        raise SystemExit('Command topic is unavailable')
finally:
    consumer.close(autocommit=False)

