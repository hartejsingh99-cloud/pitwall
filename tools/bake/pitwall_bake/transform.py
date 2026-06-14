"""Pure telemetry transforms — plain Python lists in, plain Python out.

No pandas, no fastf1, no I/O. This is the unit-tested heart of the bake; extract.py adapts
FastF1 into these signatures and write_db.py persists their output. Mirrors the Kotlin side:
symmetric_pace_pct here == symmetricGapPct in domain/DriverVsCar.kt.
"""

from typing import Dict, List, Optional, Sequence, Tuple

# A channel is a list of numbers (or None if FastF1 didn't provide it for this lap).
Channel = Optional[List[float]]


def decimate_by_distance(
    distance: Sequence[float],
    channels: Dict[str, Channel],
    step_m: float = 25.0,
) -> Tuple[List[float], Dict[str, Channel]]:
    """Subsample a per-lap trace by DISTANCE, not time.

    Keeps the first sample, then any sample at least ``step_m`` metres past the last kept one,
    and ALWAYS the final sample (so the lap's end aligns across drivers). Decimating by distance
    — rather than by time — keeps multi-driver overlays positionally aligned on track, which is
    the whole point of a track-dominance / delta view.

    Returns the kept distance axis and every channel subset to the same kept indices. A channel
    that is ``None`` (absent for this lap) stays ``None``.
    """
    n = len(distance)
    if n <= 2:
        return list(distance), {k: (list(v) if v is not None else None) for k, v in channels.items()}

    kept = [0]
    last = distance[0]
    for i in range(1, n):
        if distance[i] - last >= step_m:
            kept.append(i)
            last = distance[i]
    if kept[-1] != n - 1:
        kept.append(n - 1)

    out_dist = [float(distance[i]) for i in kept]
    out_chans: Dict[str, Channel] = {}
    for name, values in channels.items():
        if values is None:
            out_chans[name] = None
        else:
            out_chans[name] = [float(values[i]) for i in kept]
    return out_dist, out_chans


def pack_channels(values: Channel) -> Optional[str]:
    """Pack a numeric channel into a compact comma-delimited string (or None).

    Ints stay int-like ("1,2,3"); floats use %g (compact, drops trailing zeros). This is the
    on-disk form the Kotlin parseChannel() reads back with split(',')/toDouble — zero serialization
    deps on either side.
    """
    if values is None:
        return None

    def fmt(v) -> str:
        if isinstance(v, bool):
            return "1" if v else "0"
        if isinstance(v, int):
            return str(v)
        f = float(v)
        if f.is_integer():
            return str(int(f)) if abs(f) < 1e15 else ("%g" % f)
        return "%g" % f

    return ",".join(fmt(v) for v in values)


def symmetric_pace_pct(ms_i: Optional[int], ms_j: Optional[int]) -> Optional[float]:
    """Symmetric percent gap 100*(ti-tj)/((ti+tj)/2); negative when i is faster. None if either missing.

    Identical formula to the hero's qualifying symmetricGapPct, applied to median race-pace times so
    the race-pace companion is on the same scale as the one-lap rating.
    """
    if ms_i is None or ms_j is None:
        return None
    return 100.0 * (ms_i - ms_j) / ((ms_i + ms_j) / 2.0)


def tyre_deg_slope(tyre_life: Sequence[int], lap_ms: Sequence[int]) -> float:
    """Least-squares slope (ms per lap of tyre life) of lap time vs tyre life. 0.0 if < 2 points.

    Positive = the tyre is degrading (laps getting slower as it ages). Callers should pre-filter to
    green-flag, accurate, fuel-corrected laps within a stint before calling — this is the pure fit.
    """
    n = len(tyre_life)
    if n < 2 or n != len(lap_ms):
        return 0.0
    mean_x = sum(tyre_life) / n
    mean_y = sum(lap_ms) / n
    num = sum((x - mean_x) * (y - mean_y) for x, y in zip(tyre_life, lap_ms))
    den = sum((x - mean_x) ** 2 for x in tyre_life)
    if den == 0:
        return 0.0
    return num / den
