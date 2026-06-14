# PitWall — Design Spec

**Date:** 2026-06-14
**Status:** Approved (2026-06-14) — ready for implementation planning
**Author:** Hartej (with Claude)
**Repo:** public open-source on personal GitHub (`hartejsingh99-cloud`), to live under `~/Downloads/Repos/personal/pitwall`

> A personal Formula 1 companion: race results, deep car/lap/telemetry analysis, historical
> comparisons, a "driver vs car" skill metric, and (eventually) live race-weekend timing —
> running on **Android phone + macOS desktop** from **one shared Kotlin/Compose codebase**,
> backed by **one small always-on API**, built to run at **$0/month**.

---

## 1. Goal & non-negotiables

Build an F1 app for **myself and a few friends** that:

1. Browses races, results, schedules, standings (1950→present).
2. Helps me *understand the car* — lap-by-lap timing and full car telemetry (speed, throttle,
   brake, gear, DRS, position).
3. Makes historical comparisons (career head-to-heads, records).
4. Answers *"how good is the driver vs the car they're in"* — a defensible win-%/skill metric.
5. Grows into a **live race-weekend** experience (live timing + push) plus some "wow" features.

**Hard constraints (these override convenience):**

| Constraint | Decision |
|---|---|
| **Cost** | **$0/month** wherever possible. Self-host everything on a free tier. Only two optional "spend pennies" upgrades, both in the last phase. |
| **Audience** | Private — **me + a few friends**. Sideloaded Android APK + a macOS `.dmg`. **No public Play Store listing.** |
| **Branding** | App name is **"PitWall"** — deliberately no "F1"/"Formula 1"/"FORMULA ONE"/logo in the name, icon, or any listing metadata. Trademark C&Ds are F1's most-executed enforcement and are 100% avoidable. (Describing the app *as* a Formula 1 companion in prose is fine; using the marks as *branding* is not.) |
| **Platforms** | Native via **Kotlin + Compose Multiplatform** → Android + macOS desktop from one codebase. |
| **Repo / hosting** | **Public open-source** repo on personal GitHub (`hartejsingh99-cloud`). MIT for own code; f1db CC-BY-4.0 attribution honored. Code is public (OSS fan-project norm); **the deployed live backend stays private to the friend group** — never a public live-timing URL. |

---

## 2. Architecture at a glance

```
① DATA SOURCES (external)
   f1db (static SQLite, 1950→now)   jolpica-f1 (recent results/schedule)
   FastF1 (telemetry, post-session) OpenF1 / FastF1-live (live timing — Phase 3)
        │
        ▼  ingested & pre-computed by
② BACKEND — FastAPI (Python 3.12), self-hosted on Oracle Cloud Always-Free
   • APScheduler bake jobs (run FastF1 a few hours after each session)
   • Postgres + Redis (in Docker on the same box) — pre-computed, query-ready blobs
   • REST API + SSE endpoint (live) + optional Claude/local-LLM for recaps
        │
        ▼  HTTPS · REST + Server-Sent Events (JSON)
③ SHARED KOTLIN / COMPOSE CORE  (commonMain)
   • Ktor client · kotlinx.serialization · Koin DI · lifecycle ViewModel
   • Repositories + stats engine (Driver-vs-Car, permutations, tyre-deg)
   • Compose UI + Vico charts + Canvas track-maps
   • SQLDelight over a bundled, read-only f1db SQLite
        │
        ▼  compiles to two targets
④ CLIENTS
   📱 Android (FCM push; sideloaded APK)     💻 macOS desktop (.dmg; tray notifications)
```

**Why this shape:** the always-on API decouples clients from data. Adding the macOS client is "another
compile target," not another app. Live timing later is "add an SSE endpoint," not a re-architecture.
Each tier lights up exactly one new data capability, phase by phase, so we never build infra before it's needed.

---

## 3. Data sources — what each gives us (and its limits)

| Source | Provides | Does NOT provide | License / cost | Notes |
|---|---|---|---|---|
| **f1db** ([github.com/f1db/f1db](https://github.com/f1db/f1db)) | All results, qualifying, sprint, grid, pit stops, fastest laps, standings, drivers, constructors, engines, tyres, circuits — **1950→now** | Any telemetry or live timing | **CC-BY-4.0** (commercial OK w/ attribution) | Ships a ready **~15.5 MB SQLite** (`f1db-sqlite.zip`). New release after every race (latest seen: `v2026.6.3`). **Bundle this on-device.** |
| **jolpica-f1** ([api.jolpi.ca/ergast/f1/](https://api.jolpi.ca/ergast/f1/)) | Recent/historical results, **schedule with per-session UTC start times**, standings | Telemetry | Free, no auth | **Ergast successor** (Ergast shut down early 2025). **Rate limit: 4 req/s, 500 req/hr, decreasing** — cache hard, never call per-screen. Volunteer-run (~$45/mo), treat as fragile. |
| **FastF1** ([docs.fastf1.dev](https://docs.fastf1.dev)) v3.8.3 | Lap timing + **car telemetry** (Speed, RPM, nGear, Throttle, Brake, DRS) + position (X/Y/Z) + tyre/weather, **2018→now** | Pre-2018 telemetry; real-time processing | Open-source (Python 3.10+) | Heavy (pandas). **ETL/ingest only — never on the request path.** Telemetry available ~30 min–2 h after a session. |
| **OpenF1** ([openf1.org](https://openf1.org)) | Live car telemetry (3.7 Hz), gaps (4 s), ~3 s latency | — | **CC-BY-NC-SA** (non-commercial); **live data €9.90/mo**, historical free | Phase 3 option. We're non-commercial, so license is fine, but **live is paid** → free fallback is FastF1's recorder. |

**Data-source-per-feature is the key rule:** historical/stats features use **f1db** (bundled, offline, no
rate limit). Telemetry features use **FastF1** (baked post-session). Live uses **OpenF1 or FastF1-live** (Phase 3).
Never route a historical feature through jolpica per-pageview.

> ⚠️ **Telemetry cutoff:** car telemetry only exists **2018+**. Stats/historical features (incl. the hero)
> go back to **1950**; telemetry/ghost-replay are 2018+. The UI must degrade gracefully pre-2018.

---

## 4. Hero feature — "Driver vs Car"

The thing almost no consumer app does well, and exactly what was asked for: *win % of a driver with
respect to their car.* There is **no single accepted number** — the field uses a ladder of methods. We
implement a defensible subset, computable from **f1db + FastF1**, and we **report a range + the method**,
never fake precision.

**4.1 Primary metric — teammate-normalized rating (the controlled comparison).**
Teammates share the same car, so the teammate gap isolates the driver. For each event *e* where driver *i*
and teammate *j* both set a time in their **last common qualifying segment**:

```
quali_gap(e) = 100 × (t_i,e − t_j,e) / ((t_i,e + t_j,e) / 2)      # symmetric %, track-length-normalized
```

- **One-lap rating** = `median_e(−quali_gap(e))` (negative so faster = positive), reported **with the
  head-to-head win count** (# events with gap<0 of N). **Median, not mean** — kills wet-session/red-flag outliers
  (this is the published-media standard).
- **Race-pace companion (Phase 2 enrichment — needs the FastF1 backend):** same symmetric % on median race-lap
  pace (FastF1 `pick_accurate().pick_quicklaps()`, excluding SC/VSC laps), 2018+ only. The Phase 1 hero ships
  on the **qualifying** signal alone (all eras, fully offline); race pace deepens it once Phase 2 exists.

**4.2 Car-adjusted win probability (the headline "win % vs car"):** two defensible constructions —
- **Elo route** (simple, transparent): per weekend, S=1 for the teammate finishing/qualifying ahead;
  `R' = R + K·(S − E)`, `E = 1/(1+10^((R_j−R_i)/400))`, K=64; **exclude any-DNF weekends**. Final rating gap →
  head-to-head win probability.
- **Model route** (rigorous, gives intervals): Bayesian rank-ordered logit with driver + team + season effects
  (van Kesteren & Bergkamp 2023); car-removed win prob = predict with teammate `θ_t` set equal. Quote a credible interval.

**4.3 Report the split, not just a rating.** Published models put the **constructor at 64–88% of result
variance** (method-dependent: 88% Bayesian rank-logit, 64% RAPM ridge). So every headline shows a *range* and
names the estimator.

**4.4 DNF handling (the single biggest swing factor).** Tag each f1db result by retirement reason; classify
**car-fault** (mechanical/PU/hydraulics) vs **driver-fault** (accident/spin/collision). For "win % vs car":
**exclude car-fault DNFs** from the denominator, **keep driver-fault** as losses. State this rule in-app.
*(Caveat: f1db collapses retirements to "Retired" from 2025+, so reason granularity is limited for recent seasons.)*

**4.5 Cross-team / cross-era** is only **transitive** through chains of shared teammates (A>B, B>C ⟹ A>C),
never a direct same-car control. Build a teammate graph; report path length/sample as a confidence proxy; never
present a cross-team win% as if it were a direct comparison.

---

## 5. Feature roadmap — all 15, phased

Each phase is independently shippable; cost grows only when a phase needs it. **F = f1db, J = jolpica, T = FastF1
telemetry, L = live.** Parity: 📱+💻 = both clients; 📱 = phone-only.

### Phase 1 — Stats no other app has · **$0 · offline-first** · ships first
*(Offline-first: hero, career, records, and even recent results work fully offline from the bundled f1db, which
re-ships every release. jolpica is only for between-release schedule/standings freshness, cached when online.)*

| Feature | Data | Parity |
|---|---|---|
| ★ **Driver vs Car** (§4) — qualifying teammate-gap + Elo + DNF handling | F (quali + results) | 📱+💻 |
| Career / teammate head-to-head | F | 📱+💻 |
| Title permutation calculator | F + J(standings) | 📱+💻 |
| Records & On-This-Day | F | 📱+💻 |
| Results / standings / schedule browse | F + J | 📱+💻 |

### Phase 2 — Understand the car · **~$0** (FastF1 bake on the free box)
| Feature | Data | Parity |
|---|---|---|
| Telemetry overlay (speed/throttle/brake/gear/DRS by distance) | T | 📱+💻 |
| Track-dominance mini-sector map | T (X/Y + Distance) | 📱+💻 |
| Delta-time trace | T | 📱+💻 |
| Tyre strategy & degradation (fuel-corrected, 107% + SC/VSC filter, LOWESS) | T | 📱+💻 |
| Race pace & gap evolution | T (laps) | 📱+💻 |
| Ghost-car GPS replay (SC position faked ~500 m ahead of leader) | T (X/Y) | 📱+💻 |

### Phase 3 — Race weekend, live · **$0 path** (FastF1 recorder) or €9.90/mo (OpenF1)
| Feature | Data | Parity |
|---|---|---|
| Live timing board (positions/gaps/last lap/tyres) | L (via SSE) | 📱+💻 |
| Smart push alerts (lights out, red flag, fav-driver pit/podium) | FCM | **📱 only** (desktop = in-app/tray while running) |
| Predictions vs friends | backend | 📱+💻 |

### Phase 4 — Wow polish · **$0 path** (local LLM) or pennies (Claude API)
| Feature | Data | Parity |
|---|---|---|
| AI race recap (ground the LLM in real FastF1/OpenF1 stats to avoid hallucination) | T/L + LLM | 📱+💻 |
| Season "Wrapped" | F + T | 📱+💻 |

---

## 6. Backend design (FastAPI, Python)

- **Golden rule:** **FastF1 NEVER runs on the request path.** `load()` blocks for seconds–tens of seconds and a
  full session is hundreds of MB. It is an **ETL/ingest layer**, not a request dependency.
- **Ingestion:** **APScheduler** (in-process) — not Celery (overkill for one box). A job polls the jolpica
  schedule and, a few hours after each session, runs `session.load()`, pre-computes **downsampled, columnar,
  gzipped** telemetry, and writes query-ready artifacts. Build retry/poll-until-ready (telemetry lands 30 min–2 h late).
- **Storage tiering:** (1) FastF1 disk cache (pickle/requests-cache) on a **persistent volume** = raw layer;
  (2) **Postgres** = one row per driver-lap holding telemetry as gzipped JSON/Parquet + normalized laps/results
  tables; (3) **Redis** = hot read-through cache for the few hot sessions. *(SQLite acceptable for a few users, but
  Postgres is safer once the live sidecar writes concurrently with the API reading.)*
- **Serving telemetry to mobile:** pre-downsampled (low integer Hz / decimate by distance), **parallel arrays
  per channel** (not array-of-objects), gzip/brotli, paginated by driver+lap. Never ship raw frames.
- **Live (Phase 3):** **SSE (`sse-starlette`), not WebSocket** — timing/telemetry is one-way (server→client);
  SSE is lighter, auto-reconnects, no upgrade handshake. Free source = FastF1's SignalR recorder run as an
  **auto-restarting sidecar** (the F1 feed drops every ~2 h → rotate files & dedupe; may now require a **free**
  F1.com account). Paid-but-clean source = OpenF1 live (€9.90/mo). WebSocket only if we later add client→server messaging.
- **Packaging:** one slim `python:3.12-slim` image (uvicorn) + `docker compose` for api + postgres + redis +
  (Phase 3) live-recorder sidecar. Pin `fastf1==3.8.3`, `pandas<3`, `numpy<3`, `scipy<2`.

**Hosting — the $0 choice:** **Oracle Cloud Always-Free** (ARM Ampere A1, up to 4 OCPU / 24 GB RAM, **never
expires**, 10 TB/mo egress). Choose **Mumbai/Hyderabad** home region (low latency for me + better A1 availability
than US regions, which often report "out of capacity"). Trade-off: self-managed OS/Docker, and provisioning may
need retries. *(Paid fallback if Oracle frustrates: Hetzner CAX11 ~€4.99/mo.)* TLS + hostname: **DuckDNS + Caddy/
Let's Encrypt = $0.**

---

## 7. Shared client design (Compose Multiplatform)

- **One `composeApp` KMP module**, three source sets:
  - `commonMain` — `App()` composable, navigation, ViewModels, Ktor client, repositories, **the entire stats
    engine**, Compose UI, charts. (~85–90% of the code.)
  - `androidMain` — `Activity` entry point, FCM, OkHttp Ktor engine, Android file paths.
  - `desktopMain` — `main()`/`Window`, CIO Ktor engine, tray notifications, macOS file paths.
- **Stack (pin latest stable in `libs.versions.toml`; the research flagged fast version churn — verify at build):**

| Concern | Library | Note |
|---|---|---|
| UI framework | **Compose Multiplatform 1.10.x/1.11.x** | Desktop (JVM) target is **Stable**. |
| Language | **Kotlin 2.2.20+** (target 2.3.x) | |
| Networking | **Ktor Client 3.5.0** | `ktor-client-core` in common; **OkHttp** engine (Android) + **CIO** (desktop). *Not* Darwin (that's Kotlin/Native only). |
| JSON | **kotlinx.serialization 1.11.0** | `ignoreUnknownKeys=true` for FastAPI drift. |
| Local DB | **SQLDelight 2.3.2** (`app.cash.sqldelight`) | Chosen over Room because it opens an arbitrary **pre-built read-only** SQLite by path on every target. |
| DI | **Koin 4.2.1** | Hilt is Android-only — can't live in `commonMain`. |
| State | **lifecycle-viewmodel-compose 2.10.0** (`org.jetbrains.androidx.lifecycle`) | Add `kotlinx-coroutines-swing` to `desktopMain` (viewModelScope needs `Dispatchers.Main`). |
| Charts | **Vico** (`com.patrykandpatrick.vico:compose`, 3.x) | Telemetry line charts. |
| Custom drawing | **Compose Canvas / DrawScope** | Circuit track-map + ghost-replay + bespoke overlays. No map SDK — free, identical on both via Skia. |
| Images | **Coil 3.x** (`coil3` + `coil-network-okhttp`) | |
| Resources | **`org.jetbrains.compose.resources`** (`Res.*`) | NOT Android `R.*`. |

- **Bundling f1db (the core data-layer move):** put `f1db.db` in `commonMain/composeResources/files`. On first
  launch `Res.readBytes("files/f1db.db")` → write to a writable path (Android `Context.getDatabasePath()`; macOS
  `~/Library/Application Support/<app>`) → open **read-only** with the SQLDelight driver
  (`AndroidSqliteDriver` / `JdbcSqliteDriver`). **Gate the copy behind a dataset-version constant** so it only
  re-copies when a new f1db release ships. Don't run `Schema.create()`/migrations against the populated file.
- **CC-BY-4.0 compliance:** an in-app "Open-source data / Licenses" screen crediting **F1DB** + linking the CC
  license + noting the bundled release version (`v2026.x.y`) + that data was transformed.

---

## 8. Platform differences (what can't be shared)

| Concern | Android | macOS desktop |
|---|---|---|
| Push notifications | **FCM** (background push works) | **No FCM.** Only `trayState.sendNotification` → Notification Center, **and only while the app is running.** |
| Live transport | Ktor/SSE client (shared in `commonMain`) | same |
| Background reliability | Doze → live needs a **foreground service** | always-on while window open |
| File/cache paths | `Context.cacheDir` etc. | `~/Library/...` — via `expect/actual` |
| Packaging | sideloaded **APK** | **`.dmg`** via `compose.desktop` plugin (`packageDmg`) |
| Build constraint | — | **Must build the `.dmg` on a Mac** (jpackage can't cross-compile; JDK 17+). |

**Free macOS distribution to friends:** ship an **un-notarized `.dmg`** (skip the $99/yr Apple Developer
account); each friend runs `xattr -dr com.apple.quarantine /Applications/<App>.app` once (or right-click → Open).
Under macOS Sequoia 15.1, quarantine removal is the reliable no-cost path. *(If friction matters later: Hydraulic
Conveyor is free for OSS and notarizes from any OS.)*

---

## 9. Legal & compliance (baked-in rules)

1. **Trademark (highest, fully in our control):** no "F1"/"Formula 1"/logo anywhere in branding. C&Ds over
   names are F1's most-executed enforcement.
2. **Public code, private service:** the **source repo is public OSS** (exactly like FastF1/f1db/OpenF1/f1-dash) —
   that's the tolerated norm. But **no public Play Store listing**, and **the deployed live backend stays private**
   to the friend group. F1 tolerates non-commercial open-source fan tools but **IP-blocks public live-timing sites**
   (it took down f1-dash). "Me + a few friends" + public code = tolerated lane; a public *hosted live service* is not.
3. **Data rights:** F1's ToU claims copyright **and database rights** over live timing and restricts to personal,
   non-commercial use. Historical (f1db/jolpica, post-session FastF1) is the safe category; **live timing is the
   sensitive asset.** Consuming live privately = grey-but-tolerated; **publicly rebroadcasting it = the line F1
   enforces** — so live stays private to the friend group.
4. **Attribution:** F1DB CC-BY-4.0 credit in-app; OpenF1 (if used) is CC-BY-NC-SA (non-commercial — fine for us).
5. **Disclaimer:** include "unofficial, not affiliated with Formula 1; F1 marks owned by Formula One Licensing B.V."

---

## 10. Cost summary

| Phase | Monthly cost | Notes |
|---|---|---|
| 1 | **$0** | Offline; no backend needed yet (or trivial jolpica proxy). |
| 2 | **$0** | Oracle Always-Free box runs the FastF1 bake. |
| 3 | **$0** (FastF1 recorder) or €9.90/mo (OpenF1 clean live) | Decide at the Phase 3 boundary. |
| 4 | **$0** (local LLM on the box) or ~pennies/race (Claude API) | Optional. |

**Whole project runs at $0/month** with the free-default choices.

---

## 11. Risks & open decisions

- **Phase 3 live source** — FastF1 recorder (free, fiddly 2-h rotation, maybe needs a free F1 account) vs OpenF1
  (€9.90/mo, clean). *Decide at Phase 3.* Default: try FastF1 recorder first.
- **Oracle A1 capacity** — provisioning can fail in busy regions; validate a launch in Mumbai/Hyderabad before
  committing. Fallback: Hetzner ~€4.99/mo.
- **Version churn** — Ktor/CMP/Kotlin move fast; pin everything in `libs.versions.toml` and re-verify coordinates
  at build time (research caught several stale version/date claims).
- **f1db schema** — Room/SQLDelight entities must match the shipped DB exactly; run `sqlite3 f1db.db '.schema'` on
  the actual release before writing data classes.
- **DNF classification** — the biggest swing in any "win % vs car"; f1db collapses retirement reasons from 2025+.

---

## 12. Non-goals

- Public Play Store / App Store distribution.
- iOS/Windows/Linux clients (CMP keeps the door open, but out of scope now).
- Monetization, accounts/auth beyond a simple shared key for the friend group.
- Pre-2018 telemetry (doesn't exist).
- Real-time *processing* beyond relaying live timing.

---

## 13. Implementation decomposition

This is too large for one implementation plan. It will be built **phase by phase**, each with its own plan:

- **Plan 1 (next):** Phase 0 scaffolding (**`git init` the public `pitwall` repo under `~/Downloads/Repos/personal/`**
  — MIT `LICENSE` + `NOTICE`/f1db attribution + README "unofficial fan project" disclaimer + `.gitignore`; move this
  spec into the repo; CMP `composeApp` module; backend skeleton; f1db bundling + SQLDelight read path; CC-BY screen)
  **+ Phase 1 hero (Driver vs Car)** and the results/standings browse.
- Plans 2–4: Phase 2 telemetry, Phase 3 live, Phase 4 wow — authored when each is reached.

---

## 14. Key references (verified)

- FastF1 — https://docs.fastf1.dev (v3.8.3, Python 3.10+)
- jolpica-f1 — https://github.com/jolpica/jolpica-f1 (rate limits, Ergast differences)
- f1db — https://github.com/f1db/f1db (CC-BY-4.0, SQLite artifact)
- OpenF1 — https://openf1.org (CC-BY-NC-SA; live €9.90/mo)
- Driver-vs-car — van Kesteren & Bergkamp 2023 (arXiv:2203.08489); f1metrics; matthewperron/f1-elo
- Compose Multiplatform — https://kotlinlang.org/docs/multiplatform/ (Desktop Stable; native distribution)
- SQLDelight — https://sqldelight.github.io/sqldelight/ ; Ktor — https://ktor.io/docs/
- F1 legal — formula1.com Legal Notices & Guidelines; f1-dash IP-block; OpenF1 license
- Oracle Always-Free — https://www.oracle.com/cloud/free/ ; Hetzner — https://www.hetzner.com
