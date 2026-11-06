#!/usr/bin/env python3
"""Default/full profile acceptance on fresh, isolated Compose projects.

Never deletes volumes; stops its own stack on exit. No development-stack writes.
Python stdlib only; --default-only also runs the existing Bash developer script.
"""
import base64
import argparse
from contextlib import ExitStack
import hashlib
import json
import os
from pathlib import Path
import select
import socket
import struct
import subprocess
import tempfile
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]


def eventually(check, seconds=25):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        value = check()
        if value:
            return value
        time.sleep(0.1)  # Bounded observation polling, never simulated movement timing.
    raise AssertionError('Timed out waiting for authoritative state')


class Stomp:
    """Small RFC6455/STOMP protocol probe, not browser UI automation."""
    def __init__(self, port):
        self.sock = socket.create_connection(('127.0.0.1', port), timeout=15)
        self.buffer = b''
        key = base64.b64encode(os.urandom(16)).decode()
        self.sock.sendall((f'GET /ws HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\n'
                           f'Origin: http://127.0.0.1:{port}\r\nUpgrade: websocket\r\n'
                           f'Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\n'
                           'Sec-WebSocket-Version: 13\r\nSec-WebSocket-Protocol: v12.stomp\r\n\r\n').encode())
        response = b''
        while b'\r\n\r\n' not in response:
            response += self.sock.recv(4096)
        headers, self.buffer = response.split(b'\r\n\r\n', 1)
        assert headers.startswith(b'HTTP/1.1 101'), headers
        accept = base64.b64encode(hashlib.sha1((key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest())
        assert accept.lower() in headers.lower()
        self.send('CONNECT\naccept-version:1.2\nhost:localhost\nheart-beat:0,0\n\n\0')
        assert 'CONNECTED' in self.receive()

    def take(self, size):
        while len(self.buffer) < size:
            block = self.sock.recv(65536)
            if not block:
                raise AssertionError('WebSocket closed before expected STOMP frame')
            self.buffer += block
        result, self.buffer = self.buffer[:size], self.buffer[size:]
        return result

    def send(self, message, opcode=1):
        data = message.encode() if isinstance(message, str) else message
        header = bytes([0x80 | opcode])
        if len(data) < 126:
            header += bytes([0x80 | len(data)])
        else:
            header += bytes([0x80 | 126]) + struct.pack('!H', len(data))
        mask = os.urandom(4)
        self.sock.sendall(header + mask + bytes(byte ^ mask[i % 4] for i, byte in enumerate(data)))

    def receive(self):
        message = b''
        while True:
            first, second = self.take(2)
            length = second & 127
            if length == 126:
                length = struct.unpack('!H', self.take(2))[0]
            elif length == 127:
                length = struct.unpack('!Q', self.take(8))[0]
            assert length <= 2 * 1024 * 1024
            mask = self.take(4) if second & 128 else None
            data = self.take(length)
            if mask:
                data = bytes(byte ^ mask[i % 4] for i, byte in enumerate(data))
            opcode = first & 15
            if opcode == 9:
                self.send(data, 10)
                continue
            assert opcode != 8, 'WebSocket closed'
            if opcode in (0, 1, 2):
                message += data
                if first & 0x80:
                    return message.decode()

    def subscribe(self, bag):
        # Spring's simple broker does not support subscription receipts.
        self.send(f'SUBSCRIBE\nid:bag\ndestination:/topic/bags/{bag}\nack:auto\n\n\0')

    def close(self):
        self.sock.close()


class Deployment:
    def __init__(self, full=True):
        self.project = 'vortex-docker-test-' + uuid4().hex[:12]
        self.directory = Path(tempfile.mkdtemp(prefix=self.project + '-'))
        # Reserve distinct ports together; full acceptance prefers the documented URL.
        with ExitStack() as stack:
            ports = []
            for preferred in (8088 if full else 0, 0, 0):
                listener = stack.enter_context(socket.socket())
                try:
                    listener.bind(('127.0.0.1', preferred))
                except OSError:
                    listener.bind(('127.0.0.1', 0))
                ports.append(listener.getsockname()[1])
        self.port, self.postgres_port, self.kafka_port = ports
        self.env = dict(os.environ, VORTEX_HTTP_PORT=str(self.port),
                        VORTEX_POSTGRES_PORT=str(self.postgres_port), VORTEX_KAFKA_PORT=str(self.kafka_port),
                        POSTGRES_DB='vortex', POSTGRES_USER='vortex', POSTGRES_PASSWORD='vortex-profile-test-' + uuid4().hex,
                        COMPOSE_PROFILES='', VORTEX_FULL_FAILURE_MODE='SUCCESS', VORTEX_FULL_SIMULATOR_DELAY='0')
        self.base = ['docker', 'compose'] + (['--profile', 'full'] if full else [])
        self.base += ['-p', self.project, '-f', str(ROOT / 'compose.yaml')]
        self.results = {'project': self.project, 'mode': 'full' if full else 'default',
                        'url': f'http://127.0.0.1:{self.port}', 'checks': {}}
        print('Docker acceptance evidence:', self.directory, flush=True)

    def compose(self, *args, data=None, timeout=900):
        assert self.project.startswith('vortex-docker-test-')
        completed = subprocess.run(self.base + list(args), input=data, text=True, capture_output=True,
                                   env=self.env, cwd=ROOT, timeout=timeout)
        with (self.directory / 'compose.log').open('a') as log:
            log.write('\n$ ' + ' '.join(self.base + list(args[:5])) + '\n')
            log.write(completed.stdout + completed.stderr)
        if completed.returncode:
            raise AssertionError(f'Compose {args[:3]} failed: {completed.stderr[-2500:]}')
        return completed.stdout

    def verify_isolation(self):
        ids = self.compose('ps', '--all', '-q').split()
        assert ids
        info = json.loads(subprocess.check_output(['docker', 'inspect', *ids], text=True))
        assert all(item['Config']['Labels']['com.docker.compose.project'] == self.project for item in info)
        for item in info:
            for mount in item['Mounts']:
                if mount['Type'] == 'volume':
                    assert mount['Name'].startswith(self.project + '_'), mount

    def sql(self, statement):
        self.verify_isolation()
        return self.compose('exec', '-T', 'postgres', 'psql', '-U', 'vortex', '-d', 'vortex',
                            '-qAt', '-v', 'ON_ERROR_STOP=1', data=statement).strip()

    def api(self, path, body=None, key=None, status=200, origin=None):
        headers = {'Origin': origin or self.results['url']}
        if body is not None:
            headers['Content-Type'] = 'application/json'
        if key:
            headers['Idempotency-Key'] = key
        request = Request(self.results['url'] + path, headers=headers,
                          data=None if body is None else json.dumps(body).encode())
        try:
            response = urlopen(request, timeout=15)
        except HTTPError as error:
            response = error
        content = response.read()
        assert response.status == status, (path, response.status, content)
        return (json.loads(content) if 'json' in response.headers.get('Content-Type', '')
                else content.decode()) if content else None

    def checkin(self, suffix, flight='FL-204-2030-01-15'):
        result = self.api('/api/bags/check-in', {'bagTag': 'DOCKER-' + suffix + '-' + uuid4().hex[:10],
                                               'flightId': flight}, uuid4().hex, 202)
        assert result['state'] == 'CHECKED_IN'
        return result['bagId']

    def bag(self, bag):
        return self.api('/api/bags/' + bag)

    def state(self, bag, state):
        return eventually(lambda: (value if (value := self.bag(bag))['state'] == state else None))

    def command(self, bag):
        text = self.sql("SELECT envelope::text FROM vortex.outbox WHERE aggregate_id='" + bag +
                        "' AND event_type='EquipmentMovementCommand' ORDER BY aggregate_sequence DESC LIMIT 1;")
        return json.loads(text)

    def python(self, code, document=None, oneoff=False):
        args = ('run', '--rm', '--no-deps', '-T', '--entrypoint', 'python', 'simulator', '-c', code) if oneoff else (
            'exec', '-T', 'simulator', 'python', '-c', code)
        return json.loads(self.compose(*args, data=json.dumps(document or {}), timeout=90).strip())

    def journal(self):
        return self.python("from pathlib import Path; import hashlib,json,os; p=Path(os.environ['VORTEX_COMMAND_JOURNAL']); "
                           "print(json.dumps({'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'lines':len(p.read_bytes().splitlines())}))")

    def simulator_mode(self, mode):
        self.env['VORTEX_FULL_FAILURE_MODE'] = mode
        self.compose('up', '-d', '--no-deps', 'simulator')

    def complete(self, bag):
        current = self.state(bag, 'AT_MAKEUP')
        route = self.api('/api/bags/' + bag + '/route')
        movement = self.api('/api/bags/' + bag + '/movement')
        history = self.api('/api/bags/' + bag + '/history')['items']
        assert current['confirmedNodeId'] == 'makeup-lhr'
        assert route['status'] == 'COMPLETE'
        accepted = [item for item in movement['observations'] if item['decision'] == 'ACCEPTED']
        assert [item['node_id'] for item in sorted(accepted, key=lambda x: x['route_step_no'])][-1] == 'makeup-lhr'
        final = [item for item in accepted if item['node_id'] == 'makeup-lhr']
        assert len(final) == 1 and final[0]['scanner_id'] == 'scanner-lhr'
        assert final[0]['route_id'] == route['routeId']
        assert any(item['command_id'] == final[0]['command_id'] and item['status'] == 'SUCCEEDED'
                   for item in movement['commands'])
        assert sum(item['currentState'] == 'AT_MAKEUP' for item in history) == 1
        self.results['checks'][bag] = {'bag': current, 'route': route, 'movement': movement, 'history': history}
        return current

    def run(self):
        assert not self.compose('ps', '--all', '-q').strip()
        self.compose('up', '--build', '-d', '--wait', '--wait-timeout', '600')
        self.verify_isolation()
        assert self.sql('SELECT count(*) FROM vortex.bags;') == '0'
        assert self.sql('SELECT count(*) FROM vortex.flyway_schema_history WHERE success AND version IS NOT NULL;') == '5'
        assert self.sql('SELECT count(*) FROM vortex.network_edges;') == '5'
        assert len(self.api('/api/flights')['items']) == 3
        with urlopen(self.results['url'], timeout=10) as page:
            assert page.status == 200 and b'<div id="root">' in page.read()
        assert self.api('/actuator/health/readiness')['status'] == 'UP'
        self.api('/api/bags/summary', status=403, origin='http://untrusted.invalid')
        self.results['checks']['fresh_start'] = 'PASS: empty DB, five migrations, seed graph/flights, readiness and frontend'

        self.compose('stop', 'simulator')
        first = self.checkin('SUCCESS')
        pending = self.state(first, 'MOVEMENT_PENDING')
        assert pending['confirmedNodeId'] == 'checkin-main'
        route = self.api('/api/bags/' + first + '/route')
        assert [step['equipmentId'] for step in route['steps']] == ['belt-west', 'diverter-lhr']
        assert route['totalCost'] == 12
        decision = json.loads(self.sql("SELECT decision_summary::text FROM vortex.route_plans WHERE route_id='" + route['routeId'] + "';"))
        assert any(item['edgeId'] == 'edge-checkin-blocked' for item in decision['excludedEdges'])
        live = Stomp(self.port)
        try:
            live.subscribe(first)
            committed = json.loads(self.sql("SELECT envelope::text FROM vortex.outbox WHERE aggregate_id='" + first +
                                            "' AND event_type='BagStateChanged' AND envelope->'payload'->>'currentState'='MOVEMENT_PENDING';"))
            deadline = time.monotonic() + 15
            while True:
                # Same harmless committed-notification replay as Phase4KafkaIntegrationTest.
                self.python(NOTIFY, committed, oneoff=True)
                if live.buffer or select.select([live.sock], [], [], 1)[0]:
                    frame = live.receive()
                    assert 'MESSAGE\n' in frame and first in frame
                    break
                assert time.monotonic() < deadline, 'Subscription was not installed'
            self.compose('up', '-d', '--no-deps', 'simulator')
            seen = []
            while True:
                frame = live.receive()
                if 'MESSAGE\n' in frame:
                    event = json.loads(frame.split('\n\n', 1)[1].split('\0', 1)[0])
                    seen.append(event)
                    if event['payload']['currentState'] == 'AT_MAKEUP':
                        assert event['payload']['confirmedNodeId'] == 'makeup-lhr'
                        break
            assert all(event['aggregateId'] == first for event in seen)
            self.results['checks']['live_stomp'] = seen
            self.complete(first)
            live.send(f'SEND\ndestination:/topic/bags/{first}\ncontent-length:2\n\n{{}}\0')
            assert 'ERROR' in live.receive()
        finally:
            live.close()
        self.results['checks']['blocked_parallel_edge'] = {'decision': decision, 'route': route}

        # Redeliver an actual completed command through the real Kafka consumer.
        replay = self.python(REPLAY, self.command(first))
        assert replay['before'] == replay['after']
        assert self.bag(first) == self.results['checks'][first]['bag']
        self.results['checks']['duplicate_command'] = replay

        for suffix, flight, code in [('CANCELLED', 'FL-999-2030-01-15', 'FLIGHT_NOT_ELIGIBLE'),
                                     ('UNKNOWN', 'NONEXISTENT', 'UNKNOWN_FLIGHT')]:
            bag = self.checkin(suffix, flight)
            failed = self.state(bag, 'FLIGHT_VERIFICATION_FAILED')
            assert failed['failureCode'] == code
            self.results['checks'][suffix] = failed

        self.compose('stop', 'simulator')
        self.sql("UPDATE vortex.equipment SET status='BLOCKED' WHERE equipment_id='belt-west';")
        blocked = self.checkin('NO-ROUTE')
        failed = self.state(blocked, 'ROUTING_BLOCKED')
        assert failed['confirmedNodeId'] == 'checkin-main'
        assert self.sql("SELECT count(*) FROM vortex.movement_commands WHERE bag_id='" + blocked + "';") == '0'
        self.results['checks']['no_route'] = failed
        self.sql("UPDATE vortex.equipment SET status='AVAILABLE' WHERE equipment_id='belt-west';")
        key = uuid4().hex
        response = self.api('/api/bags/' + blocked + '/retry', {}, key, 202)
        self.compose('up', '-d', '--no-deps', 'simulator')
        self.complete(blocked)
        assert self.api('/api/bags/' + blocked + '/retry', {}, key, 202) == response

        # A real accepted intermediate scanner, followed by failure on the diverter.
        self.compose('stop', 'simulator')
        moving = self.checkin('ACTIVE-FAILURE')
        self.state(moving, 'MOVEMENT_PENDING')
        first_reports = self.python(SIMULATE, self.command(moving), oneoff=True)
        assert first_reports[-1]['payload']['scannerId'] == 'scanner-west'
        eventually(lambda: self.bag(moving)['confirmedNodeId'] == 'junction-west')
        self.simulator_mode('MOVEMENT_FAILURE')
        failed = self.state(moving, 'MOVEMENT_FAILED')
        assert failed['confirmedNodeId'] == 'junction-west'
        assert failed['failureCode'] == 'SIMULATED_MOVEMENT_FAILURE'
        self.results['checks']['active_failure'] = {'bag': failed, 'first_reports': first_reports}
        self.simulator_mode('SUCCESS')
        assert self.bag(moving)['state'] == 'MOVEMENT_FAILED'  # No automatic journey retry.
        key = uuid4().hex
        response = self.api('/api/bags/' + moving + '/retry', {}, key, 202)
        self.complete(moving)
        assert self.api('/api/bags/' + moving + '/route')['sourceNodeId'] == 'junction-west'
        assert self.api('/api/bags/' + moving + '/retry', {}, key, 202) == response

        # Fsynced intent survives a process death; fresh simulator must fail closed.
        self.compose('stop', 'simulator')
        uncertain = self.checkin('UNCERTAIN')
        self.state(uncertain, 'MOVEMENT_PENDING')
        self.compose('run', '--rm', '--no-deps', '-T', '--entrypoint', 'python', 'simulator', '-c', INTENT,
                     data=json.dumps(self.command(uncertain)))
        self.compose('up', '-d', '--no-deps', 'simulator')
        failed = self.state(uncertain, 'MOVEMENT_FAILED')
        assert failed['failureCode'] == 'SIMULATOR_EXECUTION_UNCERTAIN'
        assert failed['confirmedNodeId'] == 'checkin-main'
        assert self.sql("SELECT count(*) FROM vortex.equipment_observations WHERE bag_id='" + uncertain + "';") == '0'
        assert self.sql("SELECT count(*) FROM vortex.movement_commands WHERE bag_id='" + uncertain + "';") == '1'
        self.results['checks']['uncertain_before_explicit_recovery'] = failed
        self.api('/api/bags/' + uncertain + '/retry', {}, uuid4().hex, 202)
        self.complete(uncertain)

        before = self.journal()
        snapshot = {bag: self.bag(bag) for bag in (first, blocked, moving, uncertain)}
        self.compose('stop')
        self.compose('up', '-d', '--wait', '--wait-timeout', '600')
        assert self.journal() == before
        assert {bag: self.bag(bag) for bag in snapshot} == snapshot
        for bag in snapshot:
            assert self.api('/api/bags/' + bag + '/history')['items'] == self.results['checks'][bag]['history']
        self.results['checks']['persistent_restart'] = before
        self.results['verdict'] = 'PASS'

    def run_default(self):
        """Exercise only the unprofiled dependencies, including host listeners and volumes."""
        assert not self.compose('ps', '--all', '-q').strip()
        self.compose('up', '-d', '--wait', '--wait-timeout', '180')
        self.verify_isolation()
        assert set(self.compose('ps', '--services', '--status', 'running').split()) == {'postgres', 'kafka'}
        assert self.compose('port', 'postgres', '5432').strip() == f'127.0.0.1:{self.postgres_port}'
        assert self.compose('port', 'kafka', '9092').strip() == f'127.0.0.1:{self.kafka_port}'
        with socket.create_connection(('127.0.0.1', self.postgres_port), timeout=5) as postgres:
            postgres.sendall(struct.pack('!ii', 8, 80877103))  # PostgreSQL SSLRequest, no SQL/data changes.
            assert postgres.recv(1) in (b'S', b'N')
        with socket.create_connection(('127.0.0.1', self.kafka_port), timeout=5) as kafka:
            client = b'vortex-profile-test'
            request = struct.pack('!hhiH', 18, 0, 71, len(client)) + client  # ApiVersions v0.
            kafka.sendall(struct.pack('!i', len(request)) + request)
            response = b''
            while len(response) < 4:
                response += kafka.recv(4 - len(response))
            length = struct.unpack('!i', response)[0]
            assert 10 <= length <= 1048576
            response = b''
            while len(response) < length:
                block = kafka.recv(length - len(response))
                assert block
                response += block
            correlation, error, versions = struct.unpack('!ihi', response[:10])
            assert correlation == 71 and error == 0 and versions > 0
        assert self.sql("SELECT to_regnamespace('vortex') IS NULL;") == 't'
        # Run the existing host-development initialization workflow in this project only.
        env = dict(self.env, COMPOSE_PROJECT_NAME=self.project, COMPOSE_FILE=str(ROOT / 'compose.yaml'))
        initialized = subprocess.run(['bash', str(ROOT / 'scripts/dev-start.sh')], cwd=ROOT,
                                     env=env, capture_output=True, text=True, timeout=300)
        (self.directory / 'dev-start.log').write_text(initialized.stdout + initialized.stderr)
        assert initialized.returncode == 0, initialized.stderr[-2000:]
        assert set(self.compose('ps', '--services', '--status', 'running').split()) == {'postgres', 'kafka'}
        marker = uuid4().hex
        self.sql("CREATE TABLE public.profile_probe(value text); INSERT INTO public.profile_probe VALUES ('" + marker + "');")
        topic = 'vortex.profile-test.persistence'
        self.compose('exec', '-T', 'kafka', '/opt/kafka/bin/kafka-topics.sh', '--bootstrap-server', 'kafka:29092',
                     '--create', '--topic', topic, '--partitions', '1', '--replication-factor', '1')
        self.compose('exec', '-T', 'kafka', '/opt/kafka/bin/kafka-console-producer.sh', '--bootstrap-server', 'kafka:29092',
                     '--topic', topic, data=marker + '\n')
        before = self.compose('exec', '-T', 'kafka', '/opt/kafka/bin/kafka-topics.sh', '--bootstrap-server', 'kafka:29092',
                              '--describe', '--topic', topic)
        old_ids = set(self.compose('ps', '--all', '-q').split())
        self.compose('stop')
        self.compose('up', '-d', '--force-recreate', '--wait', '--wait-timeout', '180')
        assert old_ids.isdisjoint(self.compose('ps', '--all', '-q').split())
        assert self.sql('SELECT value FROM public.profile_probe;') == marker
        assert self.compose('exec', '-T', 'kafka', '/opt/kafka/bin/kafka-topics.sh', '--bootstrap-server', 'kafka:29092',
                            '--describe', '--topic', topic) == before
        assert marker in self.compose('exec', '-T', 'kafka', '/opt/kafka/bin/kafka-console-consumer.sh',
                                      '--bootstrap-server', 'kafka:29092', '--topic', topic, '--from-beginning',
                                      '--max-messages', '1', '--timeout-ms', '10000')
        assert set(self.compose('ps', '--services', '--status', 'running').split()) == {'postgres', 'kafka'}
        self.results['checks']['default'] = 'PASS: only postgres/kafka, host protocol connections, existing dev-start topic policy, persisted SQL/Kafka data after container replacement'
        self.results['verdict'] = 'PASS'


SIMULATE = '''
import json,sys
from dataclasses import replace
from kafka import KafkaProducer
from vortex_simulator.config import SimulatorConfig
from vortex_simulator.command_journal import CommandJournal
from vortex_simulator.service import SimulatorService
from vortex_simulator.scenarios import FailureMode
config=replace(SimulatorConfig.from_environment(),failure_mode=FailureMode.SUCCESS)
producer=KafkaProducer(bootstrap_servers=config.bootstrap_servers.split(','),acks='all',enable_idempotence=True)
def publish(raw):
    value=json.loads(raw)
    producer.send(config.report_topic,key=value['aggregateId'].encode(),value=raw).get(timeout=10)
try:
    with CommandJournal(config.journal_path) as journal:
        reports=SimulatorService(config,publish,journal=journal).process_command(json.dumps(json.load(sys.stdin)).encode())
        print(json.dumps([json.loads(row) for row in reports]))
finally: producer.close()
'''

INTENT = '''
import json,sys,os
from vortex_simulator.config import SimulatorConfig
from vortex_simulator.command_journal import CommandJournal
from vortex_simulator.models import parse_movement_command_envelope
config=SimulatorConfig.from_environment()
with CommandJournal(config.journal_path) as journal:
    journal.mark_in_progress(parse_movement_command_envelope(json.dumps(json.load(sys.stdin)).encode()))
    os._exit(0)
'''

REPLAY = '''
import json,sys,time,hashlib
from kafka import KafkaProducer,KafkaConsumer,TopicPartition
from vortex_simulator.config import SimulatorConfig
config=SimulatorConfig.from_environment()
document=json.load(sys.stdin)
digest=lambda:hashlib.sha256(config.journal_path.read_bytes()).hexdigest()
before=digest()
producer=KafkaProducer(bootstrap_servers=config.bootstrap_servers.split(','),acks='all',enable_idempotence=True)
probe=KafkaConsumer(bootstrap_servers=config.bootstrap_servers.split(','),group_id=config.consumer_group,enable_auto_commit=False)
try:
    record=producer.send(config.command_topic,key=document['aggregateId'].encode(),value=json.dumps(document).encode()).get(timeout=10)
    deadline=time.monotonic()+20
    while (probe.committed(TopicPartition(record.topic,record.partition)) or 0)<=record.offset:
        assert time.monotonic()<deadline,'Simulator did not commit duplicate command'
        time.sleep(.1)
    print(json.dumps({'before':before,'after':digest(),'commandId':document['eventId']}))
finally:
    probe.close(autocommit=False)
    producer.close()
'''

NOTIFY = '''
import json,sys
from kafka import KafkaProducer
from vortex_simulator.config import SimulatorConfig
config=SimulatorConfig.from_environment()
document=json.load(sys.stdin)
assert document['eventType']=='BagStateChanged'
producer=KafkaProducer(bootstrap_servers=config.bootstrap_servers.split(','),acks='all',enable_idempotence=True)
try:
    producer.send('vortex.workflow-events',key=document['aggregateId'].encode(),value=json.dumps(document).encode()).get(timeout=10)
    print(json.dumps({'committedEventId':document['eventId']}))
finally:producer.close()
'''


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--default-only', action='store_true', help='Verify infrastructure-only mode in its own disposable project')
    args = parser.parse_args()
    deployment = Deployment(full=not args.default_only)
    try:
        if args.default_only:
            deployment.run_default()
        else:
            deployment.run()
    except BaseException as error:
        deployment.results['verdict'] = 'FAIL'
        deployment.results['error'] = str(error)
        raise
    finally:
        try:
            (deployment.directory / 'services.log').write_text(deployment.compose('logs', '--no-color'))
            deployment.compose('stop')
        finally:
            (deployment.directory / 'results.json').write_text(json.dumps(deployment.results, indent=2) + '\n')
            print('Verdict:', deployment.results.get('verdict'), '; preserved volumes in', deployment.project, flush=True)
