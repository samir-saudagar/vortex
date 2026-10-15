"""Deterministic simulation scenario choices; no I/O or sleeping occurs here."""

from enum import Enum


class FailureMode(str, Enum):
    SUCCESS = "SUCCESS"
    REJECT = "REJECT"
    BLOCKED_EQUIPMENT = "BLOCKED_EQUIPMENT"
    MOVEMENT_FAILURE = "MOVEMENT_FAILURE"
    MISSING_OBSERVATION = "MISSING_OBSERVATION"

