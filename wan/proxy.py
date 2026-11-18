"""WAN link emulator between the municipality and the national cloud.

A TCP proxy that adds one-way latency with jitter in each direction and can
cut the link on command (all open connections are aborted and new ones are
refused), reproducing what happens when antennas or fibre fail in a disaster.

    python -m wan.proxy --listen 9000 --upstream localhost:8000 --control 9100

Control API (plain HTTP on the control port):
    GET  /state
    POST /link?up=0|1
    POST /config?latency_ms=200&jitter_ms=80
"""
from __future__ import annotations

import argparse
import asyncio
import json
import logging
import random
import time
from urllib.parse import parse_qs, urlparse

logging.basicConfig(level=logging.INFO, format="%(asctime)s wan %(message)s")
log = logging.getLogger("wan")


class Link:
    def __init__(self, latency_ms: float, jitter_ms: float):
        self.up = True
        self.latency_ms = latency_ms
        self.jitter_ms = jitter_ms
        self.writers: set[asyncio.StreamWriter] = set()
        self.changes: list[dict] = []
        self.refused = 0
        self.connections = 0

    def delay_s(self) -> float:
        d = self.latency_ms + random.uniform(-self.jitter_ms, self.jitter_ms)
        return max(0.0, d) / 1000

    def set_up(self, up: bool) -> None:
        if up == self.up:
            return
        self.up = up
        self.changes.append({"ts_ms": int(time.time() * 1000), "up": up})
        log.info("link %s", "UP" if up else "DOWN")
        if not up:
            for w in list(self.writers):
                w.transport.abort()
            self.writers.clear()

    def state(self) -> dict:
        return {"up": self.up, "latency_ms": self.latency_ms, "jitter_ms": self.jitter_ms,
                "open_connections": len(self.writers) // 2, "connections": self.connections,
                "refused": self.refused, "changes": self.changes[-20:]}


async def pipe(reader: asyncio.StreamReader, writer: asyncio.StreamWriter, link: Link) -> None:
    """Forward bytes adding latency while preserving order (FIFO delivery times)."""
    queue: asyncio.Queue = asyncio.Queue()
    last = 0.0

    async def deliver():
        while True:
            at, chunk = await queue.get()
            if chunk is None:
                break
            wait = at - time.monotonic()
            if wait > 0:
                await asyncio.sleep(wait)
            writer.write(chunk)
            await writer.drain()

    task = asyncio.create_task(deliver())
    try:
        while True:
            chunk = await reader.read(65536)
            if not chunk:
                break
            last = max(last, time.monotonic() + link.delay_s())
            await queue.put((last, chunk))
    except (ConnectionError, asyncio.IncompleteReadError):
        pass
    finally:
        await queue.put((0, None))
        try:
            await task
            writer.close()
        except Exception:
            pass


def make_handler(link: Link, up_host: str, up_port: int):
    async def handle(c_reader, c_writer):
        if not link.up:
            link.refused += 1
            c_writer.transport.abort()
            return
        try:
            u_reader, u_writer = await asyncio.open_connection(up_host, up_port)
        except OSError:
            c_writer.transport.abort()
            return
        link.connections += 1
        link.writers.update({c_writer, u_writer})
        await asyncio.gather(pipe(c_reader, u_writer, link), pipe(u_reader, c_writer, link))
        link.writers.discard(c_writer)
        link.writers.discard(u_writer)
    return handle


def make_control(link: Link):
    async def control(reader, writer):
        try:
            head = await reader.readuntil(b"\r\n\r\n")
            method, target, _ = head.split(b"\r\n", 1)[0].decode().split(" ", 2)
            url = urlparse(target)
            qs = {k: v[0] for k, v in parse_qs(url.query).items()}
            status, body = 200, None
            if method == "GET" and url.path == "/state":
                body = link.state()
            elif method == "POST" and url.path == "/link":
                link.set_up(qs.get("up", "1") in ("1", "true", "yes"))
                body = link.state()
            elif method == "POST" and url.path == "/config":
                link.latency_ms = float(qs.get("latency_ms", link.latency_ms))
                link.jitter_ms = float(qs.get("jitter_ms", link.jitter_ms))
                body = link.state()
            else:
                status, body = 404, {"error": "not found"}
            data = json.dumps(body).encode()
            writer.write(f"HTTP/1.1 {status} OK\r\nContent-Type: application/json\r\n"
                         f"Content-Length: {len(data)}\r\nConnection: close\r\n\r\n".encode() + data)
            await writer.drain()
        finally:
            writer.close()
    return control


async def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--listen", type=int, default=9000)
    ap.add_argument("--upstream", default="localhost:8000")
    ap.add_argument("--control", type=int, default=9100)
    ap.add_argument("--latency-ms", type=float, default=200)
    ap.add_argument("--jitter-ms", type=float, default=80)
    a = ap.parse_args()
    host, port = a.upstream.rsplit(":", 1)
    link = Link(a.latency_ms, a.jitter_ms)
    s1 = await asyncio.start_server(make_handler(link, host, int(port)), "0.0.0.0", a.listen)
    s2 = await asyncio.start_server(make_control(link), "0.0.0.0", a.control)
    log.info("listening :%s -> %s (control :%s, latency %s±%s ms)",
             a.listen, a.upstream, a.control, a.latency_ms, a.jitter_ms)
    async with s1, s2:
        await asyncio.gather(s1.serve_forever(), s2.serve_forever())


if __name__ == "__main__":
    asyncio.run(main())
