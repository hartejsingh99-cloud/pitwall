#!/usr/bin/env python3
"""Real telemetry bake CLI: FastF1 → telemetry.db. Run locally (needs fastf1 + network).

    python3 bake.py --out telemetry.db --sessions 2024:1:R 2024:1:Q 2024:2:R

Pipeline per session: extract.load_session (single load) → decimate-by-distance + pack channels
(pure transforms) → assemble laps/channels/pace payload → write_telemetry_db (idempotent per session).
Pace is computed for race ('R'/'S') sessions: median accurate green-flag lap, symmetric % vs
teammate, fitted tyre-deg slope. Telemetry is 2018+.
"""

import argparse
import datetime
import os
import statistics
from typing import Any, Dict, List

from pitwall_bake.extract import enable_cache, load_session
from pitwall_bake.transform import decimate_by_distance, pack_channels, symmetric_pace_pct, tyre_deg_slope
from pitwall_bake.write_db import write_telemetry_db

RACE_TYPES = {"R", "S"}  # race + sprint carry race-pace meaning
GREEN = "1"  # FastF1 TrackStatus '1' == green flag


def _pack_channel_row(raw: Dict[str, Any], step_m: float) -> Dict[str, Any]:
    chans = {k: raw.get(k) for k in ("speed", "throttle", "brake", "gear", "drs", "x", "y")}
    dist = raw.get("distance") or []
    d2, c2 = decimate_by_distance(dist, chans, step_m=step_m)
    return {
        "driver_id": raw["driver_id"], "lap_number": raw["lap_number"],
        "distance": pack_channels(d2),
        "speed": pack_channels(c2["speed"]), "throttle": pack_channels(c2["throttle"]),
        "brake": pack_channels(c2["brake"]), "gear": pack_channels(c2["gear"]),
        "drs": pack_channels(c2["drs"]), "x": pack_channels(c2["x"]), "y": pack_channels(c2["y"]),
    }


def _green_accurate(lap: Dict[str, Any]) -> bool:
    return bool(lap.get("is_accurate")) and lap.get("lap_time_ms") and lap.get("track_status", "") in (GREEN, "")


def _build_pace(laps: List[Dict[str, Any]], drivers: Dict[str, Dict[str, str]]) -> List[Dict[str, Any]]:
    by_driver: Dict[str, List[Dict[str, Any]]] = {}
    for lap in laps:
        if _green_accurate(lap):
            by_driver.setdefault(lap["driver_id"], []).append(lap)

    median_ms: Dict[str, int] = {}
    deg: Dict[str, float] = {}
    for did, dl in by_driver.items():
        times = [l["lap_time_ms"] for l in dl]
        median_ms[did] = int(statistics.median(times)) if times else None
        lives = [l["tyre_life"] for l in dl if l.get("tyre_life") is not None]
        ms = [l["lap_time_ms"] for l in dl if l.get("tyre_life") is not None]
        deg[did] = round(tyre_deg_slope(lives, ms), 3) if len(lives) >= 2 else 0.0

    # teammate map by team
    team_drivers: Dict[str, List[str]] = {}
    for did, meta in drivers.items():
        team_drivers.setdefault(meta.get("team", ""), []).append(did)

    pace: List[Dict[str, Any]] = []
    for did in median_ms:
        team = drivers.get(did, {}).get("team", "")
        mates = [d for d in team_drivers.get(team, []) if d != did and d in median_ms]
        pct = symmetric_pace_pct(median_ms[did], median_ms[mates[0]]) if mates else None
        pace.append({
            "driver_id": did,
            "driver_label": drivers.get(did, {}).get("label", did),
            "median_pace_ms": median_ms[did],
            "pace_pct_vs_team": round(pct, 4) if pct is not None else None,
            "deg_ms_per_lap": deg[did],
        })
    return pace


def bake_one(out: str, year: int, rnd: int, stype: str, step_m: float, baked_at: str) -> Dict[str, int]:
    extracted = load_session(year, rnd, stype)
    drivers = extracted["drivers"]
    labels = {d: m.get("label", d) for d, m in drivers.items()}

    laps = [
        {**l, "driver_label": labels.get(l["driver_id"], l["driver_id"])}
        for l in extracted["laps"]
    ]
    channels = [_pack_channel_row(r, step_m) for r in extracted["raw_channels"]]
    pace = _build_pace(extracted["laps"], drivers) if stype in RACE_TYPES else []

    payload = {
        "session": {**extracted["session"], "baked_at": baked_at},
        "laps": [{k: l.get(k) for k in
                  ("driver_id", "driver_label", "lap_number", "lap_time_ms", "compound",
                   "tyre_life", "stint", "is_accurate")} for l in laps],
        "channels": channels,
        "pace": pace,
    }
    write_telemetry_db(out, payload)
    return {"laps": len(payload["laps"]), "channels": len(channels), "pace": len(pace)}


def main():
    ap = argparse.ArgumentParser(description="Bake FastF1 telemetry into telemetry.db (2018+).")
    ap.add_argument("--out", required=True, help="output telemetry.db path")
    ap.add_argument("--sessions", nargs="+", required=True, help="YEAR:ROUND:SESSION e.g. 2024:1:R")
    ap.add_argument("--step-m", type=float, default=25.0, help="distance decimation step in metres")
    ap.add_argument("--cache", default="fastf1cache", help="FastF1 cache dir (block storage in prod)")
    args = ap.parse_args()

    os.makedirs(args.cache, exist_ok=True)
    enable_cache(args.cache)
    baked_at = datetime.date.today().isoformat()

    for spec in args.sessions:
        year, rnd, stype = spec.split(":")
        if int(year) < 2018:
            print(f"skip {spec}: telemetry is 2018+ only")
            continue
        stats = bake_one(args.out, int(year), int(rnd), stype, args.step_m, baked_at)
        print(f"baked {spec}: {stats}")
    print(f"done -> {os.path.abspath(args.out)}")


if __name__ == "__main__":
    main()
