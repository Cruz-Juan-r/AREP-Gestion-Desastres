"""Starts the whole prototype as local processes (no Docker needed).

    python scripts/run_local_stack.py            # Ctrl+C to stop

Processes: MQTT broker (amqtt, pure Python) :1883, cloud API :8000 (Java),
WAN emulator :9000 (control :9100), edge node :8080 (Java).
The Java services run from their Spring Boot jars; build them first with
``mvnw.cmd -DskipTests package`` (Windows) or ``./mvnw -DskipTests package``.
Logs go to ./logs. Databases go to ./data.
"""
from __future__ import annotations

import os
import signal
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LOGS = ROOT / "logs"
DATA = ROOT / "data"
EDGE_JAR = ROOT / "edge-node" / "target" / "edge-node.jar"
CLOUD_JAR = ROOT / "cloud-api" / "target" / "cloud-api.jar"

BROKER_CONF = """
listeners:
  default:
    type: tcp
    bind: 0.0.0.0:1883
plugins:
  amqtt.plugins.authentication.AnonymousAuthPlugin:
    allow_anonymous: true
"""


def start(name: str, cmd: list[str], env: dict | None = None) -> subprocess.Popen:
    log = open(LOGS / f"{name}.log", "w")
    e = {**os.environ, "PYTHONPATH": str(ROOT), **(env or {})}
    return subprocess.Popen(cmd, cwd=ROOT, env=e, stdout=log, stderr=subprocess.STDOUT)


def main() -> None:
    missing = [j for j in (EDGE_JAR, CLOUD_JAR) if not j.exists()]
    if missing:
        sys.exit("Missing " + ", ".join(str(m.relative_to(ROOT)) for m in missing)
                 + ". Build them first: mvnw.cmd -DskipTests package")
    java = os.environ.get("JAVA", "java")
    LOGS.mkdir(exist_ok=True)
    DATA.mkdir(exist_ok=True)
    for f in DATA.glob("*.db*"):
        f.unlink()
    conf = DATA / "broker.yaml"
    conf.write_text(BROKER_CONF)
    py = sys.executable
    procs = [
        start("broker", ["amqtt", "-c", str(conf)]),
        start("cloud", [java, "-jar", str(CLOUD_JAR)], {"CLOUD_DB": str(DATA / "cloud.db")}),
        start("wan", [py, "-m", "wan.proxy", "--listen", "9000", "--upstream", "127.0.0.1:8000",
                      "--control", "9100"]),
    ]
    time.sleep(2)
    procs.append(start("edge", [java, "-jar", str(EDGE_JAR)],
                       {"EDGE_DB": str(DATA / "edge.db"), "MQTT_HOST": "127.0.0.1",
                        "CLOUD_URL": "http://127.0.0.1:9000"}))
    print("Stack running: dashboard http://localhost:8080  | cloud http://localhost:8000/docs")
    print("Ctrl+C to stop. Logs in ./logs")
    try:
        while all(p.poll() is None for p in procs):
            time.sleep(1)
        print("A process exited, check ./logs")
    except KeyboardInterrupt:
        pass
    finally:
        for p in procs:
            if os.name == "nt":          # Windows has no SIGINT for child processes
                p.terminate()
            else:
                p.send_signal(signal.SIGINT)
        for p in procs:
            try:
                p.wait(5)
            except subprocess.TimeoutExpired:
                p.kill()


if __name__ == "__main__":
    main()
