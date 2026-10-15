"""Entry point for the separate Python Kafka equipment simulator."""

from __future__ import annotations

import argparse
import logging


def main() -> int:
    parser = argparse.ArgumentParser(description="VORTEX equipment simulator")
    parser.add_argument("--version", action="version", version="vortex-simulator 0.1.0")
    parser.parse_args()
    from vortex_simulator.kafka_adapter import main as run_kafka_simulator

    logging.getLogger(__name__).info("Starting the VORTEX simulator; no database connection is configured")
    return run_kafka_simulator()


if __name__ == "__main__":
    raise SystemExit(main())
