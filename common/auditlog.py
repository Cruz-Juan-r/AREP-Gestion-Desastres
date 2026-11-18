"""Append-only, hash-chained audit log stored in SQLite.

Each entry stores the SHA-256 of (previous hash + entry content). Changing,
deleting or reordering any past entry breaks every hash after it, so
``verify`` pinpoints the first tampered entry.
"""
from __future__ import annotations

import hashlib
import json
import sqlite3

GENESIS = "0" * 64

SCHEMA = """
CREATE TABLE IF NOT EXISTS audit (
    seq       INTEGER PRIMARY KEY AUTOINCREMENT,
    ts_ms     INTEGER NOT NULL,
    kind      TEXT    NOT NULL,
    ref_id    TEXT    NOT NULL,
    detail    TEXT    NOT NULL,
    prev_hash TEXT    NOT NULL,
    hash      TEXT    NOT NULL
);
"""


def _digest(prev_hash: str, ts_ms: int, kind: str, ref_id: str, detail: str) -> str:
    material = json.dumps([prev_hash, ts_ms, kind, ref_id, detail], separators=(",", ":"))
    return hashlib.sha256(material.encode()).hexdigest()


class AuditLog:
    """Must be used inside the caller's transaction (same connection)."""

    def __init__(self, conn: sqlite3.Connection):
        conn.executescript(SCHEMA)
        row = conn.execute("SELECT hash FROM audit ORDER BY seq DESC LIMIT 1").fetchone()
        self._last = row[0] if row else GENESIS

    def append(self, conn: sqlite3.Connection, ts_ms: int, kind: str, ref_id: str,
               detail: dict) -> None:
        d = json.dumps(detail, sort_keys=True, separators=(",", ":"))
        h = _digest(self._last, ts_ms, kind, ref_id, d)
        conn.execute(
            "INSERT INTO audit (ts_ms, kind, ref_id, detail, prev_hash, hash) VALUES (?,?,?,?,?,?)",
            (ts_ms, kind, ref_id, d, self._last, h),
        )
        self._last = h

    def reset(self) -> None:
        self._last = GENESIS


def verify(conn: sqlite3.Connection) -> dict:
    prev = GENESIS
    n = 0
    for seq, ts_ms, kind, ref_id, detail, prev_hash, h in conn.execute(
        "SELECT seq, ts_ms, kind, ref_id, detail, prev_hash, hash FROM audit ORDER BY seq"
    ):
        n += 1
        if prev_hash != prev or _digest(prev, ts_ms, kind, ref_id, detail) != h:
            return {"valid": False, "entries": n, "first_invalid_seq": seq}
        prev = h
    return {"valid": True, "entries": n, "first_invalid_seq": None, "head": prev}
