# PitWall — Phase 1 Remaining Features — Implementation Plan

> **Stream:** `phase1-remaining-features`
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Date:** 2026-06-14
**Status:** Ready for implementation (after the Phase-0 hero plan lands)
**Depends on:** `2026-06-14-pitwall-phase0-hero.md` (repo, KMP `composeApp`, SQLDelight read-only bundled-f1db path, Koin, the pure stats engine, `App()` shell). This plan **extends** that scaffold — it does not re-create it.

**Goal:** Ship the four remaining Phase-1 features from the design spec §5 — all **100% offline** from the bundled f1db, **$0**, no backend, no jolpica required:

| Feature | Spec ref | This plan |
|---|---|---|
| **A — Results / standings / schedule browse** | §5 Phase 1 row 5 | Task 2–5 |
| **B — Career / teammate head-to-head** | §5 Phase 1 row 2 | Task 6–8 |
| **C — Title-permutation calculator** | §5 Phase 1 row 3 | Task 9–11 |
| **D — Records & On-This-Day** | §5 Phase 1 row 4 | Task 12–14 |

The hero (Driver vs Car, §4) ships in the Phase-0 plan; this plan completes Phase 1. After this, the app is the full **"$0, offline-first, stats no other app has"** Phase-1 product before any backend (Phase 2) exists.

---

## 0. Why this plan exists separately, and what it inherits

The Phase-0 plan deliberately scoped down to *one* screen (the hero) to prove the end-to-end KMP + bundled-SQLite + pure-engine + Android/macOS pipeline. It explicitly **deferred** "career H2H, title permutations, records, results/standings browse" to a later plan (Phase-0 plan, Self-review → "Deferred to later plans"). **This is that plan.**

Everything here reuses, never duplicates, the Phase-0 primitives:

- **Data path:** `ensureF1dbFile()` + `makeF1dbDriver(path)` (read-only, no `Schema.create()`), `DATASET_VERSION` gating. **No new copy logic.**
- **DB access:** SQLDelight `F1db` with labelled `.sq` queries; `db.f1dbQueries.*`. New queries are **added to the same `F1db.sq`** (or a sibling `.sq` in the same SQLDelight database — same generated package `dev.pitwall.db`).
- **Stats engine:** `symmetricGapPct`, `lastCommonSegment`, `computeDriverVsCar`, `median` in `domain/`. Feature B's teammate-era mode **reuses the hero's symmetric-gap math verbatim** (spec §4.1) — do not re-implement it.
- **DI:** add new repos + ViewModels as `single`/`factory` in the existing `appModule`.
- **UI shell:** the existing `App()` two-tab `NavigationBar` grows to host the new screens (Task 15 wires navigation).
- **Compliance:** the `LicensesScreen` (CC-BY-4.0 F1DB attribution + trademark disclaimer) already exists; these features add **no** new data source, so **no new attribution is required** — but Records and the title calculator surface "as-of-bundle" data, so each carries a small "data as of f1db `vYYYY.R.M`" stamp (Task 12, Task 9) so users never mistake a stale bundle for live truth.

**Hard constraints carried from the spec (these override convenience):**

- **$0/month.** All four features read the bundled f1db only. **No jolpica call is on any request path** (spec §3 rule: "Never route a historical feature through jolpica per-pageview"). The title calculator's freshness comes from re-bundling f1db each release, not a network call — see Task 9.
- **No "F1"/"Formula 1"/logo in branding.** Screen titles, tab labels, and copy describe the app as a Formula 1 *companion* in prose (allowed) but never use the marks as branding. Reuse existing disclaimer copy.
- **Public personal repo, CC-BY-4.0.** No new licence obligations; the existing `NOTICE` + `LicensesScreen` cover all four features (single data source, already credited).

---

## 1. Two cross-cutting schema facts (verified against the bundled DB) — read before any task

These were re-verified on the actual bundled copy at `/tmp/f1db/f1db.db` (a `v2026.x` release: **915 drivers, 187 constructors, 1,171 races, 54 grands prix, seasons 1950–2026**; the 2026 calendar carries all 22 rounds with results through round 6 / Monaco). The published latest is `v2026.3.0`; this local copy is ahead — see Risk R1.

### FACT 1 — `qualifying_result` / `race_result` are thin VIEWS over `race_data`, with **renamed** columns

The views drop the `qualifying_`/`race_` prefix that the underlying `race_data` table carries. **Use the view column names, never the `race_data` prefixed names** (Phase-0 self-review corrections #4). Verified view columns:

- `qualifying_result`: `race_id, position_display_order, position_number, position_text, driver_number, driver_id, constructor_id, engine_manufacturer_id, tyre_manufacturer_id, time, time_millis, q1, q1_millis, q2, q2_millis, q3, q3_millis, gap, gap_millis, interval, interval_millis, laps`
- `race_result`: `race_id, position_display_order, position_number, position_text, driver_number, driver_id, constructor_id, engine_manufacturer_id, tyre_manufacturer_id, shared_car, laps, time, time_millis, time_penalty, time_penalty_millis, gap, gap_millis, gap_laps, interval, interval_millis, reason_retired, points, pole_position, qualification_position_number, qualification_position_text, grid_position_number, grid_position_text, positions_gained, pit_stops, fastest_lap, driver_of_the_day, grand_slam`

Sibling views exist for the other `race_data.type` discriminators: `sprint_race_result`, `sprint_qualifying_result`, `starting_grid_position`, `sprint_starting_grid_position`, `fastest_lap`, `pit_stop`, `pre_qualifying_result`, `qualifying_1_result`, `qualifying_2_result`, `free_practice_{1..4}_result`, `warming_up_result`.

### FACT 2 (CRITICAL — the single biggest correctness risk) — from 2006 onward `qualifying_result.time_millis` is almost always NULL

The real lap times live in `q1_millis` / `q2_millis` / `q3_millis`. **Verified on this DB:** in 2024, `qualifying_result` has **478 rows with NULL `time_millis`** but **474 rows with a populated Q-segment**. A naive teammate join on `time_millis` for Hamilton-vs-Alonso returns **0** common sessions; the same join on `COALESCE(q3_millis, q2_millis, q1_millis, time_millis)` returns **340**.

**RULE (applies to Features A and B, and matches the hero's `lastCommonSegment`):** every quali read that needs "the lap time" must select
```
COALESCE(q3_millis, q2_millis, q1_millis, time_millis)
```
never `time_millis` alone. Display the headline quali time through the same coalesce. The hero's `lastCommonSegment` (Q3→Q2→Q1→time fallback) is already correct and is **reused as-is** by Feature B's career mode.

> The Phase-0 `qualifyingForSeason` query selects bare `q.time_millis` as the displayed time — that column is NULL for 2006+. **Task 16 (cleanup) patches that query** so the hero's *displayed* time coalesces too. The hero's *engine* is unaffected (it already coalesces), so this is a display fix, not a math fix.

### Three corollary rules (verified)

- **Order results by `position_display_order`, never `position_number`.** `position_number` is NULL for every DNF/DNS/NC/DSQ; `position_display_order` is always populated and monotonic. (Phase-0 self-review correction #3.)
- **Poles = the `race_result.pole_position` BOOLEAN flag, never `qualifying_result.position_number=1`.** Verified: Hamilton has **105** quali-P1 rows but **104** canonical poles (matching `driver.total_pole_positions=104`); Schumacher 69 vs 68. Pole is grid-determining and affected by penalties/format. (Feature D, correction #2.)
- **`position_text` carries non-numeric statuses** verbatim: `DNF`, `DNQ`, `DNS`, `DNPQ`, `NC`, `DSQ`, `EX`. Display verbatim; never coerce to int. One `DSQ` even appears in `season_driver_standing` (1997 Schumacher).

---

## File structure (additions only — Phase-0 files unchanged)

```
composeApp/src/
├── commonMain/
│   ├── sqldelight/dev/pitwall/db/
│   │   ├── F1db.sq                      # (existing) hero queries  — Task 16 patches qualifyingForSeason display
│   │   ├── Browse.sq                    # NEW — Feature A queries
│   │   ├── HeadToHead.sq                # NEW — Feature B queries
│   │   ├── Standings.sq                 # NEW — Feature C inputs
│   │   └── Records.sq                   # NEW — Feature D queries
│   └── kotlin/dev/pitwall/
│       ├── domain/
│       │   ├── HeadToHead.kt            # NEW — pure career/teammate H2H (reuses symmetricGapPct)
│       │   ├── TitleCalculator.kt       # NEW — pure permutation engine + era points tables
│       │   └── Records.kt               # NEW — pure leaderboard shaping (mostly passthrough)
│       ├── data/
│       │   ├── BrowseRepository.kt      # NEW — Feature A
│       │   ├── HeadToHeadRepository.kt  # NEW — Feature B
│       │   ├── TitleRepository.kt       # NEW — Feature C
│       │   └── RecordsRepository.kt     # NEW — Feature D
│       ├── ui/
│       │   ├── browse/{SeasonsScreen,RacesScreen,RaceResultScreen,StandingsScreen}.kt + ViewModels
│       │   ├── h2h/{HeadToHeadScreen}.kt + ViewModel
│       │   ├── title/{TitleCalculatorScreen}.kt + ViewModel
│       │   └── records/{RecordsScreen,OnThisDayScreen}.kt + ViewModels
│       ├── nav/AppNav.kt                # NEW — replaces the 2-tab shell with a real nav graph
│       └── di/Modules.kt               # (existing) — add the new repos + ViewModels
└── commonTest/kotlin/dev/pitwall/domain/
    ├── HeadToHeadTest.kt               # NEW
    ├── TitleCalculatorTest.kt          # NEW
    └── RecordsTest.kt                  # NEW
```

**Build-file impact:** none beyond Phase 0 — no new Gradle dependencies. (Charts/Vico are a Phase-2 concern; all four screens here are list/table UIs buildable with Material 3 only.)

---

# Feature A — Results & Standings Browse

**Offline: YES, fully.** Tables/views: `season`, `race`, `grand_prix`, `circuit`, `race_result`, `qualifying_result`, `sprint_race_result`, `sprint_qualifying_result`, `starting_grid_position`, `season_driver_standing`, `season_constructor_standing`, `race_driver_standing`, `race_constructor_standing`, `driver`, `constructor`.

**Navigation:** Seasons list → Races in a season → Race detail (sub-tabs: Qualifying / [Sprint Qualifying] / [Sprint] / Race) ↔ Standings (drivers / constructors) for that season.

---

## Task 1: `Browse.sq` — the schema-grounded queries (no Kotlin yet)

**Files:** Create `composeApp/src/commonMain/sqldelight/dev/pitwall/db/Browse.sq`

> SQLDelight note: as in Phase 0, the views/tables already exist in the bundled DB. Declare only the **labelled SELECTs** in the new `.sq` files (the `CREATE` statements for codegen typing live once in the existing `F1db.sq`; add any missing `CREATE VIEW` placeholders there if a query references a column not yet declared — match the live view column list from FACT 1). Never call `Schema.create()`.

- [ ] **Step 1: Seasons list**
```sql
selectSeasonsDesc:
SELECT year FROM season ORDER BY year DESC;
```
Returns 1950–2026 (77 rows on this bundle).

- [ ] **Step 2: Races in a season** (the `race` table has no display name beyond `official_name`; join `grand_prix` + `circuit`)
```sql
racesInSeason:
SELECT r.id, r.round, r.date, r.official_name, gp.name AS gp_name,
       c.name AS circuit_name, c.place_name, r.qualifying_format,
       r.sprint_qualifying_format,
       (SELECT COUNT(*) FROM race_result rr WHERE rr.race_id = r.id) AS result_count
FROM race r
JOIN grand_prix gp ON gp.id = r.grand_prix_id
JOIN circuit c ON c.id = r.circuit_id
WHERE r.year = :year
ORDER BY r.round;
```
`result_count = 0` ⇒ **scheduled, not yet run** (the in-progress-season detector — see Edge case A1). `sprint_qualifying_format IS NOT NULL` ⇒ **sprint weekend** → show a sprint badge. (Verified: 2026 has 22 rounds, 6 of them sprint weekends, with `sprint_qualifying_format='SPRINT_SHOOTOUT'`.)

- [ ] **Step 3: Race result** (order by `position_display_order`, per FACT-1 corollary; use the view's renamed columns)
```sql
raceResult:
SELECT rr.position_display_order, rr.position_text, rr.driver_id, d.full_name,
       rr.constructor_id, con.name AS constructor_name, rr.grid_position_number,
       rr.points, rr.time, rr.gap, rr.laps, rr.reason_retired,
       rr.fastest_lap, rr.pole_position
FROM race_result rr
JOIN driver d ON d.id = rr.driver_id
JOIN constructor con ON con.id = rr.constructor_id
WHERE rr.race_id = :raceId
ORDER BY rr.position_display_order;
```

- [ ] **Step 4: Qualifying result** (COALESCE headline time per FACT 2)
```sql
qualifyingResult:
SELECT q.position_display_order, q.position_text, q.driver_id, d.full_name,
       q.constructor_id, q.q1_millis, q.q2_millis, q.q3_millis,
       COALESCE(q.q3_millis, q.q2_millis, q.q1_millis, q.time_millis) AS best_millis, q.gap
FROM qualifying_result q
JOIN driver d ON d.id = q.driver_id
WHERE q.race_id = :raceId
ORDER BY q.position_display_order;
```
Show Q1/Q2/Q3 columns when populated (knockout era 2006+); pre-2006 single/two-session formats only `best_millis` is meaningful.

- [ ] **Step 5: Sprint result + sprint quali** (only surfaced when rows exist — Edge case A3)
```sql
sprintRaceResult:
SELECT sr.position_display_order, sr.position_text, sr.driver_id, d.full_name,
       sr.constructor_id, con.name AS constructor_name, sr.points, sr.gap, sr.reason_retired
FROM sprint_race_result sr
JOIN driver d ON d.id = sr.driver_id
JOIN constructor con ON con.id = sr.constructor_id
WHERE sr.race_id = :raceId
ORDER BY sr.position_display_order;

sprintQualifyingResult:
SELECT sq.position_display_order, sq.position_text, sq.driver_id, d.full_name,
       COALESCE(sq.q3_millis, sq.q2_millis, sq.q1_millis, sq.time_millis) AS best_millis
FROM sprint_qualifying_result sq
JOIN driver d ON d.id = sq.driver_id
WHERE sq.race_id = :raceId
ORDER BY sq.position_display_order;
```

- [ ] **Step 6: Final season standings**
```sql
seasonDriverStanding:
SELECT s.position_text, s.driver_id, d.full_name, s.points, s.championship_won
FROM season_driver_standing s
JOIN driver d ON d.id = s.driver_id
WHERE s.year = :year
ORDER BY s.position_display_order;

seasonConstructorStanding:
SELECT s.position_text, s.constructor_id, con.name AS constructor_name, s.points, s.championship_won
FROM season_constructor_standing s
JOIN constructor con ON con.id = s.constructor_id
WHERE s.year = :year
ORDER BY s.position_display_order;
```

- [ ] **Step 7: Commit** (`feat: Browse.sq — results/standings/schedule queries (offline f1db)`).

---

## Task 2: Browse domain models + repository

**Files:** `data/BrowseRepository.kt`; small data classes (reuse SQLDelight-generated row types directly where possible — map to domain only where the UI needs derived fields like "is this race run yet").

- [ ] **Step 1: Repository** wraps the queries; expose:
  - `seasons(): List<Int>`
  - `races(year): List<RaceListItem>` where `RaceListItem` adds `val isRun: Boolean = result_count > 0` and `val isSprintWeekend: Boolean = sprint_qualifying_format != null`.
  - `availableSessions(raceId): Set<SessionKind>` — query which of `{QUALIFYING, SPRINT_QUALIFYING, SPRINT, RACE}` have rows, to drive the sub-tabs (Edge case A3). Implement as four cheap `COUNT(*) > 0` checks or a single `race_data` `type`-group query.
  - `raceResult(raceId)`, `qualifyingResult(raceId)`, `sprintRaceResult(raceId)`, `sprintQualifyingResult(raceId)`.
  - `driverStandings(year)`, `constructorStandings(year)`.
- [ ] **Step 2: Format helpers** (pure, testable): `millisToLapTime(Long?): String` → `"1:21.345"` / `"—"` for null; `formatGap(String?)`. Put these in `domain/` so they are unit-tested and shared with Feature B.
- [ ] **Step 3: Commit.**

---

## Task 3: Browse ViewModels + screens (TDD only on the pure format/derive helpers)

> UI composables are eyeballed, not unit-tested (matches Phase-0 discipline). The **pure helpers** (`isRun`, `availableSessions` set-shaping, `millisToLapTime`) get tests; the screens are verified by running the app (Task 15).

- [ ] **Step 1: `SeasonsScreen`** — `LazyColumn` of years (desc), tap → Races. Header chip "data as of f1db `vYYYY.R.M`" (Edge case A1 reminder to the user).
- [ ] **Step 2: `RacesScreen`** — `LazyColumn` of `RaceListItem`. Each row: round, GP name, circuit + place, date. Badges: **Sprint** (when `isSprintWeekend`), **Upcoming** (when `!isRun`). Tap a run race → `RaceResultScreen`; an upcoming race shows a disabled/"scheduled" state, not an empty results table.
- [ ] **Step 3: `RaceResultScreen`** — a `TabRow` whose tabs are exactly `availableSessions(raceId)` in canonical order (Qualifying, Sprint Qualifying, Sprint, Race). Each tab renders the matching list. Race tab columns: pos (`position_text` verbatim), driver, constructor, +`reason_retired` shown for DNF rows, points, FL/pole markers. Quali tab: pos, driver, Q1/Q2/Q3 (when populated) + best.
- [ ] **Step 4: `StandingsScreen`** — a two-tab (Drivers / Constructors) `LazyColumn`; rows show `position_text`, name, points, a crown marker when `championship_won=1`.
- [ ] **Step 5: Wire into DI + nav** (deferred to Task 15 for the global nav graph; for now expose the ViewModels in `appModule`).
- [ ] **Step 6: Commit.**

**Feature-A edge cases (each must be handled, not assumed):**

- **A1 — In-progress season.** Do **not** treat `max(season_driver_standing.year)` as a finished season. A race is "run" iff `race_result` rows exist for it (`result_count`). 2026 on this bundle has all 22 rounds in `race` but rounds 7–22 have `result_count=0`. The Races screen must show those as **Upcoming**, and the final-standings screen for an in-progress year should label itself "standings after round N" (N = max round with results) or fall back to the live `race_driver_standing` snapshot (shared with Feature C).
- **A2 — Non-numeric positions.** `position_text` ∈ {DNF (8,748), DNQ (1,041), DNS (381), DNPQ (338), NC (199), DSQ (161), EX}. Render verbatim. A `DSQ` can even appear in season standings (1997 Schumacher) — the standings row renderer must not assume an integer.
- **A3 — Sprint sub-tabs are conditional.** Show a session tab only when its rows exist. 2026's 6 sprint rounds (China, Miami, Canada, Britain, Netherlands, Singapore) get all four tabs; a normal weekend gets two (Qualifying, Race).
- **A4 — Pre-qualifying / DNPQ** (early-90s supersaturated grids: `pre_qualifying_result`, 647 rows) — **optional**, do not block on it. If shown, it is a fifth conditional tab.

---

# Feature B — Career Head-to-Head

**Offline: YES, fully.** Tables: `qualifying_result`, `race_result`, `race` (year/era grouping), `driver`, `constructor`, `season_entrant_driver` (seat/era labels). Optional context: `driver_family_relationship`.

**Teammate model (verified):** teammates = *same `race_id` + same `constructor_id` + different `driver_id`*. This naturally handles mid-career team changes and partial seasons. (Verified Hamilton–Rosberg share only Mercedes 2013–2016; Hamilton's full teammate list — Bottas 100, Rosberg 78, Russell 68, Button 58, Kovalainen 35, Leclerc 30, Alonso 17 — all surface cleanly.)

**Two modes the screen offers:**
1. **Career totals** — any two drivers, NOT necessarily teammates (counts across all *common races*).
2. **Teammate-era-aware** — restrict to `a.constructor_id = b.constructor_id`, grouped by `(year, constructor_id)` so each shared stint renders separately. **Reuses the hero's symmetric-gap math** (`symmetricGapPct`, `lastCommonSegment`, `median`).

---

## Task 4: `HeadToHead.sq`

**Files:** Create `composeApp/src/commonMain/sqldelight/dev/pitwall/db/HeadToHead.sq`

- [ ] **Step 1: Driver search** (powers the two pickers)
```sql
searchDrivers:
SELECT id, full_name, total_race_wins, total_championship_wins
FROM driver
WHERE full_name LIKE '%' || :query || '%'
ORDER BY total_race_starts DESC
LIMIT 50;
```

- [ ] **Step 2: Precomputed career totals for the side-by-side header** (no aggregation)
```sql
driverCareerTotals:
SELECT id, full_name, total_race_wins, total_pole_positions, total_podiums,
       total_fastest_laps, total_points, total_championship_wins, total_race_starts
FROM driver WHERE id = :driverId;
```

- [ ] **Step 3: Common quali sessions, COALESCE-correct (career mode)** — return raw rows for both drivers on shared `race_id`s, let the pure engine count. (Doing the count in SQL is possible but the engine already owns the median/H2H math; keep SQL thin.)
```sql
commonQualifyingRows:
SELECT q.race_id, r.year, q.driver_id, q.constructor_id,
       COALESCE(q.q3_millis, q.q2_millis, q.q1_millis, q.time_millis) AS best_millis
FROM qualifying_result q
JOIN race r ON r.id = q.race_id
WHERE q.driver_id IN (:driverA, :driverB)
  AND q.race_id IN (SELECT race_id FROM qualifying_result WHERE driver_id = :driverA
                    INTERSECT
                    SELECT race_id FROM qualifying_result WHERE driver_id = :driverB)
ORDER BY r.year, q.race_id;
```

- [ ] **Step 4: Common race finishes** (both classified — `position_number IS NOT NULL`)
```sql
commonRaceFinishes:
SELECT rr.race_id, r.year, rr.driver_id, rr.constructor_id, rr.position_number
FROM race_result rr
JOIN race r ON r.id = rr.race_id
WHERE rr.driver_id IN (:driverA, :driverB)
  AND rr.position_number IS NOT NULL
  AND rr.race_id IN (SELECT race_id FROM race_result WHERE driver_id = :driverA
                     INTERSECT
                     SELECT race_id FROM race_result WHERE driver_id = :driverB)
ORDER BY r.year, rr.race_id;
```

- [ ] **Step 5: Seat-era labels** (to distinguish a full-season teammate battle from a mid-year reserve swap — Edge case B4)
```sql
sharedSeats:
SELECT year, constructor_id, driver_id, rounds
FROM season_entrant_driver
WHERE driver_id IN (:driverA, :driverB)
ORDER BY year;
```

- [ ] **Step 6: Commit.**

---

## Task 5: `domain/HeadToHead.kt` — pure engine (TDD)

**Files:** `domain/HeadToHead.kt`; `commonTest/.../HeadToHeadTest.kt`

- [ ] **Step 1: Write the failing test.** Cover:
  - **Career quali H2H** counts: feed rows where A beats B on shared races → `qualiWinsA`/`qualiWinsB`/`commonSessions` correct. (Mirror the verified real number as a smoke test in a separate DB-backed test if desired: Hamilton vs Alonso ⇒ 340 common quali sessions, **0** under a broken `time_millis`-only path.)
  - **Race-finish H2H** counts only rows where both classified.
  - **Teammate-era grouping**: rows for two same-constructor drivers across two years → two `TeammateStint` entries, each with sessions, `aAhead`, and median symmetric gap (reusing `symmetricGapPct`/`median`).
  - **Non-teammate guard**: when the two drivers share no `constructor_id` on any common race, `teammateStints` is empty and a `directGapComputable=false` flag is set (Edge case B1 / spec §4.5).

- [ ] **Step 2: Run to verify it fails.**

- [ ] **Step 3: Implement.** Suggested shapes (reuse existing engine fns):
```kotlin
data class CareerH2H(
    val commonQualiSessions: Int, val qualiWinsA: Int, val qualiWinsB: Int,
    val commonRaces: Int, val raceWinsA: Int, val raceWinsB: Int,
)
data class TeammateStint(
    val year: Int, val constructorId: String, val sessions: Int,
    val aAhead: Int, val medianGapPctA: Double,   // reuses median(symmetricGapPct(...))
)
data class HeadToHeadResult(
    val career: CareerH2H,
    val teammateStints: List<TeammateStint>,     // empty if never teammates
    val directGapComputable: Boolean,            // false for cross-era / never-teammate pairs
)
fun computeHeadToHead(
    qualiRows: List<H2HQualiRow>,    // race_id, year, driverId, constructorId, bestMillis
    raceRows: List<H2HRaceRow>,      // race_id, year, driverId, constructorId, positionNumber
    a: String, b: String,
): HeadToHeadResult
```
Career mode: join the two drivers' rows on `race_id`, count `a.best < b.best` etc. (skip NULL bests — FACT 2 guarantees those exist). Teammate mode: keep only `race_id`s where both share a `constructorId`, group by `(year, constructorId)`, and per group compute `median(rows.map { symmetricGapPct(a.best, b.best) * -1 })` and the ahead-count — **identical math to the hero**, so factor the per-pair gap step into a shared helper rather than copying.

- [ ] **Step 4: Run to verify pass. Step 5: Commit** (`feat: pure career/teammate H2H engine (TDD), reuses hero gap math`).

---

## Task 6: H2H repository + screen

**Files:** `data/HeadToHeadRepository.kt`; `ui/h2h/HeadToHeadScreen.kt` + ViewModel.

- [ ] **Step 1: Repository** — `search(query)`, `totals(driverId)`, and `compare(a, b): HeadToHeadResult` (loads `commonQualifyingRows` + `commonRaceFinishes` + `sharedSeats`, maps, calls `computeHeadToHead`).
- [ ] **Step 2: Screen** — two search-backed driver pickers; then:
  - **Side-by-side totals card** from `driverCareerTotals` (wins / poles / podiums / FL / points / titles / starts) — straight from precomputed `driver.*` columns, no aggregation.
  - **Career H2H block**: "Qualifying X–Y · Races X–Y" over common events.
  - **Per-shared-stint table** (only when `teammateStints` non-empty): year · constructor · sessions · A-ahead · median symmetric gap, each row carrying a **"same car"** badge (spec §4.1 controlled comparison).
  - When `directGapComputable=false` (Fangio vs Verstappen): show totals side-by-side, label "Never teammates — no same-car comparison" and **do not** render any gap (spec §4.5 transitive-only rule).
- [ ] **Step 3: Wire DI; defer nav to Task 15. Step 4: Commit.**

**Feature-B edge cases:**

- **B1 — Never-overlapped / cross-era drivers:** career totals still render side-by-side; **no direct on-track gap** (spec §4.5). The `directGapComputable=false` flag enforces this.
- **B2 — FACT 2 coalesce is mandatory** or modern-era comparisons silently return zero (verified: Hamilton-vs-Alonso 0 → 340).
- **B3 — Shared drives (1950s):** a `race_id` can legitimately list two drivers in the same car (127 `shared_car=1` rows exist). The teammate join treats them as teammates, which is defensible — footnote it for pre-1960 stints so users don't read a shared-car entry as a head-to-head battle.
- **B4 — Partial seasons / reserve swaps:** use `season_entrant_driver.rounds` (e.g. `"1-5"`, `"12,13"`) to label which rounds a driver actually held the seat, so a mid-year replacement isn't presented as a full-season teammate battle.
- **B5 — Format-era mixing:** never sum across format eras (2003 single-lap vs knockout). Always group by `year` — the engine already does, and the UI must not aggregate stints across years into one gap number.

---

# Feature C — Title-Permutation Calculator ("who can mathematically still win")

**Offline: YES for the math.** Current standings + remaining rounds come from the bundled f1db; freshness comes from re-bundling each f1db release (a `DATASET_VERSION` bump), **not** a network call — keeping the `$0`/offline constraint. (An optional jolpica top-up between releases is a nicety, explicitly out of scope here per spec §3.)

**Scope decision (correctness-driven):** the forward **projection is scoped to 2010+ seasons**. Pre-2010 (best-N-counting, half-points, top-6-only scoring) makes permutation runs unsound; those seasons render as **"historical view only"** (show standings, no alive/eliminated projection). This is a deliberate scope cut, surfaced in-app.

---

## Task 7: `Standings.sq` — calculator inputs

**Files:** Create `composeApp/src/commonMain/sqldelight/dev/pitwall/db/Standings.sq`

- [ ] **Step 1: Current points** (latest per-race cumulative snapshot — the live-during-season source)
```sql
currentDriverStandings:
SELECT s.position_display_order, s.position_text, s.driver_id, d.full_name, s.points
FROM race_driver_standing s
JOIN driver d ON d.id = s.driver_id
WHERE s.race_id = (
  SELECT rds.race_id FROM race_driver_standing rds
  JOIN race r ON r.id = rds.race_id
  WHERE r.year = :year
  ORDER BY r.round DESC LIMIT 1)
ORDER BY s.position_display_order;
-- constructors: race_constructor_standing, same shape
```
**Verified on this bundle (2026):** Antonelli 156, Hamilton 90, Russell 88, Leclerc 75, Piastri 58, Norris 58.

- [ ] **Step 2: Remaining rounds & sprints** (drives `max_remaining`)
```sql
remainingRounds:
SELECT
 (SELECT COUNT(*) FROM race
    WHERE year = :year AND id NOT IN (SELECT DISTINCT race_id FROM race_result)) AS remaining_gps,
 (SELECT COUNT(*) FROM race
    WHERE year = :year AND sprint_qualifying_format IS NOT NULL
      AND id NOT IN (SELECT DISTINCT race_id FROM race_result)) AS remaining_sprints;
```
**Verified (2026 as bundled): 16 remaining GPs, 3 remaining sprints.**

- [ ] **Step 3: Commit.**

---

## Task 8: `domain/TitleCalculator.kt` — pure engine + era points tables (TDD)

**Files:** `domain/TitleCalculator.kt`; `commonTest/.../TitleCalculatorTest.kt`

- [ ] **Step 1: Failing test.** Cases:
  - **Max remaining (driver):** `remaining_gps=16, remaining_sprints=3` ⇒ `16*25 + 3*8 = 424`. (Verified target.)
  - **Max remaining (constructor):** per-GP max `25+18=43`, per-sprint `8+7=15` (two cars score) ⇒ `16*43 + 3*15 = 733`.
  - **Alive (simple headline):** driver alive iff `points[d] + maxRemaining >= points[leader]`.
  - **Alive (strict #1):** alive iff `points[d] + maxRemaining >= max over rivals r of points[r]`.
  - **No FL point for 2025+:** the 2026 points table must NOT add +1; a pre-2025 table (2019–2024) may.
  - **Sprint era select:** 2022+ sprint = 8-7-6-5-4-3-2-1; 2021 sprint = 3-2-1 (top 3 only). (Verified: 2021 race 1045 stored Verstappen 3 / Hamilton 2 / Bottas 1.)
  - **Clinch:** leader has clinched when `lead_over_2nd > maxRemaining_for_2nd`.

- [ ] **Step 2: Run to verify fail.**

- [ ] **Step 3: Implement.** Era-selected scoring tables keyed by season:
```kotlin
enum class TitleStatus { ALIVE, ELIMINATED }
data class PointsSystem(val gpPoints: IntArray, val sprintPoints: IntArray, val fastestLapPoint: Int)

fun pointsSystemFor(year: Int): PointsSystem = when {
    year >= 2025 -> PointsSystem(GP_25_18, SPRINT_8_7, fastestLapPoint = 0) // FL abolished after 2024
    year in 2022..2024 -> PointsSystem(GP_25_18, SPRINT_8_7, fastestLapPoint = 1)
    year == 2021 -> PointsSystem(GP_25_18, SPRINT_3_2_1, fastestLapPoint = 1)
    year in 2010..2020 -> PointsSystem(GP_25_18, NO_SPRINT, fastestLapPoint = if (year >= 2019) 1 else 0)
    else -> error("projection unsupported pre-2010") // caller gates to historical-only view
}
// per-GP max = max(gpPoints) [+ fastestLapPoint]; per-sprint max = max(sprintPoints)
// constructor per-GP max = gpPoints[0] + gpPoints[1]; per-sprint = sprintPoints[0] + sprintPoints[1]
fun maxRemaining(remainingGps: Int, remainingSprints: Int, sys: PointsSystem, isConstructor: Boolean): Int
fun titleAliveSimple(points: Map<String, Double>, remaining: Int): Map<String, TitleStatus>
fun titleAliveStrict(points: Map<String, Double>, remaining: Int): Map<String, TitleStatus>
fun clinchScenario(points: Map<String, Double>, remaining: Int): String?  // "Leader clinches if lead > N"
```
Sprint tables: `SPRINT_8_7 = [8,7,6,5,4,3,2,1]`, `SPRINT_3_2_1 = [3,2,1]`. The FL point is added to `maxRemaining` only when `fastestLapPoint > 0` (and even then it's a per-GP availability nuance — keep it simple: add `fastestLapPoint` to per-GP max for the eras that had it). **2025+ must add zero** (abolished after 2024 Singapore).

- [ ] **Step 4: Run to verify pass. Step 5: Commit** (`feat: pure title-permutation engine, era-selected points tables (TDD)`).

---

## Task 9: Title calculator repository + screen

**Files:** `data/TitleRepository.kt`; `ui/title/TitleCalculatorScreen.kt` + ViewModel.

- [ ] **Step 1: Repository** — `currentStandings(year)`, `remaining(year)`, then call the engine. Detect `year < 2010` → return a `HistoricalOnly` state.
- [ ] **Step 2: Screen** — Drivers/Constructors toggle. For 2010+:
  - Per-driver row: **Alive / Eliminated** badge, **max reachable** points, **gap to leader**.
  - Header: "X GPs and Y sprints remaining · max +Z available" (for 2026: 16 GPs, 3 sprints, +424).
  - **Clinch line**: "Leader clinches the title if they outscore P2 by more than N over the remaining rounds."
  - Two views toggle: **simple headline** ("can catch the current leader if leader scores zero") vs **strict** ("can still finish #1 over all rivals").
  - **"Data as of f1db `vYYYY.R.M` (through round N)"** stamp — this is the one feature where staleness matters between releases; make it impossible to miss (Risk R1).
  - For `year < 2010`: standings table + a banner "Permutation projection available for 2010-onward seasons only (scoring-system complexity)."
- [ ] **Step 3: Wire DI; nav in Task 15. Step 4: Commit.**

**Feature-C edge cases:**

- **C1 — Points-system era selection:** stored `race_result.points` already reflect each era; the *projection* needs the rule table. Supported for projection: 2010+. Pre-2010 = historical view only.
- **C2 — Sprint era (2021+ only).** 2021 = 3-2-1; 2022+ = 8→1. Era-select the sprint table (verified 2021 stored 3-2-1).
- **C3 — In-progress detection drives `remaining_*`.** A part-complete round (quali done, race pending) still counts as a remaining GP for points-available purposes — the `NOT IN (SELECT race_id FROM race_result)` test handles this (a race with no `race_result` rows is "remaining").
- **C4 — Tie-break / countback** ("most wins" decides ties) is **not** needed for "mathematically alive"; it *is* needed for precise "can clinch." **Defer countback to v2** (flagged, not silently dropped).
- **C5 — Constructor max doubles per round** (two cars score): per-GP 43, per-sprint 15. Tested in Task 8.

---

# Feature D — Records Book + On-This-Day

**Offline: YES, fully.** Prefer the **precomputed `driver`/`constructor` totals** for leaderboards (fast, canonical, already encode f1db's shared-drive/half-points conventions). Use computed-from-`race_result` only for filtered/era-sliced views.

---

## Task 10: `Records.sq`

**Files:** Create `composeApp/src/commonMain/sqldelight/dev/pitwall/db/Records.sq`

- [ ] **Step 1: Precomputed leaderboards** (one query per metric, no GROUP BY):
```sql
topDriversByWins:
SELECT id, full_name, total_race_wins FROM driver
WHERE total_race_wins > 0 ORDER BY total_race_wins DESC LIMIT :limit;
-- analogous: total_pole_positions, total_podiums, total_fastest_laps,
--   total_championship_wins, total_points, total_race_starts, total_grand_slams,
--   total_driver_of_the_day, total_sprint_race_wins
-- constructors: total_race_wins, total_pole_positions, total_podiums,
--   total_1_and_2_finishes, total_podium_races, total_points, total_championship_wins
```
**Verified canonical leaderboard:** Hamilton 105 wins / 104 poles / 205 podiums / 68 FL; Schumacher 91 / 68 / 155 / 77; Verstappen 71 / 48 / 128 / 37.

- [ ] **Step 2: Computed/era-sliced** (for "wins in the V6-hybrid era 2014+", "poles at Monaco", etc.). **Poles use the FLAG, not quali-P1 (correction #2):**
```sql
winsFiltered:
SELECT rr.driver_id, d.full_name, COUNT(*) AS wins
FROM race_result rr JOIN race r ON r.id = rr.race_id JOIN driver d ON d.id = rr.driver_id
WHERE rr.position_number = 1
  AND (:yearFrom IS NULL OR r.year >= :yearFrom) AND (:yearTo IS NULL OR r.year <= :yearTo)
GROUP BY rr.driver_id ORDER BY wins DESC LIMIT :limit;

polesFiltered:
SELECT rr.driver_id, d.full_name, SUM(CASE WHEN rr.pole_position = 1 THEN 1 ELSE 0 END) AS poles
FROM race_result rr JOIN race r ON r.id = rr.race_id JOIN driver d ON d.id = rr.driver_id
WHERE (:yearFrom IS NULL OR r.year >= :yearFrom) AND (:yearTo IS NULL OR r.year <= :yearTo)
GROUP BY rr.driver_id ORDER BY poles DESC LIMIT :limit;

fastestLapsFiltered:
SELECT rr.driver_id, d.full_name, SUM(CASE WHEN rr.fastest_lap = 1 THEN 1 ELSE 0 END) AS fl
FROM race_result rr JOIN race r ON r.id = rr.race_id JOIN driver d ON d.id = rr.driver_id
WHERE (:yearFrom IS NULL OR r.year >= :yearFrom) AND (:yearTo IS NULL OR r.year <= :yearTo)
GROUP BY rr.driver_id ORDER BY fl DESC LIMIT :limit;
```

- [ ] **Step 3: On-This-Day**
```sql
onThisDay:
SELECT r.year, r.official_name, gp.name AS gp_name, c.name AS circuit_name,
       d.full_name AS winner, con.name AS constructor_name
FROM race r
JOIN grand_prix gp ON gp.id = r.grand_prix_id
JOIN circuit c ON c.id = r.circuit_id
JOIN race_result rr ON rr.race_id = r.id AND rr.position_number = 1
JOIN driver d ON d.id = rr.driver_id
JOIN constructor con ON con.id = rr.constructor_id
WHERE strftime('%m-%d', r.date) = :mmdd
ORDER BY r.year DESC;
```

- [ ] **Step 4: Commit.**

---

## Task 11: `domain/Records.kt` (thin) + repository + screens

**Files:** `domain/Records.kt` (mostly passthrough/ranking shaping — minimal logic, light tests on rank-tie handling and the era-filter switch); `data/RecordsRepository.kt`; `ui/records/{RecordsScreen,OnThisDayScreen}.kt` + ViewModels.

- [ ] **Step 1: Repository** — `leaderboard(metric, filter)` where `filter=None` returns the precomputed query and any era/circuit filter flips to the computed query. `onThisDay(mmdd)`.
- [ ] **Step 2: `RecordsScreen`** — a `TabRow` of metrics: Wins / Poles / Podiums / Fastest Laps / Championships / Points / Starts / Grand Slams. Each tab a ranked list (name + count). Optional era filter (year-from/to) that flips precomputed→computed. A drivers/constructors toggle.
- [ ] **Step 3: `OnThisDayScreen`** — today's `mmdd` by default, list of "On this day in YYYY: <winner> won the <GP> at <circuit>."
- [ ] **Step 4: Wire DI; nav in Task 15. Step 5: Commit.**

**Feature-D edge cases (each surfaced in-app where it could mislead):**

- **D1 — Pole ≠ quali-P1.** Use `race_result.pole_position` (or precomputed `total_pole_positions`), never `qualifying_result.position_number=1`. Verified off-by-one+: Hamilton 105 quali-P1 vs **104** canonical; Schumacher 69 vs 68.
- **D2 — Fastest-lap leaderboard counts the *achievement*, not the (removed) FL point.** The FL championship point existed only 1950–59 and 2019–2024 and was abolished after 2024; `total_fastest_laps`/the `fastest_lap` flag count laps set regardless. Add an in-app note so users don't conflate the records count with the points-era nuance (ties into Feature C's no-FL-point-for-2025 rule).
- **D3 — Shared drives (1950s):** 127 `race_result` rows have `shared_car=1`; points were split (verified fractional points exist: 0.14, 1.33, 3.14) and a single winning car can map to two drivers. **Prefer precomputed `driver.total_race_wins`** (it already encodes f1db's shared-drive convention) over computing `position_number=1` for the headline leaderboards; for the *filtered* computed views, either accept the small double-count or dedupe by `(race_id, position_number)` and footnote it.
- **D4 — Half-points races** (e.g. 2021 Belgian GP winner scored 12.5 — verified) affect points/season tallies, not win/pole counts. Precomputed `total_points` already accounts for it.
- **D5 — Sprint wins are separate** (`total_sprint_race_wins`); never fold them into GP wins.

---

# Cross-cutting tasks

## Task 12: `nav/AppNav.kt` — replace the Phase-0 two-tab shell with a real nav graph

**Files:** `nav/AppNav.kt`; modify `App.kt`.

The Phase-0 `App()` is a two-tab `NavigationBar` (Driver vs Car / About). Phase-1 needs five top-level destinations plus drill-down. Keep it dependency-light (no extra Gradle libs): use a small sealed-class screen state + Compose `NavigationBar` / `NavigationRail` for top-level and a back-stack `mutableStateList` for drill-down, **or** adopt `androidx.navigation` Compose multiplatform artifact **only if** it is already pulled transitively (verify at build — do not add a dependency just for this; a hand-rolled back stack is acceptable for five screens).

- [ ] **Step 1:** Top-level destinations: **Driver vs Car** (hero, existing) · **Browse** (Feature A) · **Compare** (Feature B) · **Title Race** (Feature C) · **Records** (Feature D) · **About/Data** (existing `LicensesScreen`). On macOS a `NavigationRail` reads better than a bottom bar; gate by a simple `isCompact` width check (`expect/actual` or `BoxWithConstraints`).
- [ ] **Step 2:** Drill-down stacks: Browse → Races → Race detail / Standings.
- [ ] **Step 3:** Wire every ViewModel through Koin `koinViewModel()`.
- [ ] **Step 4: Commit.**

## Task 13: Fix the hero's displayed quali time (the FACT-2 carryover, correction #1)

**Files:** modify `F1db.sq` `qualifyingForSeason`.

The Phase-0 `qualifyingForSeason` selects bare `q.time_millis` as the headline time, which is NULL for 2006+. The hero *engine* already coalesces (so ratings are correct), but if any hero UI displays "the quali time," it will show blanks.

- [ ] **Step 1:** Add `COALESCE(q.q3_millis, q.q2_millis, q.q1_millis, q.time_millis) AS best_millis` to `qualifyingForSeason` and use it for any displayed time. Do not change the engine inputs (it consumes `q1/q2/q3/time` individually and coalesces internally).
- [ ] **Step 2:** Re-run the hero's `:composeApp:desktopTest` (unchanged) + eyeball a 2024 season to confirm times now render. **Step 3: Commit.**

> Coordinate with the team building Phase 0 in parallel: this is a one-line additive change to a query they own. If they have already fixed it, this task is a no-op — verify before editing.

## Task 14: Bump `DATASET_VERSION` + `NOTICE`/about version string (correction #6)

**Files:** `data/DbFile.kt` (`DATASET_VERSION`), `NOTICE`, `LicensesScreen.kt`.

- [ ] **Step 1:** The bundled copy is **ahead of the public `v2026.3.0`**. Determine the actual bundled release (re-dump or read the f1db release notes for the copy you ship) and set the version string everywhere it appears: `NOTICE` ("Bundled f1db release: vYYYY.R.M"), the `LicensesScreen` copy, and the "data as of" stamps in the Title calculator (Task 9) and Records (Task 11).
- [ ] **Step 2:** Bump `DATASET_VERSION` so the copy-on-launch re-extracts the newer DB on existing installs. **Step 3: Commit.**

## Task 15: End-to-end run + eyeball on both targets

- [ ] **Step 1: Desktop** — `:composeApp:run`; click through all five features. Verify: Browse shows 2026 with rounds 7–22 as **Upcoming**; a 2024 race shows correct quali times (FACT-2 fix); Compare(Hamilton, Alonso) shows **340** common quali sessions and no same-car gap (never teammates); Compare(Hamilton, Rosberg) shows the 2013–2016 Mercedes stints with "same car" badges; Title Race(2026) shows Antonelli leading with 156, 16 GPs / 3 sprints / +424 remaining, alive/eliminated badges; Records shows Hamilton 104 poles (not 105); On-This-Day renders for today.
- [ ] **Step 2: Android** — `:composeApp:installDebug`; spot-check the same flows + that the bottom bar (compact) navigation works.
- [ ] **Step 3: Commit + tag** (`feat: Phase 1 complete — browse, H2H, title calculator, records (offline, $0)`; tag `v0.2.0-phase1`).

---

## Self-review

**Spec coverage (Phase 1, remaining four features):**
- Results/standings/schedule browse (§5) → Tasks 1–3; in-progress-season detection, sprint sub-tabs, verbatim `position_text`, `position_display_order` ordering, FACT-2 quali coalesce.
- Career/teammate H2H (§5, §4.1, §4.5) → Tasks 4–6; reuses the hero's symmetric-gap engine, career + teammate-era modes, transitive-only rule for non-teammates, seat-era labels.
- Title-permutation calculator (§5) → Tasks 7–9; era-selected points tables, no FL point 2025+, sprint era select, constructor double-max, 2010+ projection scope, clinch line.
- Records & On-This-Day (§5) → Tasks 10–11; precomputed-totals-first, pole=flag, FL-as-achievement note, shared-drive/half-points handling.

**Constraint coverage:**
- **$0 / offline:** all four read bundled f1db only; no jolpica on any path; freshness via re-bundle, not network (spec §3, §10).
- **No-F1 branding:** screen/tab labels and copy describe the app as a companion in prose; no marks as branding; reuses existing disclaimer + `LicensesScreen`.
- **Public repo / CC-BY:** single data source already credited in `NOTICE` + `LicensesScreen`; Task 14 keeps the version string honest; no new attribution obligations.

**Reuse, not duplication:** data path, SQLDelight DB, Koin module, `App()` shell, and the **entire Driver-vs-Car gap engine** are inherited from Phase 0. Feature B factors the per-pair gap step into a shared helper rather than copying the hero's math.

**Every recommendation is schema-grounded and re-verified against the bundled `/tmp/f1db/f1db.db`:** view column names (FACT 1); FACT-2 NULL trap (2024: 478 NULL `time_millis` / 474 segments; Hamilton-vs-Alonso 0 → 340); `position_display_order` vs NULL `position_number`; poles flag (Hamilton 105 quali-P1 vs 104 canonical/precomputed); 2026 standings (Antonelli 156…Norris 58); 16 GPs / 3 sprints remaining; 22 rounds / 6 sprints / `SPRINT_SHOOTOUT`; 127 `shared_car` rows; fractional points (0.14/1.33/3.14/12.5); catalog 915/187/1,171/54, 1950–2026.

**Deferred (named, not dropped):** countback tie-break for "can clinch" (Feature C v2); optional jolpica between-release top-up; pre-2010 title projection (historical view only); pre-qualifying/DNPQ tab (optional); charts/Vico (Phase 2). Race-pace H2H companion stays Phase 2 (needs the FastF1 backend).

## Risks & open decisions

- **R1 — Bundle freshness vs public release.** This copy is ahead of public `v2026.3.0`. The Title calculator and Records are only as current as the bundle. Mitigations: prominent "data as of f1db `vYYYY.R.M` (through round N)" stamps (Tasks 9, 11, 14); re-bundle + `DATASET_VERSION` bump each release (Task 14). Decide whether to pin the *public* `v2026.3.0` for reproducibility or ship the newer local copy — pinning a published release is the defensible OSS choice; document whichever you ship.
- **R2 — Parallel Phase-0 build.** Task 13 touches `qualifyingForSeason`, owned by the Phase-0 stream. Coordinate; treat as no-op if already fixed.
- **R3 — Navigation library.** Do not add `androidx.navigation` (or any dep) solely for five screens unless it is already on the classpath; a hand-rolled back stack keeps the $0/lean profile and avoids version churn (spec §11). Verify at build.
- **R4 — Pre-2010 / shared-drive / half-points correctness.** Scope the title projection to 2010+ and prefer precomputed driver/constructor totals for records to sidestep shared-drive double-counting and half-points; surfaced in-app, not silently swallowed.
