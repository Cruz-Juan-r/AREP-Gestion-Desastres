"""Field device simulator (river gauges, rain gauges, accelerometers, drones).

The hardware is simulated; the protocol is real. In ``mqtt`` mode readings
are published to the broker exactly as a field gateway would; in ``http`` mode
each device posts directly to the cloud (the cloud-only baseline).

The scenario is reproducible (fixed seed) and plants anomaly *episodes* whose
start time is recorded as ground truth, so detection quality (precision,
recall) and event-to-alert latency can be measured objectively.

    python -m simulator.simulator --mode mqtt --duration 60
"""
from __future__ import annotations

import argparse
import asyncio
import json
import math
import random
import time
from dataclasses import asdict, dataclass, field

from common.models import Telemetry, now_ms

# Fictional municipality around Mocoa (Putumayo), only for plausible coordinates.
CENTER = (1.1480, -76.6460)

DEVICE_MIX = {"river_level": 20, "rainfall": 15, "seismic": 10, "drone": 5}
UNITS = {"river_level": "m", "rainfall": "mm/h", "seismic": "g", "drone": "persons"}

# kind of episode -> (sensor type, rule the detector should use)
EPISODE_KINDS = [
    ("river_overflow", "river_level"),   # crosses the critical threshold
    ("river_surge", "river_level"),      # abrupt rise still below thresholds (z-score)
    ("heavy_rain", "rainfall"),
    ("ground_motion", "seismic"),
    ("persons_detected", "drone"),
]


@dataclass
class Device:
    device_id: str
    sensor_type: str
    lat: float
    lon: float
    base: float
    noise: float
    phase: float
    offset_s: float
    seq: int = 0


@dataclass
class Episode:
    episode_id: int
    kind: str
    device_id: str
    start_s: float
    duration_s: float
    magnitude: float
    first_ts_ms: int | None = None      # filled when the first anomalous reading is sent
    readings: list[str] = field(default_factory=list)


class Scenario:
    def __init__(self, duration_s: float, rate_hz: float = 1.0, seed: int = 42,
                 device_scale: int = 1, episodes: int = 24, warmup_s: float = 25.0,
                 episode_s: float = 6.0):
        self.rng = random.Random(seed)
        self.duration_s = duration_s
        self.rate_hz = rate_hz
        self.devices: list[Device] = []
        for st, n in DEVICE_MIX.items():
            for i in range(n * device_scale):
                self.devices.append(self._make_device(st, i))
        self.by_id = {d.device_id: d for d in self.devices}
        self.episodes = self._plant(episodes, warmup_s, episode_s)

    def _make_device(self, st: str, i: int) -> Device:
        r = self.rng
        lat = CENTER[0] + r.uniform(-0.08, 0.08)
        lon = CENTER[1] + r.uniform(-0.08, 0.08)
        base, noise = {
            "river_level": (r.uniform(1.6, 2.4), 0.03),
            "rainfall": (r.uniform(2.0, 10.0), 0.8),
            "seismic": (0.004, 0.0015),
            "drone": (0.0, 0.0),
        }[st]
        return Device(f"{st}-{i:03d}", st, round(lat, 5), round(lon, 5), base, noise,
                      r.uniform(0, 2 * math.pi), r.uniform(0, 1 / self.rate_hz))

    def _plant(self, n: int, warmup_s: float, ep_s: float) -> list[Episode]:
        """Spread episodes evenly over the run, each on a different device."""
        span_end = self.duration_s - ep_s - 4
        if n == 0 or span_end <= warmup_s:
            return []
        step = (span_end - warmup_s) / n
        used: set[str] = set()
        eps = []
        for k in range(n):
            kind, st = EPISODE_KINDS[k % len(EPISODE_KINDS)]
            candidates = [d for d in self.devices if d.sensor_type == st and d.device_id not in used]
            if not candidates:
                continue
            dev = self.rng.choice(candidates)
            used.add(dev.device_id)
            mag = {
                "river_overflow": self.rng.uniform(4.6, 5.2),
                "river_surge": self.rng.uniform(0.6, 0.9),
                "heavy_rain": self.rng.uniform(55, 85),
                "ground_motion": self.rng.uniform(0.11, 0.30),
                "persons_detected": float(self.rng.randint(1, 4)),
            }[kind]
            start = warmup_s + k * step + self.rng.uniform(0, step * 0.5)
            eps.append(Episode(k, kind, dev.device_id, round(start, 2), ep_s, round(mag, 3)))
        return eps

    def value(self, d: Device, t: float) -> tuple[float, Episode | None]:
        r = self.rng
        v = d.base + 0.02 * d.base * math.sin(t / 30 + d.phase) + r.gauss(0, d.noise)
        if d.sensor_type == "seismic":
            v = abs(v)
        if d.sensor_type == "drone":
            v = 0.0
        for ep in self.episodes:
            if ep.device_id == d.device_id and ep.start_s <= t < ep.start_s + ep.duration_s:
                if ep.kind == "river_overflow":
                    v = ep.magnitude + r.gauss(0, 0.05)
                elif ep.kind == "river_surge":
                    v = d.base + ep.magnitude + r.gauss(0, d.noise)
                elif ep.kind == "heavy_rain":
                    v = ep.magnitude + r.gauss(0, 2)
                elif ep.kind == "ground_motion":
                    v = ep.magnitude * r.uniform(0.9, 1.1)
                elif ep.kind == "persons_detected":
                    v = ep.magnitude
                return round(v, 4), ep
        return round(max(v, 0.0), 4), None

    def schedule(self):
        """Yield (t_s, device) in time order for the whole run."""
        period = 1 / self.rate_hz
        events = []
        for d in self.devices:
            t = d.offset_s
            while t < self.duration_s:
                events.append((t, d.device_id))
                t += period
        events.sort()
        for t, dev_id in events:
            yield t, self.by_id[dev_id]

    def reading(self, d: Device, t: float) -> tuple[Telemetry, Episode | None]:
        v, ep = self.value(d, t)
        d.seq += 1
        msg = Telemetry(device_id=d.device_id, sensor_type=d.sensor_type, value=v,
                        unit=UNITS[d.sensor_type], lat=d.lat, lon=d.lon, seq=d.seq, ts_ms=now_ms())
        if ep is not None:
            if ep.first_ts_ms is None:
                ep.first_ts_ms = msg.ts_ms
            ep.readings.append(msg.event_id)
        return msg, ep

    def ground_truth(self) -> list[dict]:
        return [asdict(e) for e in self.episodes]


@dataclass
class RunLog:
    mode: str
    sent: list[dict] = field(default_factory=list)          # event_id, device_id, ts_ms, ok
    alerts: list[dict] = field(default_factory=list)        # as received by local responders
    send_failures: int = 0
    started_ms: int = 0
    finished_ms: int = 0


# ---------------------------------------------------------------- MQTT mode
async def run_mqtt(sc: Scenario, host: str, port: int, on_start=None) -> RunLog:
    import paho.mqtt.client as mqtt

    log = RunLog("edge")
    pub = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=f"gateway-{random.randint(0, 1 << 30)}")
    responders = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2,
                             client_id=f"responders-{random.randint(0, 1 << 30)}")

    def on_alert(client, userdata, msg):
        a = json.loads(msg.payload)
        a["received_ms"] = now_ms()
        log.alerts.append(a)

    responders.on_message = on_alert
    responders.on_connect = lambda c, u, f, rc, p: c.subscribe("alerts/#", qos=1)
    for c in (pub, responders):
        c.connect(host, port, keepalive=30)
        c.loop_start()
    await asyncio.sleep(1.0)

    t0 = time.monotonic()
    log.started_ms = now_ms()
    if on_start:
        on_start(t0)
    for t, dev in sc.schedule():
        wait = t0 + t - time.monotonic()
        if wait > 0:
            await asyncio.sleep(wait)
        msg, _ = sc.reading(dev, t)
        info = pub.publish(f"sensors/{dev.sensor_type}/{dev.device_id}", msg.model_dump_json(), qos=1)
        ok = info.rc == mqtt.MQTT_ERR_SUCCESS
        log.send_failures += 0 if ok else 1
        log.sent.append({"event_id": msg.event_id, "device_id": dev.device_id, "ts_ms": msg.ts_ms, "ok": ok})
    log.finished_ms = now_ms()
    await asyncio.sleep(2.0)                      # let the last alerts arrive
    for c in (pub, responders):
        c.loop_stop()
        c.disconnect()
    return log


# ---------------------------------------------------------------- HTTP mode
async def run_http(sc: Scenario, cloud_url: str, timeout_s: float = 3.0, on_start=None) -> RunLog:
    import httpx

    log = RunLog("cloud")
    url = cloud_url.rstrip("/") + "/api/v1/ingest"
    limits = httpx.Limits(max_connections=400, max_keepalive_connections=400)
    async with httpx.AsyncClient(timeout=timeout_s, limits=limits) as client:
        tasks = []

        async def send(msg: Telemetry, rec: dict):
            try:
                r = await client.post(url, content=msg.model_dump_json(),
                                      headers={"content-type": "application/json"})
                r.raise_for_status()
                body = r.json()
                if body.get("alert"):
                    a = body["alert"]
                    a["received_ms"] = now_ms()
                    log.alerts.append(a)
            except Exception:
                rec["ok"] = False
                log.send_failures += 1

        t0 = time.monotonic()
        log.started_ms = now_ms()
        if on_start:
            on_start(t0)
        for t, dev in sc.schedule():
            wait = t0 + t - time.monotonic()
            if wait > 0:
                await asyncio.sleep(wait)
            msg, _ = sc.reading(dev, t)
            rec = {"event_id": msg.event_id, "device_id": dev.device_id, "ts_ms": msg.ts_ms, "ok": True}
            log.sent.append(rec)
            tasks.append(asyncio.create_task(send(msg, rec)))
        log.finished_ms = now_ms()
        await asyncio.gather(*tasks)
    return log


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", choices=["mqtt", "http"], default="mqtt")
    ap.add_argument("--duration", type=float, default=60)
    ap.add_argument("--rate-hz", type=float, default=1.0)
    ap.add_argument("--mqtt-host", default="localhost")
    ap.add_argument("--mqtt-port", type=int, default=1883)
    ap.add_argument("--cloud-url", default="http://localhost:9000")
    ap.add_argument("--seed", type=int, default=42)
    a = ap.parse_args()
    sc = Scenario(a.duration, a.rate_hz, a.seed)
    if a.mode == "mqtt":
        log = asyncio.run(run_mqtt(sc, a.mqtt_host, a.mqtt_port))
    else:
        log = asyncio.run(run_http(sc, a.cloud_url))
    print(json.dumps({"sent": len(log.sent), "failures": log.send_failures,
                      "alerts_received": len(log.alerts), "episodes": len(sc.episodes)}, indent=2))


if __name__ == "__main__":
    main()
