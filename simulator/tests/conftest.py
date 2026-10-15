from dataclasses import replace
import json

import pytest

from vortex_simulator.config import DEFAULT_EQUIPMENT, DEFAULT_SCANNERS, SimulatorConfig
from vortex_simulator.models import EquipmentSnapshot, SimulationConfig
from vortex_simulator.scenarios import FailureMode


@pytest.fixture
def simulator_config(tmp_path):
    return SimulatorConfig(
        bootstrap_servers="localhost:9092", command_topic="vortex.equipment-commands",
        report_topic="vortex.simulator-reports", quarantine_topic="vortex.quarantine",
        consumer_group="vortex-python-simulator-test", journal_path=tmp_path / "command-journal.jsonl",
        simulation=SimulationConfig(), failure_mode=FailureMode.SUCCESS,
        equipment={key: EquipmentSnapshot(key, status) for key, status in DEFAULT_EQUIPMENT.items()},
        scanners_by_node=dict(DEFAULT_SCANNERS),
    )


@pytest.fixture
def command_document():
    return {
        "eventId": "d3ea48b1-d9ad-4fea-b09c-9421c8f4865f", "eventType": "EquipmentMovementCommand",
        "eventVersion": 1, "occurredAt": "2030-01-15T10:15:30Z", "producer": "movement-orchestration",
        "aggregateType": "BAG", "aggregateId": "e57ed129-6923-4ec0-8ea8-7e7ce4dd1ad0",
        "correlationId": "b8b2944b-7f62-45d3-98f1-1d1f5b406f4a",
        "causationId": "61fce2ca-1d1c-4ce6-9d06-0aa49f84344d",
        "payload": {
            "commandId": "d3ea48b1-d9ad-4fea-b09c-9421c8f4865f",
            "bagId": "e57ed129-6923-4ec0-8ea8-7e7ce4dd1ad0",
            "routeId": "e2c4c35b-73b3-4ac6-a7ba-73eacaa64e2c", "routeStepNo": 1,
            "fromNodeId": "checkin-main", "toNodeId": "junction-west", "equipmentId": "belt-west",
            "attempt": 1, "issuedAt": "2030-01-15T10:15:30Z", "deadlineAt": "2030-01-15T10:16:00Z",
        },
    }


@pytest.fixture
def command_bytes(command_document):
    return json.dumps(command_document, separators=(",", ":")).encode()
