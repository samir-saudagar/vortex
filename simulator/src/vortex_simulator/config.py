"""Environment-based local simulator configuration; no secrets or database access."""

from __future__ import annotations

from dataclasses import dataclass
import json
import os
from pathlib import Path

from vortex_simulator.models import EquipmentSnapshot, EquipmentStatus, SimulationConfig
from vortex_simulator.scenarios import FailureMode


DEFAULT_EQUIPMENT = {
    "belt-west": EquipmentStatus.AVAILABLE,
    "belt-east": EquipmentStatus.AVAILABLE,
    "belt-blocked": EquipmentStatus.BLOCKED,
    "diverter-lhr": EquipmentStatus.AVAILABLE,
    "diverter-lax": EquipmentStatus.AVAILABLE,
    "scanner-west": EquipmentStatus.AVAILABLE,
    "scanner-east": EquipmentStatus.AVAILABLE,
    "scanner-lhr": EquipmentStatus.AVAILABLE,
    "scanner-lax": EquipmentStatus.AVAILABLE,
}
DEFAULT_SCANNERS = {
    "junction-west": "scanner-west",
    "junction-east": "scanner-east",
    "makeup-lhr": "scanner-lhr",
    "makeup-lax": "scanner-lax",
}


@dataclass(frozen=True, slots=True)
class SimulatorConfig:
    bootstrap_servers: str
    command_topic: str
    report_topic: str
    quarantine_topic: str
    consumer_group: str
    journal_path: Path
    simulation: SimulationConfig
    failure_mode: FailureMode
    equipment: dict[str, EquipmentSnapshot]
    scanners_by_node: dict[str, str]

    @classmethod
    def from_environment(cls) -> "SimulatorConfig":
        root = Path(__file__).resolve().parents[2]
        journal_default = root / ".local" / "command-journal.jsonl"
        statuses = dict(DEFAULT_EQUIPMENT)
        raw_equipment = os.environ.get("VORTEX_EQUIPMENT_STATUS_JSON")
        if raw_equipment:
            document = json.loads(raw_equipment)
            if not isinstance(document, dict):
                raise ValueError("VORTEX_EQUIPMENT_STATUS_JSON must be an object")
            for equipment_id, status in document.items():
                statuses[equipment_id] = EquipmentStatus(status)
        scanner_map = dict(DEFAULT_SCANNERS)
        raw_scanners = os.environ.get("VORTEX_SCANNERS_JSON")
        if raw_scanners:
            document = json.loads(raw_scanners)
            if not isinstance(document, dict):
                raise ValueError("VORTEX_SCANNERS_JSON must be an object")
            scanner_map.update({str(node): str(scanner) for node, scanner in document.items()})
        delay = float(os.environ.get("VORTEX_SIMULATOR_DELAY_SECONDS", "0"))
        return cls(
            bootstrap_servers=os.environ.get("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
            command_topic=os.environ.get("VORTEX_COMMAND_TOPIC", "vortex.equipment-commands"),
            report_topic=os.environ.get("VORTEX_REPORT_TOPIC", "vortex.simulator-reports"),
            quarantine_topic=os.environ.get("VORTEX_QUARANTINE_TOPIC", "vortex.quarantine"),
            consumer_group=os.environ.get("VORTEX_SIMULATOR_GROUP", "vortex-python-simulator"),
            journal_path=Path(os.environ.get("VORTEX_COMMAND_JOURNAL", str(journal_default))),
            simulation=SimulationConfig(delay_seconds=delay),
            failure_mode=FailureMode(os.environ.get("VORTEX_FAILURE_MODE", "SUCCESS")),
            equipment={key: EquipmentSnapshot(key, status) for key, status in statuses.items()},
            scanners_by_node=scanner_map,
        )
