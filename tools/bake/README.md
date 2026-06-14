# PitWall telemetry bake

Produces `telemetry.db` — the read-only SQLite the app bundles for car-data features (telemetry
overlay, delta-time, track-dominance, tyre-deg, race-pace). Runs **locally**, $0, no server. The app
ships whatever `telemetry.db` is bundled at
`composeApp/src/commonMain/composeResources/files/telemetry.db`.

## Two ways to get a telemetry.db

### 1. Synthetic seed (no network, no deps) — for development / CI
```bash
cd tools/bake
python3 make_sample_db.py --out ../../composeApp/src/commonMain/composeResources/files/telemetry.db
```
Pure stdlib. Produces a tiny 2-session sample so the app and its integration test run offline. **This
is fake data** — every shipped build that wants real telemetry must replace it via the real bake.

### 2. Real bake (FastF1) — for actual data
```bash
cd tools/bake
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
python3 bake.py --out telemetry.db --sessions 2024:1:R 2024:1:Q 2024:2:R ...
# then copy telemetry.db into composeApp/src/commonMain/composeResources/files/  (gitignored)
```
Telemetry is available **2018+** only. The bake loads each session in a **single**
`session.load(laps=True, telemetry=True)` call (avoids FastF1 issue #852), decimates by distance,
and writes compact comma-delimited channels. Re-baking a session is idempotent (old rows replaced).

## Tests (stdlib, no install)
```bash
cd tools/bake
python3 -m unittest discover -s tests -v
```
Only the **pure transforms** and the **DB writer** are unit-tested (they take plain lists / dicts).
`extract.py` (the FastF1 adapter) is intentionally thin and exercised only by a real bake run.

## Layout
- `pitwall_bake/transform.py` — pure: decimate-by-distance, channel packing, symmetric pace %, tyre-deg slope.
- `pitwall_bake/extract.py` — FastF1 → plain dict-of-lists (the only module importing fastf1/pandas).
- `pitwall_bake/write_db.py` — payload → telemetry.db (stdlib sqlite3); schema is `schema.sql`.
- `bake.py` — CLI: extract → transform → write, for a list of `YEAR:ROUND:SESSION`.
- `make_sample_db.py` — synthetic seed generator (stdlib only).
- `schema.sql` — the telemetry.db contract (kept 1:1 with the Kotlin `Telemetry.sq`).

## Distribution
Real `telemetry.db` is **gitignored** (like `f1db.db`). Ship it in the app bundle and/or host it as a
GitHub **Release** asset (free static hosting) for an optional in-app refresh. Attribution for FastF1
(and its upstream sources) lives in the app's About / Licenses screen alongside the f1db CC-BY credit.
