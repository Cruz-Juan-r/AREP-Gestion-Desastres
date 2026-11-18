"""Experiment: edge-first vs cloud-only under a WAN outage.

Hypotheses
  H1  Alert latency at the edge stays below 1 s (p95) and does not degrade
      during the outage; the cloud-only baseline pays the WAN round trip and
      loses every alert during the outage.
  H2  No reading is lost: after the link returns, the cloud holds exactly the
      set of readings the devices produced.
  H3  Resends caused by lost acknowledgements never create duplicates.
  H4  The edge backlog drains after reconnection in bounded time.
  H5  The audit chain stays valid and detects tampering.

Requires the stack running (scripts/run_local_stack.py or docker compose).

    python -m experiment.run_experiment --duration 120 --outage 40 80
"""
from __future__ import annotations

import argparse
import asyncio
import json
import shutil
import sqlite3
import statistics
import threading
import time
from pathlib import Path

import httpx

from common.auditlog import verify
from simulator.simulator import Scenario, run_http, run_mqtt

ROOT = Path(__file__).resolve().parents[1]


def pct(values: list[float], q: float) -> float | None:
    if not values:
        return None
    v = sorted(values)
    k = (len(v) - 1) * q
    f, c = int(k), min(int(k) + 1, len(v) - 1)
    return round(v[f] + (v[c] - v[f]) * (k - f), 1)


class Sampler(threading.Thread):
    """Samples edge backlog, cloud stored events and link state every 0.5 s."""

    def __init__(self, args, with_edge: bool):
        super().__init__(daemon=True)
        self.a = args
        self.with_edge = with_edge
        self.rows: list[dict] = []
        self._stop = threading.Event()

    def run(self):
        with httpx.Client(timeout=2) as c:
            while not self._stop.is_set():
                row = {"ts_ms": int(time.time() * 1000)}
                try:
                    row["cloud_events"] = c.get(f"{self.a.cloud}/api/v1/stats").json()["events_stored"]
                    row["link_up"] = c.get(f"{self.a.wan_control}/state").json()["up"]
                    if self.with_edge:
                        m = c.get(f"{self.a.edge}/metrics").json()
                        row["edge_pending"] = m["store"]["events_pending"]
                        row["edge_total"] = m["store"]["events_total"]
                except Exception:
                    pass
                self.rows.append(row)
                self._stop.wait(0.5)

    def stop(self):
        self._stop.set()
        self.join()


def evaluate_alerts(sc: Scenario, alerts: list[dict], outage_ms: tuple[int, int]) -> dict:
    """Match received alerts to planted episodes (ground truth)."""
    by_event = {}
    for ep in sc.episodes:
        for eid in ep.readings:
            by_event[eid] = ep
    first: dict[int, dict] = {}
    false_pos = 0
    for a in alerts:
        ep = by_event.get(a["event_id"])
        if ep is None:
            false_pos += 1
            continue
        if ep.episode_id not in first or a["received_ms"] < first[ep.episode_id]["received_ms"]:
            first[ep.episode_id] = a
    lat_all, lat_normal, lat_outage, rows = [], [], [], []
    detected_out = total_out = 0
    for ep in sc.episodes:
        if ep.first_ts_ms is None:
            continue
        in_outage = outage_ms[0] <= ep.first_ts_ms <= outage_ms[1]
        total_out += in_outage
        a = first.get(ep.episode_id)
        lat = (a["received_ms"] - ep.first_ts_ms) if a else None
        rows.append({"episode": ep.episode_id, "kind": ep.kind, "device": ep.device_id,
                     "during_outage": in_outage, "detected": a is not None,
                     "rule": a["rule"] if a else None, "latency_ms": lat})
        if a:
            lat_all.append(lat)
            (lat_outage if in_outage else lat_normal).append(lat)
            detected_out += in_outage
    n = len(rows)
    tp = len(alerts) - false_pos
    return {
        "episodes": n,
        "episodes_detected": len(first),
        "recall": round(len(first) / n, 3) if n else None,
        "alerts_received": len(alerts),
        "false_positives": false_pos,
        "precision": round(tp / len(alerts), 3) if alerts else None,
        "episodes_during_outage": total_out,
        "detected_during_outage": detected_out,
        "latency_ms": {"p50": pct(lat_all, .5), "p95": pct(lat_all, .95), "max": max(lat_all, default=None),
                       "mean": round(statistics.mean(lat_all), 1) if lat_all else None},
        "latency_ms_normal": {"p50": pct(lat_normal, .5), "p95": pct(lat_normal, .95)},
        "latency_ms_outage": {"p50": pct(lat_outage, .5), "p95": pct(lat_outage, .95)},
        "latencies": lat_all,
        "per_episode": rows,
    }


async def run_mode(mode: str, a) -> dict:
    async with httpx.AsyncClient(timeout=5) as c:
        await c.post(f"{a.cloud}/admin/reset")
        await c.post(f"{a.cloud}/admin/fault", params={"ack_loss": a.ack_loss if mode == "edge" else 0})
        if mode == "edge":
            await c.post(f"{a.edge}/admin/reset")
        await c.post(f"{a.wan_control}/config", params={"latency_ms": a.latency, "jitter_ms": a.jitter})
        await c.post(f"{a.wan_control}/link", params={"up": 1})

    sc = Scenario(a.duration, a.rate_hz, a.seed)
    sampler = Sampler(a, with_edge=(mode == "edge"))
    sampler.start()
    marks: dict[str, int] = {}

    async def link(up: int, at_s: float, t0: float):
        await asyncio.sleep(max(0, t0 + at_s - time.monotonic()))
        async with httpx.AsyncClient(timeout=5) as c:
            await c.post(f"{a.wan_control}/link", params={"up": up})
        marks["up" if up else "down"] = int(time.time() * 1000)

    tasks = []

    def on_start(t0: float):
        loop = asyncio.get_running_loop()
        if a.outage:
            tasks.append(loop.create_task(link(0, a.outage[0], t0)))
            tasks.append(loop.create_task(link(1, a.outage[1], t0)))

    print(f"[{mode}] running {a.duration:.0f} s scenario "
          f"({len(sc.devices)} devices x {a.rate_hz} Hz, outage {a.outage})")
    if mode == "edge":
        log = await run_mqtt(sc, a.mqtt_host, a.mqtt_port, on_start=on_start)
    else:
        log = await run_http(sc, a.wan_data, on_start=on_start)
    await asyncio.gather(*tasks)

    # ---- wait for convergence (edge backlog drained into the cloud)
    converged_ms = None
    sent_ids = {r["event_id"] for r in log.sent}
    if mode == "edge":
        deadline = time.time() + a.converge_timeout
        async with httpx.AsyncClient(timeout=5) as c:
            while time.time() < deadline:
                m = (await c.get(f"{a.edge}/metrics")).json()
                if m["store"]["events_pending"] == 0 and m["store"]["alerts_pending"] == 0 \
                        and m["store"]["events_total"] >= len(sent_ids):
                    converged_ms = int(time.time() * 1000)
                    break
                await asyncio.sleep(0.2)
    else:
        await asyncio.sleep(2)
    sampler.stop()

    async with httpx.AsyncClient(timeout=30) as c:
        cloud_stats = (await c.get(f"{a.cloud}/api/v1/stats")).json()
        cloud_ids = set((await c.get(f"{a.cloud}/api/v1/events/ids")).json())
        edge_metrics = (await c.get(f"{a.edge}/metrics")).json() if mode == "edge" else None
        audit = (await c.get(f"{a.edge}/audit/verify")).json() if mode == "edge" else None

    outage_ms = (marks.get("down", 0), marks.get("up", 0))
    alerts = evaluate_alerts(sc, log.alerts, outage_ms)

    # backlog drain: from link restored until the backlog is empty (from samples)
    # While live traffic keeps flowing the backlog never stays at 0 (readings wait up
    # to one sync cycle), so "drained" means back to the steady-state level measured
    # before the outage (p95 of the backlog with the link up).
    drain_s = steady = None
    if mode == "edge" and "up" in marks:
        before = [r["edge_pending"] for r in sampler.rows
                  if "edge_pending" in r and r["ts_ms"] < marks["down"]][10:]
        steady = max(1, pct(before, 0.95) or 0)
        after = [r for r in sampler.rows if r["ts_ms"] >= marks["up"] and "edge_pending" in r]
        back = next((r for r in after if r["edge_pending"] <= steady), None)
        if back:
            drain_s = round((back["ts_ms"] - marks["up"]) / 1000, 2)
    max_backlog = max((r.get("edge_pending", 0) for r in sampler.rows), default=0)

    lost = len(sent_ids - cloud_ids)
    res = {
        "mode": mode,
        "readings_sent": len(sent_ids),
        "readings_in_cloud": len(sent_ids & cloud_ids),
        "readings_lost": lost,
        "loss_pct": round(100 * lost / len(sent_ids), 2),
        "send_failures_at_device": log.send_failures,
        "duplicates_stored_in_cloud": cloud_stats["events_stored"] - cloud_stats["events_distinct"],
        "duplicates_rejected_by_idempotency": cloud_stats["events_duplicated"],
        "acks_dropped_injected": cloud_stats["acks_dropped"],
        "max_edge_backlog": max_backlog,
        "backlog_drain_s": drain_s,
        "steady_backlog_p95": steady,
        "converged": (converged_ms is not None) if mode == "edge" else None,
        "outage_window_ms": outage_ms,
        "alerts": alerts,
        "edge_metrics": edge_metrics,
        "audit": audit,
        "timeline": sampler.rows,
        "ground_truth": sc.ground_truth(),
    }
    print(f"[{mode}] lost={lost}/{len(sent_ids)}  recall={alerts['recall']}  "
          f"latency p50/p95={alerts['latency_ms']['p50']}/{alerts['latency_ms']['p95']} ms  "
          f"drain={drain_s}s  dup_rejected={res['duplicates_rejected_by_idempotency']}")
    return res


async def run_stress(a) -> dict:
    """Edge-only throughput: many devices, no outage."""
    async with httpx.AsyncClient(timeout=5) as c:
        await c.post(f"{a.cloud}/admin/reset")
        await c.post(f"{a.cloud}/admin/fault", params={"ack_loss": 0})
        await c.post(f"{a.edge}/admin/reset")
        await c.post(f"{a.wan_control}/link", params={"up": 1})
    sc = Scenario(a.stress_duration, a.stress_rate_hz, a.seed, episodes=12)
    t0 = time.time()
    log = await run_mqtt(sc, a.mqtt_host, a.mqtt_port)
    gen_s = (log.finished_ms - log.started_ms) / 1000
    async with httpx.AsyncClient(timeout=5) as c:
        deadline = time.time() + 60
        while time.time() < deadline:
            m = (await c.get(f"{a.edge}/metrics")).json()
            if m["readings_processed"] >= len(log.sent) and m["store"]["events_pending"] == 0:
                break
            await asyncio.sleep(0.2)
    alerts = evaluate_alerts(sc, log.alerts, (0, 0))
    res = {
        "offered_rate_msg_s": round(len(log.sent) / gen_s, 1),
        "readings_sent": len(log.sent),
        "readings_processed": m["readings_processed"],
        "processing_ms_p50": m["processing_ms_p50"],
        "processing_ms_p95": m["processing_ms_p95"],
        "processing_ms_p99": m["processing_ms_p99"],
        "alert_latency_ms_p95": alerts["latency_ms"]["p95"],
        "recall": alerts["recall"],
        "synced_to_cloud": m["store"]["events_total"] - m["store"]["events_pending"],
        "wall_s": round(time.time() - t0, 1),
    }
    print(f"[stress] {res}")
    return res


def tamper_test(edge_db: Path) -> dict | None:
    """Copies the edge DB, edits one past audit entry and checks verify() catches it."""
    if not edge_db.exists():
        return None
    tmp = ROOT / "results" / "edge_tamper_copy.db"
    src = sqlite3.connect(edge_db)
    dst = sqlite3.connect(tmp)
    src.backup(dst)
    src.close()
    before = verify(dst)
    target = max(1, before["entries"] // 2)
    # e.g. someone "softens" a past record: any byte change must be detected
    dst.execute("UPDATE audit SET detail = replace(detail, '\"critical\"', '\"warning\"') || ' ' "
                "WHERE seq = ?", (target,))
    dst.commit()
    after = verify(dst)
    dst.close()
    tmp.unlink()
    return {"before": before, "tampered_seq": target, "after": after,
            "detected": (not after["valid"]) and after["first_invalid_seq"] == target}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--duration", type=float, default=120)
    ap.add_argument("--outage", type=float, nargs=2, default=[40, 80], metavar=("START", "END"))
    ap.add_argument("--latency", type=float, default=200, help="one-way WAN latency, ms")
    ap.add_argument("--jitter", type=float, default=80)
    ap.add_argument("--ack-loss", type=float, default=0.1)
    ap.add_argument("--rate-hz", type=float, default=1.0)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--converge-timeout", type=float, default=120)
    ap.add_argument("--stress-rate-hz", type=float, default=10.0)
    ap.add_argument("--stress-duration", type=float, default=40)
    ap.add_argument("--skip-stress", action="store_true")
    ap.add_argument("--edge", default="http://127.0.0.1:8080")
    ap.add_argument("--cloud", default="http://127.0.0.1:8000")
    ap.add_argument("--wan-data", default="http://127.0.0.1:9000")
    ap.add_argument("--wan-control", default="http://127.0.0.1:9100")
    ap.add_argument("--mqtt-host", default="127.0.0.1")
    ap.add_argument("--mqtt-port", type=int, default=1883)
    ap.add_argument("--edge-db", default=str(ROOT / "data" / "edge.db"))
    ap.add_argument("--out", default=str(ROOT / "results"))
    a = ap.parse_args()

    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    results = {"config": {k: v for k, v in vars(a).items()}}
    results["cloud_only"] = asyncio.run(run_mode("cloud", a))
    results["edge"] = asyncio.run(run_mode("edge", a))
    results["tamper_test"] = tamper_test(Path(a.edge_db))
    if not a.skip_stress:
        results["stress"] = asyncio.run(run_stress(a))
    (out / "results.json").write_text(json.dumps(results, indent=2))
    print(f"Results written to {out / 'results.json'}")
    from experiment.report import build
    build(out)


if __name__ == "__main__":
    main()
