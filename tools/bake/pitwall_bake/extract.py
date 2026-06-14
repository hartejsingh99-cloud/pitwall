"""FastF1 adapter — the ONLY module that imports fastf1/pandas.

Loads a session in a SINGLE session.load(laps=True, telemetry=True) call (FastF1 issue #852: loading
telemetry separately after laps duplicates crash laps) and returns PLAIN Python (lists/dicts) — no
pandas leaves this module, so everything downstream (transform.py) stays pure and unit-testable.

Not unit-tested: requires fastf1 + network. Exercised by a real `bake.py` run. Telemetry is 2018+.
"""

from typing import Any, Dict, List, Optional


def _slugify(driver_id: str, abbreviation: str) -> str:
    """Best-effort f1db-style slug. FastF1's DriverId is usually 'max_verstappen'; f1db uses
    'max-verstappen'. Falls back to the lowercased 3-letter abbreviation when DriverId is absent."""
    base = (driver_id or abbreviation or "").strip().lower()
    if not base:
        return abbreviation.lower()
    return base.replace("_", "-").replace(" ", "-")


def _ms(td) -> Optional[int]:
    """pandas Timedelta -> integer milliseconds, or None for NaT/None."""
    if td is None:
        return None
    try:
        import pandas as pd  # local import: only when actually extracting
        if pd.isna(td):
            return None
    except Exception:
        pass
    try:
        return int(td.total_seconds() * 1000)
    except Exception:
        return None


def enable_cache(cache_dir: str) -> None:
    import fastf1
    fastf1.Cache.enable_cache(cache_dir)


def load_session(year: int, rnd: int, session_type: str, slug_for_abbr: Optional[Dict[str, str]] = None) -> Dict[str, Any]:
    """Return a plain-Python payload-precursor for one session (channels still RAW/undecimated).

    ``slug_for_abbr`` maps an UPPERCASE 3-letter code (FastF1's lap 'Driver', e.g. 'VER') to the
    canonical f1db driver slug (e.g. 'max-verstappen'). FastF1's *lap* rows carry no DriverId, so
    without this the driver_id falls back to the lowercased abbreviation ('ver') and never joins the
    f1db-keyed hero race-pace column. The caller scopes the map to the session's season (abbreviations
    are unique within a season), so it's unambiguous; any code absent from the map falls back to the
    slugified ergast id, then the abbreviation.
    """
    import fastf1

    session = fastf1.get_session(year, rnd, session_type)
    # ONE call — never load laps then telemetry separately (issue #852).
    session.load(laps=True, telemetry=True, weather=False, messages=False)

    ev = session.event
    event_name = str(ev.get("Location", ev.get("EventName", f"Round {rnd}")))
    circuit_name = str(ev.get("OfficialEventName", event_name))

    drivers: Dict[str, Dict[str, str]] = {}
    laps_out: List[Dict[str, Any]] = []
    raw_channels: List[Dict[str, Any]] = []

    for _, lap in session.laps.iterrows():
        abbr = str(lap.get("Driver", "") or "")
        raw_did = str(lap.get("DriverId", "") or "")
        did = (slug_for_abbr or {}).get(abbr.upper()) or _slugify(raw_did, abbr)
        if did not in drivers:
            drivers[did] = {"label": abbr or did, "team": str(lap.get("Team", "") or "")}

        lap_no = lap.get("LapNumber")
        if lap_no is None:
            continue
        lap_number = int(lap_no)
        laps_out.append({
            "driver_id": did,
            "lap_number": lap_number,
            "lap_time_ms": _ms(lap.get("LapTime")),
            "compound": (str(lap.get("Compound")) if lap.get("Compound") is not None else None),
            "tyre_life": (int(lap.get("TyreLife")) if lap.get("TyreLife") == lap.get("TyreLife") else None),
            "stint": (int(lap.get("Stint")) if lap.get("Stint") == lap.get("Stint") else None),
            "is_accurate": bool(lap.get("IsAccurate", False)),
            "track_status": str(lap.get("TrackStatus", "") or ""),
        })

        try:
            tel = lap.get_telemetry()
        except Exception:
            continue  # some laps have no telemetry; skip the channel row, keep the lap metadata
        if tel is None or len(tel) == 0:
            continue

        def col(name):
            return [float(v) for v in tel[name].tolist()] if name in tel else None

        raw_channels.append({
            "driver_id": did,
            "lap_number": lap_number,
            "distance": col("Distance"),
            "speed": col("Speed"),
            "throttle": col("Throttle"),
            "brake": [1.0 if bool(v) else 0.0 for v in tel["Brake"].tolist()] if "Brake" in tel else None,
            "gear": [int(v) for v in tel["nGear"].tolist()] if "nGear" in tel else None,
            "drs": [int(v) for v in tel["DRS"].tolist()] if "DRS" in tel else None,
            "x": col("X"),
            "y": col("Y"),
        })

    return {
        "session": {
            "id": f"{year}-{rnd}-{session_type}",
            "year": int(year), "round": int(rnd), "session_type": session_type,
            "event_name": event_name, "circuit_name": circuit_name,
        },
        "drivers": drivers,
        "laps": laps_out,
        "raw_channels": raw_channels,
    }
