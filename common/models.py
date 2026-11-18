"""Data contracts shared by the edge node, the cloud API and the simulator.

These Pydantic models are the executable version of the JSON schemas in
``contracts/``. Every message that crosses a process boundary uses them.
"""
from __future__ import annotations

import time
import uuid
from typing import Literal, Optional

from pydantic import BaseModel, Field

SensorType = Literal["river_level", "rainfall", "seismic", "drone"]
Severity = Literal["warning", "critical"]
Rule = Literal["threshold", "zscore"]
Source = Literal["edge", "cloud"]


def now_ms() -> int:
    return int(time.time() * 1000)


class Telemetry(BaseModel):
    """One reading published by a field device (MQTT topic sensors/{type}/{device})."""

    event_id: str = Field(default_factory=lambda: str(uuid.uuid4()),
                          description="Globally unique id; idempotency key end to end")
    device_id: str
    sensor_type: SensorType
    value: float
    unit: str
    lat: float
    lon: float
    seq: int = Field(ge=0, description="Per-device sequence number")
    ts_ms: int = Field(description="Capture time at the device, epoch milliseconds")


class Alert(BaseModel):
    """Alert produced by the detector (MQTT topic alerts/{severity}/{device})."""

    alert_id: str = Field(default_factory=lambda: str(uuid.uuid4()))
    event_id: str
    device_id: str
    sensor_type: SensorType
    severity: Severity
    rule: Rule
    value: float
    score: float = Field(description="Threshold ratio or |z| that triggered the alert")
    lat: float
    lon: float
    ts_event_ms: int
    ts_alert_ms: int
    source: Source


class SyncBatch(BaseModel):
    """Edge -> cloud synchronisation request (POST /api/v1/sync)."""

    node_id: str
    batch_id: str = Field(default_factory=lambda: str(uuid.uuid4()))
    events: list[Telemetry] = []
    alerts: list[Alert] = []


class SyncAck(BaseModel):
    batch_id: str
    events_accepted: int
    events_duplicated: int
    alerts_accepted: int
    alerts_duplicated: int


class IngestResponse(BaseModel):
    """Cloud-only baseline: the device sends a reading and gets the alert back."""

    event_id: str
    duplicated: bool
    alert: Optional[Alert] = None
