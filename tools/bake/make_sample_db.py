#!/usr/bin/env python3
"""Generate a small SYNTHETIC telemetry.db for development / CI / app verification.

No FastF1, no network — pure stdlib + the bake's own transforms. The shape (sessions, drivers,
parallel-array channels, mirrored teammate pace) matches what the real bake.py produces, so the app
and its integration test can run end-to-end offline without real data. The REAL telemetry.db (from
bake.py) is gitignored and Releases-hosted; this seed is what ships when no real bake is bundled.

Usage:
    python3 make_sample_db.py --out ../../composeApp/src/commonMain/composeResources/files/telemetry.db
"""

import argparse
import math
import os

from pitwall_bake.transform import decimate_by_distance, pack_channels, symmetric_pace_pct, tyre_deg_slope
from pitwall_bake.write_db import write_telemetry_db

BAKED_AT = "2026-06-14"

# Two constructors, two drivers each → every driver has exactly one teammate (mirrored pace).
DRIVERS = [
    # driver_id, label, constructor, base_pace_ms (race), quali_rank within team (0 faster)
    ("max-verstappen", "Max Verstappen (VER)", "red-bull", 95000, 0),
    ("sergio-perez", "Sergio Pérez (PER)", "red-bull", 95450, 1),
    ("charles-leclerc", "Charles Leclerc (LEC)", "ferrari", 95300, 0),
    ("carlos-sainz-jr", "Carlos Sainz Jr. (SAI)", "ferrari", 95520, 1),
]

LAPS_PER_DRIVER = 5
TRACK_LEN_M = 5000.0
SAMPLE_STEP_M = 10.0  # fine raw trace; decimated to ~25 m below


def _raw_trace():
    """A fine synthetic lap trace: monotonic distance, a speed profile with three 'corner' dips,
    matching throttle/brake/gear/drs and a simple oval x/y. Returns plain lists."""
    n = int(TRACK_LEN_M / SAMPLE_STEP_M) + 1
    dist, speed, throttle, brake, gear, drs, xs, ys = [], [], [], [], [], [], [], []
    for i in range(n):
        d = i * SAMPLE_STEP_M
        frac = d / TRACK_LEN_M
        # three corners as gaussian-ish dips in speed
        corner = sum(math.exp(-((frac - c) ** 2) / 0.0015) for c in (0.2, 0.55, 0.85))
        v = 330.0 - 230.0 * min(corner, 1.0)  # 100..330 km/h
        dist.append(d)
        speed.append(round(v, 1))
        throttle.append(100.0 if corner < 0.3 else 0.0)
        brake.append(1.0 if 0.3 <= corner < 0.9 else 0.0)
        gear.append(max(1, min(8, int(v / 42) + 1)))
        drs.append(1 if 0.62 < frac < 0.78 else 0)  # DRS zone on the back straight
        ang = 2 * math.pi * frac
        xs.append(round(1500.0 * math.cos(ang), 1))
        ys.append(round(900.0 * math.sin(ang), 1))
    return dist, {"speed": speed, "throttle": throttle, "brake": brake,
                  "gear": gear, "drs": drs, "x": xs, "y": ys}


def _channels_for(driver_id, lap_number, speed_scale):
    dist, chans = _raw_trace()
    chans = dict(chans)
    chans["speed"] = [round(v * speed_scale, 1) for v in chans["speed"]]
    d2, c2 = decimate_by_distance(dist, chans, step_m=25.0)
    return {
        "driver_id": driver_id, "lap_number": lap_number,
        "distance": pack_channels(d2),
        "speed": pack_channels(c2["speed"]), "throttle": pack_channels(c2["throttle"]),
        "brake": pack_channels(c2["brake"]), "gear": pack_channels(c2["gear"]),
        "drs": pack_channels(c2["drs"]), "x": pack_channels(c2["x"]), "y": pack_channels(c2["y"]),
    }


def _race_payload():
    laps, channels, pace = [], [], []
    median_by_driver = {}
    for did, label, _team, base, _rank in DRIVERS:
        lap_ms_list, lives = [], []
        for ln in range(1, LAPS_PER_DRIVER + 1):
            deg = (ln - 1) * 120  # +120 ms/lap tyre deg
            lap_ms = base + deg
            laps.append({"driver_id": did, "driver_label": label, "lap_number": ln,
                         "lap_time_ms": lap_ms, "compound": "MEDIUM", "tyre_life": ln,
                         "stint": 1, "is_accurate": True})
            # faster cars run marginally higher top speed
            channels.append(_channels_for(did, ln, speed_scale=1.0 + (95500 - base) / 50000.0))
            lap_ms_list.append(lap_ms)
            lives.append(ln)
        sm = sorted(lap_ms_list)[len(lap_ms_list) // 2]
        median_by_driver[did] = sm
        pace.append({"driver_id": did, "driver_label": label, "median_pace_ms": sm,
                     "pace_pct_vs_team": None,  # filled below once both teammates known
                     "deg_ms_per_lap": round(tyre_deg_slope(lives, lap_ms_list), 3)})
    # mirror pace within each constructor
    by_team = {}
    for did, _l, team, _b, _r in DRIVERS:
        by_team.setdefault(team, []).append(did)
    for team, ids in by_team.items():
        if len(ids) == 2:
            a, b = ids
            pa = next(p for p in pace if p["driver_id"] == a)
            pb = next(p for p in pace if p["driver_id"] == b)
            pa["pace_pct_vs_team"] = round(symmetric_pace_pct(median_by_driver[a], median_by_driver[b]), 4)
            pb["pace_pct_vs_team"] = round(symmetric_pace_pct(median_by_driver[b], median_by_driver[a]), 4)
    return {
        "session": {"id": "2024-1-R", "year": 2024, "round": 1, "session_type": "R",
                    "event_name": "Bahrain", "circuit_name": "Bahrain International Circuit",
                    "baked_at": BAKED_AT},
        "laps": laps, "channels": channels, "pace": pace,
    }


def _quali_payload():
    laps, channels = [], []
    for did, label, _team, base, rank in DRIVERS:
        ln = 1
        lap_ms = base - 2000 + rank * 250  # quali laps faster than race; rank separates teammates
        laps.append({"driver_id": did, "driver_label": label, "lap_number": ln,
                     "lap_time_ms": lap_ms, "compound": "SOFT", "tyre_life": 2,
                     "stint": 1, "is_accurate": True})
        channels.append(_channels_for(did, ln, speed_scale=1.02))
    return {
        "session": {"id": "2024-1-Q", "year": 2024, "round": 1, "session_type": "Q",
                    "event_name": "Bahrain", "circuit_name": "Bahrain International Circuit",
                    "baked_at": BAKED_AT},
        "laps": laps, "channels": channels, "pace": [],
    }


def main():
    ap = argparse.ArgumentParser(description="Generate a synthetic telemetry.db for development.")
    ap.add_argument("--out", required=True, help="output path for telemetry.db")
    args = ap.parse_args()
    out = os.path.abspath(args.out)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    if os.path.exists(out):
        os.remove(out)
    write_telemetry_db(out, _race_payload())
    write_telemetry_db(out, _quali_payload())
    print(f"wrote synthetic telemetry.db -> {out}")


if __name__ == "__main__":
    main()
