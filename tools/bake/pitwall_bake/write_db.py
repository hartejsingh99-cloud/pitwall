"""Write a transformed telemetry payload into telemetry.db (stdlib sqlite3 only).

A payload is plain dicts/lists (see make_sample_db.py / bake.py for how it's assembled). Writes are
idempotent per session: re-baking a session_id deletes its old rows first, so the bundle never
accumulates duplicates across re-runs.
"""

import os
import sqlite3
from typing import Any, Dict, List

_SCHEMA_PATH = os.path.join(os.path.dirname(os.path.dirname(__file__)), "schema.sql")


def _schema_sql() -> str:
    with open(_SCHEMA_PATH, "r", encoding="utf-8") as f:
        return f.read()


def write_telemetry_db(path: str, payload: Dict[str, Any]) -> None:
    """Create (if needed) telemetry.db at ``path`` and upsert one session's data.

    payload = {
      "session": {id, year, round, session_type, event_name, circuit_name, baked_at},
      "laps":     [{driver_id, driver_label, lap_number, lap_time_ms, compound, tyre_life, stint, is_accurate}],
      "channels": [{driver_id, lap_number, distance, speed, throttle, brake, gear, drs, x, y}],  # strings/None
      "pace":     [{driver_id, driver_label, median_pace_ms, pace_pct_vs_team, deg_ms_per_lap}],
    }
    """
    s = payload["session"]
    sid = s["id"]
    conn = sqlite3.connect(path)
    try:
        conn.executescript(_schema_sql())
        # idempotent re-bake: clear this session's rows first
        for table in ("session", "lap", "telemetry_channel", "driver_pace"):
            col = "id" if table == "session" else "session_id"
            conn.execute(f"DELETE FROM {table} WHERE {col} = ?", (sid,))

        conn.execute(
            "INSERT INTO session (id, year, round, session_type, event_name, circuit_name, baked_at) "
            "VALUES (?,?,?,?,?,?,?)",
            (sid, s["year"], s["round"], s["session_type"], s["event_name"], s["circuit_name"], s["baked_at"]),
        )

        conn.executemany(
            "INSERT INTO lap (session_id, driver_id, driver_label, lap_number, lap_time_ms, compound, "
            "tyre_life, stint, is_accurate) VALUES (?,?,?,?,?,?,?,?,?)",
            [
                (sid, l["driver_id"], l["driver_label"], l["lap_number"], l.get("lap_time_ms"),
                 l.get("compound"), l.get("tyre_life"), l.get("stint"), 1 if l.get("is_accurate") else 0)
                for l in payload.get("laps", [])
            ],
        )

        conn.executemany(
            "INSERT INTO telemetry_channel (session_id, driver_id, lap_number, distance, speed, throttle, "
            "brake, gear, drs, x, y) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            [
                (sid, c["driver_id"], c["lap_number"], c["distance"], c.get("speed"), c.get("throttle"),
                 c.get("brake"), c.get("gear"), c.get("drs"), c.get("x"), c.get("y"))
                for c in payload.get("channels", [])
            ],
        )

        conn.executemany(
            "INSERT INTO driver_pace (session_id, driver_id, driver_label, median_pace_ms, "
            "pace_pct_vs_team, deg_ms_per_lap) VALUES (?,?,?,?,?,?)",
            [
                (sid, p["driver_id"], p["driver_label"], p.get("median_pace_ms"),
                 p.get("pace_pct_vs_team"), p.get("deg_ms_per_lap"))
                for p in payload.get("pace", [])
            ],
        )
        conn.commit()
    finally:
        conn.close()
