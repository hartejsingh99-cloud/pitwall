# PitWall — Phase 2 — Telemetry + Always-On Backend — Design & Plan

> **Status:** Forward-prep design doc (not yet ready to execute). Phase 0/1 (the offline Driver-vs-Car
> hero) is being built in parallel; **this document does not touch that code or git.** It is the
> architecture/ops design for Phase 2 plus a TDD task breakdown for the client-side slice.
>
> **For agentic workers (when this graduates to execution):** the backend/infra sections are an
> architecture + ops design (Section A); the client data-tier section is a TDD task breakdown with
> checkbox (`- [ ]`) steps (Section B). Re-verify every pinned version against PyPI/Maven Central at
> execution time — the research below was verified **2026-06-14** and library lines churn.

**Date:** 2026-06-14
**Author:** Claude (forward-prep), for Hartej
**Repo:** public open-source on personal GitHub (`hartejsingh99-cloud`), under `~/Downloads/Repos/personal/pitwall`
**Reads on:** `docs/superpowers/specs/2026-06-14-f1-app-design.md` (§3 data sources, §6 backend, §9 legal,
§10 cost, §11 risks) and `docs/superpowers/plans/2026-06-14-pitwall-phase0-hero.md` (client module shape).

---

## 0. Goal & scope

Phase 2 turns PitWall from an offline historical app into an app that can **"understand the car"**: per-lap
**car telemetry** (speed, throttle, brake, gear, DRS, position) and timing for **2018→now**, plus the
**race-pace companion** to the Driver-vs-Car hero (spec §4.1). The thing that makes this hard, and the
entire reason this phase exists, is the **golden rule (spec §6): FastF1 NEVER runs on the request path.**
`session.load()` blocks for seconds-to-tens-of-seconds and holds **hundreds of MB** in pandas. So Phase 2 is
really two deliverables:

1. **An always-on backend** (FastAPI + APScheduler + Postgres + Redis on Oracle Cloud Always-Free) that
   *bakes* FastF1 sessions into compact, pre-downsampled, gzipped, columnar artifacts a few hours after each
   session ends — entirely off the request path.
2. **A client telemetry tier** in the existing `composeApp` that fetches those baked blobs over Ktor,
   **persistently disk-caches** them (the gap this doc closes), and renders telemetry overlays / track-dominance
   / delta-time / tyre-deg / race-pace via Vico + Canvas.

Phase 2 features in scope (spec §5): telemetry overlay, track-dominance mini-sector map, delta-time trace,
tyre strategy & degradation, race pace & gap evolution, ghost-car GPS replay, and the **race-pace enrichment
of the hero** (§4.1). Live timing is explicitly Phase 3 and out of scope here — but the backend, transport,
and privacy decisions below are made so Phase 3 is "add an SSE endpoint," not a re-architecture.

### Hard constraints carried forward (these override convenience)

| Constraint | How Phase 2 honors it |
|---|---|
| **$0/month** | Oracle Cloud Always-Free A1 box; DuckDNS + Caddy/Let's Encrypt TLS; self-hosted Postgres+Redis in Docker (no managed-DB spend); no paid live source (that's a Phase 3 decision). Paid fallback (Hetzner) named but not default. |
| **No "F1"/"Formula 1"/logo branding** | Backend service name, Docker images, DuckDNS hostname, API paths, and any user-visible string use **"PitWall"** / neutral terms. No marks in repo metadata, image tags, or the hostname. |
| **Personal PUBLIC repo** | All backend code is public OSS (the FastF1/f1db/OpenF1/f1-dash norm). **No secrets in the repo** — shared API key, DuckDNS token, DB creds live only in `.env`/OCI, never committed. The *deployed* service stays **private to the friend group** (Section A.7). |
| **CC-BY-4.0 attribution** | f1db credit already in-app (Phase 0 LicensesScreen). Phase 2 adds **FastF1** + **jolpica/Ergast** attribution to the same screen, and a backend `/about` endpoint echoing the data sources & licenses. |

---

## Net delta vs the spec (read this first)

The spec §6/§10 were written before this phase's verification pass. Seven things changed; **two are
load-bearing capacity/correctness facts**, the rest are version/library updates.

| # | Spec said | Verified 2026-06-14 — now | Impact |
|---|---|---|---|
| 1 | Oracle A1 free = **up to 4 OCPU / 24 GB** | Oracle's **own docs now state 2 OCPU / 12 GB** (1,500 OCPU-h + 9,000 GB-h/mo). The 4/24 figure persists in community guides but **official docs show the reduced tier.** | **Re-baseline capacity to 2 OCPU / 12 GB.** Bake **one session at a time**; watch memory during pandas loads. Treat 4/24 as best-effort-if-granted. |
| 2 | (not mentioned) | **Idle reclamation is real:** Oracle reclaims an Always-Free instance if, over a **7-day window, ALL of** CPU p95 <20%, network <20%, **and memory <20% (A1)**. | Add a **keep-alive** (Section A.6) — a bake-spike alone won't save you between race weekends. |
| 3 | APScheduler (in-process), assume modern API | Stable is **3.11.2 (2025-12-22)**; **4.0 is STILL alpha** (`4.0.0a6`, 2025-04-27, >1yr in alpha). | Build on **3.11.x + `AsyncIOScheduler`** in FastAPI lifespan. Do **not** use the 4.x data-store API. |
| 4 | Live SSE via **`sse-starlette`** (Phase 3) | **FastAPI 0.135.0+ ships native SSE**; `0.135.1` (2026-03-01) exports `EventSourceResponse`/`ServerSentEvent` from `fastapi.sse`. | Phase 3 can **drop `sse-starlette`** and use FastAPI-native SSE. SSE-over-WebSocket reasoning unchanged. (Noted now so the dep isn't added in Phase 2.) |
| 5 | Ktor `HttpCache` for client caching | Ktor's persistent (`FileStorage`) cache is **JVM-only** — works on Android (ART)+macOS (JVM) in theory, but the KMP-idiomatic path is a community lib. | Adopt **`ktor-persistent-cache`** (Okio, LRU) or **`kachetor`** for clean disk caching across targets. |
| 6 | "load session, pre-compute" | FastF1 3.8.2 fixed a **crash-lap duplication bug** that triggers when telemetry is loaded *separately after* laps (#852). | **Always `session.load(laps=True, telemetry=True)` in ONE call**, never two passes. Poll-until-ready at end+~45min with retry; telemetry lands 30–120 min late, and brand-new sessions can be momentarily un-loadable (open #865). |
| 7 | jolpica 4 req/s, 500/hr | **Unchanged and trending DOWN** (token-auth rollout will *decrease* limits). | The **backend**, not the client, owns jolpica calls. Client read-through-caches; never per-screen. |

---

# SECTION A — Backend & Infrastructure (architecture / ops design)

## A.1 Service topology

One Oracle A1 ARM (Ampere) instance, home region **Mumbai or Hyderabad** (spec §6 — low latency + better A1
availability than the chronically "out-of-capacity" US regions). Everything runs under **one `docker compose`
stack** so the live sidecar (Phase 3) can later join the same box:

```
Oracle A1 (ARM Ampere, Always-Free)   [PLAN FOR 2 OCPU / 12 GB — Net delta #1]
└── docker compose
    ├── caddy            reverse proxy + automatic HTTPS (custom image w/ DuckDNS DNS-01 plugin)
    ├── api              FastAPI (uvicorn), python:3.12-slim ARM. Holds APScheduler in-process.
    │                    Mounts the FastF1 cache volume (read) + serves baked blobs.
    ├── postgres         baked artifacts + normalized laps/results tables. Persistent volume.
    ├── redis            hot read-through cache for the few hot sessions.
    └── (Phase 3) recorder  FastF1 SignalR live recorder sidecar — NOT in Phase 2.

Persistent block volume (≤200 GB free):
    /data/fastf1cache   ← FASTF1_CACHE; raw requests-cache SQLite + pickles (RAW LAYER)
    /data/pg            ← Postgres data dir
    /data/bake          ← optional parquet bake artifacts (if file-served instead of DB rows)
```

**Why this shape (carried from spec §2/§6, now re-confirmed):** the always-on API decouples clients from the
heavy FastF1 ETL. The bake runs on a schedule, never on a request. Postgres (not SQLite) because once the
Phase-3 live sidecar writes concurrently with the API reading, SQLite's single-writer model is a liability —
and we want to make that decision *once*, now, not migrate mid-Phase-3.

## A.2 The bake ETL — FastF1 → compact artifacts

This is the heart of Phase 2. Pin **`fastf1==3.8.3`** (current stable, 2026-04-29, Python ≥3.10; the slim
image is `python:3.12-slim`), plus `pandas<3`, `numpy<3`, `scipy<2` (spec §6).

### A.2.1 Two-stage FastF1 cache = the spec's "raw layer"

FastF1's cache is exactly what an ETL wants and maps 1:1 onto spec §6's storage tiering:

- **Stage 1** caches raw GET/POST in a **SQLite DB via `requests-cache`** (honors cache-control headers).
- **Stage 2** caches parsed API data as **pickles**, organized hierarchically by **year/event/session** — so
  a single session can be invalidated by deleting its folder.
- Enable once at process start: `fastf1.Cache.enable_cache('/data/fastf1cache')` (or `FASTF1_CACHE` env var).
- **Cache-served requests do not count against rate limits** — so a re-bake after the first load never
  re-hits the network.

**Ops consequence:** put `enable_cache` on the **Oracle block volume** (`/data/fastf1cache`), not the
container's ephemeral layer, so re-bakes and restarts are free and offline. **A loaded session is ~50–100 MB**
and the cache grows fast → **budget disk and prune old sessions** (Section A.5). The 200 GB free block volume
is generous, but a cache-prune job keeps it from creeping.

### A.2.2 The single load call (Net delta #6 — correctness)

```python
# CORRECT — one call. Loading telemetry separately after laps triggers the #852 crash-lap dup bug.
session = fastf1.get_session(year, round_or_name, identifier)  # identifier: 'Q','R','S','FP1'...
session.load(laps=True, telemetry=True, weather=True, messages=True)
# NEVER: session.load(laps=True); ... ; session.load(telemetry=True)
```

`session.load()` blocks for seconds-to-tens-of-seconds and pins hundreds of MB. It runs **only inside the
APScheduler bake job**, never in a request handler. On a 12 GB box (Net delta #1) this means **one session
loaded at a time** — serialize bakes with a lock; do not parallelize session loads.

### A.2.3 Poll-until-ready scheduling (Net delta #6 — the dynamic one-shot pattern)

Telemetry is "usually available **30–120 min after a session ends**," sometimes later, and **brand-new
sessions can be momentarily un-loadable** (open issue #865 — telemetry failed to load for the first two 2026
FP sessions). So **do not use a static cron.** The clean pattern:

1. A lightweight **recurring APScheduler job** (once or twice daily) hits the **jolpica schedule** for the
   current/next event and reads each session's **UTC start time**.
2. For each session it computes **end ≈ start + typical_duration** and **dynamically registers a one-shot
   `date` trigger at `end + 45 min`**.
3. That **one-shot bake job** runs `session.load(...)` with **retry/back-off**:
   - On success → transform → write artifacts → mark session `BAKED`.
   - On "no data yet" / empty / load failure → **reschedule itself for +30 min** (cap retries, e.g. 6 over
     ~3h), treating early failure as "retry later," **not** "no data" (#865).
   - On **`RateLimitExceededError`** (now propagates properly since 3.8.0 #842) → **exponential back-off**,
     don't hammer.

Dynamic one-shot triggers beat a fixed cron because **session end-times move** with delays, red flags, and
rain. This also naturally handles the "results may be FastF1-derived not official right after a session"
caveat: FastF1 now computes preliminary results from timing when jolpica has none — we **store a
`results_source` flag** (`fastf1_preliminary` | `jolpica_official`) and **re-bake once jolpica has official
results** so the client can show a "provisional" badge and later upgrade silently.

### A.2.4 The transform — what we actually bake (serve compact, never raw frames)

For each driver-lap we want to serve (spec §6 "pre-downsampled, columnar, gzipped, parallel-arrays"):

1. `laps = session.laps.pick_drivers(driver)` → per-lap metadata (lap time, compound, tyre life, stint,
   pit in/out, track status, `IsAccurate`).
2. For telemetry features: `lap.get_telemetry()` (or `get_car_data().add_distance()`), then **decimate by
   distance** (e.g. one sample per ~10 m, configurable) — distance-decimation, not time-decimation, keeps
   track-position overlays aligned across drivers.
3. Store **parallel arrays per channel** (`distance[]`, `speed[]`, `throttle[]`, `brake[]`, `gear[]`,
   `drs[]`, `x[]`, `y[]`) — **not array-of-objects** (smaller, faster to parse on-device).
4. **gzip** the JSON (or write **parquet** — the natural columnar format since the source is pandas
   DataFrames; serve gzipped-JSON parallel arrays to the Compose client).

**Race-pace companion for the hero (spec §4.1):** in the same bake, compute median race-lap pace per driver
using `pick_accurate().pick_quicklaps()` excluding SC/VSC laps, 2018+ only, and store the symmetric % vs
teammate. This is the data the Phase-0 hero's "race pace" enrichment reads — the hero keeps its all-eras
qualifying signal offline, and the backend *deepens* it for 2018+.

**Tyre-deg bake (spec §5):** fuel-corrected stint pace, 107% + SC/VSC filter, LOWESS smoothing — computed in
the bake (scipy/statsmodels), stored as a small fitted-curve + points blob, not raw laps.

### A.2.5 Artifact storage (Postgres tiering — spec §6)

- **`sessions`** — `(year, round, session, status, results_source, baked_at, fastf1_version)`.
- **`laps`** — normalized one row per driver-lap (metadata + lap time + tyre/stint/status).
- **`telemetry_blobs`** — `(session_id, driver_id, lap_number, channel_set, gzip_bytes)` — one row per
  driver-lap, the gzipped parallel-array payload. (Parquet-on-disk under `/data/bake` is an acceptable
  alternative if blobs get large; DB rows are simpler for a few users.)
- **`driver_pace`** — race-pace companion + tyre-deg fits per driver-session.
- **Redis** caches the **few hot sessions** (latest race weekend) read-through so the API rarely touches
  Postgres for the common case.

## A.3 API surface (read-only, baked-only, never triggers a load)

FastAPI, `fastapi==0.135.x` (Net delta #4 — native SSE available but **not used until Phase 3**). Every
route requires the shared bearer key (Section A.7). `kotlinx.serialization` on the client uses
`ignoreUnknownKeys=true` (spec §7) so we can add fields without breaking old clients.

| Method · path | Returns | Cacheability (drives client TTL) |
|---|---|---|
| `GET /v1/sessions?year=&round=` | list of baked sessions + `status` + `results_source` | short TTL (status flips provisional→official) |
| `GET /v1/sessions/{id}/laps` | normalized laps (all drivers) for a session | immutable once `status=BAKED & official` → long TTL |
| `GET /v1/sessions/{id}/telemetry?driver=&lap=` | **gzipped parallel-array** blob | **immutable** when finalized → cache effectively forever |
| `GET /v1/sessions/{id}/track-dominance` | mini-sector fastest-driver map (X/Y + sector) | immutable when finalized |
| `GET /v1/sessions/{id}/pace` | race-pace + gap-evolution + tyre-deg fits | immutable when finalized |
| `GET /v1/hero/race-pace?year=` | teammate-normalized race-pace companion (feeds hero §4.1) | medium TTL |
| `GET /v1/health` | liveness (also a keep-alive nudge target) | no-cache |
| `GET /v1/about` | data sources + licenses (f1db CC-BY, FastF1, jolpica) | long TTL |

**Cache-control discipline:** finalized-session responses set far-future `Cache-Control: public,
max-age=31536000, immutable`; provisional/`/sessions` list responses set a short max-age. This is what lets
the client cache telemetry **forever** (Section B) — the server tells it so.

## A.4 Dependency pins (verified 2026-06-14)

```
# requirements.txt (backend) — re-verify at execution; ARM wheels exist for all.
fastf1==3.8.3            # 2026-04-29; Python >=3.10; SINGLE load() call (Net delta #6)
APScheduler==3.11.2      # 2025-12-22; 4.0 STILL alpha — use AsyncIOScheduler (Net delta #3)
fastapi==0.135.1         # native SSE in fastapi.sse — for Phase 3, NOT added now (Net delta #4)
uvicorn[standard]==...   # pin at execution
psycopg[binary]==...     # Postgres driver
redis==...               # Redis client
pandas<3 ; numpy<3 ; scipy<2     # FastF1 stack constraint (spec §6)
# NOT added in Phase 2: sse-starlette (superseded by FastAPI native SSE in Phase 3)
```

APScheduler wiring (in FastAPI lifespan, not module import):

```python
from contextlib import asynccontextmanager
from apscheduler.schedulers.asyncio import AsyncIOScheduler   # 3.11.x — NOT the 4.x alpha API

scheduler = AsyncIOScheduler()

@asynccontextmanager
async def lifespan(app):
    scheduler.add_job(poll_schedule_and_register_bakes, "interval", hours=12, id="poll")
    scheduler.start()
    yield
    scheduler.shutdown(wait=False)
```

## A.5 Disk & memory budget (Net delta #1 — the tight constraint)

- **Memory:** plan against **12 GB**. One FastF1 `session.load()` can hold several hundred MB in pandas;
  Postgres + Redis + Caddy + the API + OS take a baseline. **Serialize bakes (one session at a time)** behind
  an asyncio lock; never load two sessions concurrently. Set a container memory limit on `api` so a runaway
  bake can't OOM Postgres. If a load approaches the ceiling, decimate harder / drop unneeded channels before
  materializing.
- **Disk:** 200 GB free block volume. FastF1 cache grows ~50–100 MB per loaded session. A **monthly prune
  job** (APScheduler) deletes FastF1 cache folders for sessions older than N weekends *that are already baked
  into Postgres* (the bake is the durable copy; the raw cache is re-fetchable). Keep the current + last race
  weekend hot.
- **Egress:** 10 TB/month free — irrelevant for a few users serving gzipped blobs.

## A.6 Idle-reclamation keep-alive (Net delta #2)

Oracle reclaims an idle Always-Free instance when, over a **7-day window**, **all of** CPU p95 <20%, network
<20%, **and memory <20% (A1 only)** hold. Between race weekends, bake spikes are too brief and infrequent to
clear the bar. **Mitigation, designed explicitly:**

- A small **APScheduler keep-alive job** every ~10–15 min that does light, real work (e.g. self-`GET
  /v1/health` over the network interface + touch a small file) to keep network/CPU above the floor.
- Postgres + Redis idling already hold a non-trivial **memory** baseline on A1, which helps clear the
  memory-<20% leg (the A1-specific third condition).
- Community `oci-arm-host-capacity` scripts exist precisely because of this + the launch-capacity problem;
  we don't need them for keep-alive, but note the ecosystem if launch retries are needed.

This is a **must-have**, not a nice-to-have: an always-on hobby box that looks idle *will* get reclaimed.

## A.7 Privacy — keeping it to the friend group (spec §9 legal line)

Oracle gives no built-in auth. The spec's rule is "consume live privately = tolerated; public hosted
live-timing service = the line F1 enforces (it took down f1-dash)." Even though Phase 2 is post-session
historical (the *safe* category, spec §9.3), we build the privacy posture now so Phase 3 inherits it.
**Three layers:**

1. **Network (OCI security list / NSG):** ingress restricted — ideally to friends' IPs; at minimum only
   **443** open (close 80; DNS-01 cert issuance doesn't need it — Section A.8). SSH locked to known IPs/keys.
2. **Application (shared bearer key):** FastAPI dependency checks a **single shared key** on **every** REST
   route (and every SSE route in Phase 3). Key lives in `.env`/OCI secrets, injected into the client at build
   time — **never committed** to the public repo.
3. **Obscurity:** **never publish the DuckDNS hostname** anywhere (README, issues, commits). The public repo
   contains code, not the deployed endpoint.

This keeps PitWall firmly in the "private to a few friends + public code" tolerated lane.

## A.8 TLS & hostname — the verified $0 path (spec §6)

- **DuckDNS** for the hostname (free). **Caddy** for fully-automatic HTTPS (provision + renew, zero manual
  steps).
- Use the **`caddy-dns/duckdns` DNS-01 challenge plugin**: issues/renews Let's Encrypt certs **without opening
  port 80** and supports **wildcard certs** — which lets us keep only 443 open (Section A.7 layer 1).
- **Gotcha:** the official Caddy Docker image **excludes DNS modules**, so we must **build a custom Caddy
  image** (`xcaddy` / Docker multi-stage build) bundling the DuckDNS module. This is the standard, current,
  $0 recipe — document the Dockerfile in the repo (the DuckDNS *token* is an env var, never committed).

## A.9 Region, capacity, and the paid fallback (spec §11)

- **"Out of capacity"** on A1 launches in busy regions is a known, ongoing pain (hence the
  `oci-arm-host-capacity` retry-script ecosystem). Validate a launch in **Mumbai/Hyderabad** before
  committing the architecture.
- **Free Autonomous DB is the wrong tool:** Always-Free gives 2 ADBs (1 OCPU + 20 GB each, max 20 concurrent
  sessions, storage not scalable) — but it's **Oracle SQL, not Postgres**, doesn't co-locate with Redis/the
  FastF1 cache, and would force abandoning the Docker-compose model. Its only edge is managed backups; **not
  worth the architectural split** for a friend-group app. **Self-host Postgres in Docker** — decision stands.
- **Paid fallback if Oracle frustrates:** **Hetzner CAX11** (~€4.99/mo, 2 vCPU / 4 GB ARM). Note its **4 GB
  RAM is tight** for FastF1's pandas loads — **size up to CAX21** if you fall back. This breaks the strict
  $0 goal, so it's a last resort, flagged for an explicit decision at the Phase-2 boundary, not a default.

## A.10 CI / repo hygiene (public repo, no secrets)

- Backend lives in the same public repo (e.g. `backend/`). **`.env` is gitignored**; commit a
  `.env.example` with placeholder keys only.
- A GitHub Actions job builds the ARM `python:3.12-slim` image and the custom Caddy image (multi-arch or
  ARM), runs `pytest` on the bake-transform unit tests (pure functions: decimation, parallel-array packing,
  symmetric-% race-pace — testable without network using small fixture DataFrames or recorded cache fixtures).
- **No secrets in CI logs**; deploy is manual `docker compose pull && up -d` on the box (or a gated workflow
  with secrets in GitHub Actions secrets, never the repo).
- Image tags / package names use **"pitwall"**, never the marks (branding constraint).

## A.11 Backend acceptance checks (ops verification)

- [ ] A1 instance launches in Mumbai/Hyderabad; `docker compose up -d` brings up caddy+api+postgres+redis.
- [ ] `https://<duckdns-host>/v1/health` returns 200 over **HTTPS with port 80 closed** (DNS-01 cert issued).
- [ ] Every `/v1/*` route returns **401 without** the shared key, 200 **with** it.
- [ ] A manual bake of a known 2018+ qualifying + race session produces `BAKED` rows, gzipped telemetry
      blobs, and a race-pace row; a re-bake hits the FastF1 cache (no network) and is fast.
- [ ] The dynamic one-shot scheduler registers a bake at `session_end + 45min` from the jolpica schedule, and
      retries (not gives up) on an early empty load.
- [ ] Keep-alive job keeps CPU/network/memory above the 20% floor across a quiet 7-day window (no reclamation).
- [ ] Disk-prune job removes stale FastF1 cache folders while leaving Postgres bakes intact.
- [ ] `/v1/about` lists f1db (CC-BY-4.0), FastF1, and jolpica with links (attribution).

---

# SECTION B — Client telemetry tier (TDD task breakdown)

This extends the existing `composeApp` (Phase 0 plan). It adds a **networking + persistent-cache layer** and
the **telemetry repositories/models**, then the charts. **Pure logic is TDD-first** (the Phase-0 plan's
discipline); platform/UI wiring is verified by running. Re-verify versions in `gradle/libs.versions.toml`.

> **Why a new caching layer (Net delta #5):** Ktor's built-in `HttpCache` persistent (`FileStorage`) backend
> is **JVM-only**. macOS desktop (JVM) and Android (ART) are both JVM-capable so it *technically* works, but
> the robust, KMP-idiomatic choice is a community lib. Pick **`ktor-persistent-cache`** (santimattius —
> Okio-backed, configurable TTL/max-size, **LRU eviction**, platform cache dirs, shared Android/JVM/iOS API,
> Klibs.io-listed) as primary; **`kachetor`** (vipulasri) as fallback if its API fits better at execution.

### On-device tiering (the rule the client must follow)

- **f1db** — bundled SQLite, **offline, no network** (Phase 0; unchanged).
- **jolpica** — the **client never calls jolpica directly** (Net delta #7: 4 req/s, 500/hr, *decreasing*).
  The **backend** is the jolpica consumer. If the client ever needs jolpica freshness, it reads through the
  persistent cache with a **long TTL** and respects server cache-control — but the default is: go through our
  FastAPI.
- **Baked telemetry blobs** — fetched from FastAPI, **disk-cached keyed by session+driver+lap**, and since
  finalized sessions are **immutable**, cached **effectively forever** (no revalidation). Ideal for the LRU
  disk cache.

### New version-catalog entries (verify at execution)

```toml
[versions]
ktor = "3.5.0"                 # matches spec §7
ktorPersistentCache = "..."    # ktor-persistent-cache (santimattius) — pin at execution
vico = "..."                   # 3.x — telemetry line charts (spec §7)

[libraries]
ktor-client-core    = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-client-okhttp  = { module = "io.ktor:ktor-client-okhttp", version.ref = "ktor" }   # androidMain
ktor-client-cio     = { module = "io.ktor:ktor-client-cio", version.ref = "ktor" }      # desktopMain
ktor-content-negotiation = { module = "io.ktor:ktor-client-content-negotiation", version.ref = "ktor" }
ktor-serialization-json  = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }
ktor-persistent-cache    = { module = "io.github.santimattius:ktor-persistent-cache", version.ref = "ktorPersistentCache" }
vico-compose             = { module = "com.patrykandpatrick.vico:compose", version.ref = "vico" }
```

> **Note (spec §7):** Ktor engines are **OkHttp (Android) + CIO (desktop)** — *not* Darwin (that's
> Kotlin/Native only; both PitWall targets are JVM/ART).

---

## Task 1: Telemetry domain models + decode (pure, TDD)

The on-device counterpart to the backend's parallel-array bake: decode gzipped parallel arrays into typed
channels, and provide the **delta-time** computation (pure math, the spec §5 "delta-time trace").

**Files:**
- Create: `composeApp/src/commonMain/kotlin/dev/pitwall/domain/Telemetry.kt`
- Create: `composeApp/src/commonMain/kotlin/dev/pitwall/domain/DeltaTime.kt`
- Test: `composeApp/src/commonTest/kotlin/dev/pitwall/domain/DeltaTimeTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.pitwall.domain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeltaTimeTest {
    // Two laps sampled on the SAME distance grid; lap B is uniformly slower.
    @Test fun deltaIsZeroAtStart_andGrowsWhenSlower() {
        val dist = listOf(0.0, 100.0, 200.0, 300.0)
        val tA = listOf(0.0, 2.0, 4.0, 6.0)         // reference lap cumulative time
        val tB = listOf(0.0, 2.1, 4.3, 6.6)         // comparison lap, progressively slower
        val d = deltaTime(LapTrace(dist, tA), LapTrace(dist, tB))
        assertEquals(0.0, d.first(), 1e-9)          // delta starts at 0
        assertTrue(d.last() > 0.0)                  // B ends behind A
        assertEquals(0.6, d.last(), 1e-9)
    }

    // Parallel-array channels of differing implied length must reject (corrupt blob guard).
    @Test fun rejectsMismatchedChannelLengths() {
        val r = runCatching { ChannelSet(distance = listOf(0.0, 1.0), speed = listOf(300.0)).validated() }
        assertTrue(r.isFailure)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

`~/Downloads/Repos/personal/pitwall/gradlew -p ~/Downloads/Repos/personal/pitwall :composeApp:desktopTest`
Expected: FAIL — `LapTrace`, `deltaTime`, `ChannelSet`, `validated` unresolved.

- [ ] **Step 3: Write `Telemetry.kt`**

```kotlin
package dev.pitwall.domain

/** Parallel-array telemetry for one driver-lap (matches the backend bake; nullable = channel absent). */
data class ChannelSet(
    val distance: List<Double>,
    val speed: List<Double>? = null,
    val throttle: List<Double>? = null,
    val brake: List<Double>? = null,
    val gear: List<Int>? = null,
    val drs: List<Int>? = null,
    val x: List<Double>? = null,
    val y: List<Double>? = null,
) {
    fun validated(): ChannelSet {
        val n = distance.size
        require(n > 0) { "empty distance axis" }
        listOf(speed, throttle, brake).forEach { require(it == null || it.size == n) { "channel length != distance" } }
        require(gear == null || gear.size == n)
        require(drs == null || drs.size == n)
        require((x == null) == (y == null)) { "x and y must both be present or both absent" }
        require(x == null || x.size == n)
        return this
    }
}

/** Cumulative time vs distance for one lap, on a shared distance grid. */
data class LapTrace(val distance: List<Double>, val cumulativeTimeSec: List<Double>)
```

- [ ] **Step 4: Write `DeltaTime.kt`**

```kotlin
package dev.pitwall.domain

/**
 * Delta-time of [comparison] relative to [reference] at each shared sample.
 * Both traces must be on the same distance grid (the backend bakes them decimated by distance).
 * Positive = comparison is behind (slower). Spec §5 "delta-time trace".
 */
fun deltaTime(reference: LapTrace, comparison: LapTrace): List<Double> {
    require(reference.distance.size == comparison.distance.size) { "traces must share a distance grid" }
    return reference.cumulativeTimeSec.indices.map { i ->
        comparison.cumulativeTimeSec[i] - reference.cumulativeTimeSec[i]
    }
}
```

- [ ] **Step 5: Run to verify pass; commit**

`:composeApp:desktopTest` → PASS.
```bash
git -C ~/Downloads/Repos/personal/pitwall add -A
git -C ~/Downloads/Repos/personal/pitwall commit -m "feat: telemetry channel models + pure delta-time (TDD)"
```

---

## Task 2: Ktor client + persistent disk cache (Net delta #5)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/dev/pitwall/data/net/HttpClientFactory.kt` (expect engine + cache dir)
- Create: `composeApp/src/{androidMain,desktopMain}/.../net/HttpClientFactory.{android,desktop}.kt` (actuals)
- Create: `composeApp/src/commonMain/kotlin/dev/pitwall/data/net/PitwallApi.kt`
- Modify: `composeApp/build.gradle.kts` (Ktor + persistent-cache deps per source set)

- [ ] **Step 1: `HttpClientFactory.kt` (expect)** — returns an `HttpClient` configured with:
  content-negotiation (`kotlinx.serialization`, `ignoreUnknownKeys = true` per spec §7), a default request
  that injects the **shared bearer key** (Section A.7) and base URL, and the **persistent disk cache** plugin
  pointed at a platform cache dir with an **LRU max-size** and a **long default TTL** (telemetry is immutable).

```kotlin
package dev.pitwall.data.net
import io.ktor.client.HttpClient
// expect provides the platform engine (OkHttp/CIO) and the persistent cache directory.
expect fun platformHttpEngineFactory(): io.ktor.client.engine.HttpClientEngineFactory<*>
expect fun cacheDirPath(): String   // Android: context.cacheDir/pitwall-http ; macOS: ~/Library/Caches/PitWall

fun pitwallHttpClient(baseUrl: String, apiKey: String): HttpClient { /* install ContentNegotiation,
    DefaultRequest(baseUrl + Bearer apiKey), and ktor-persistent-cache(FileStorage at cacheDirPath(), LRU). */ }
```

- [ ] **Step 2: actuals** — `androidMain` returns `OkHttp` engine + `appContext.cacheDir`; `desktopMain`
  returns `CIO` engine + `~/Library/Caches/PitWall`. (Reuse the `appContext` already wired in Phase 0's
  `MainActivity`.)

- [ ] **Step 3: `PitwallApi.kt`** — typed wrappers for the Section A.3 routes: `sessions()`,
  `laps(sessionId)`, `telemetry(sessionId, driver, lap): ChannelSet`, `pace(sessionId)`,
  `heroRacePace(year)`. The API returns gzipped parallel arrays → deserialize into `ChannelSet().validated()`
  (Task 1 guard). The client **does not call jolpica** (Net delta #7).

- [ ] **Step 4: verify** — there's little pure logic here; verify by pointing at the running backend
  (Section A.11) and confirming a telemetry fetch round-trips and the **second fetch is served from the disk
  cache offline** (kill the network, re-request, get the same blob). Commit.

```bash
git -C ~/Downloads/Repos/personal/pitwall commit -am "feat: Ktor client + persistent LRU disk cache (KMP) + PitWall API"
```

> **Acceptance for the cache:** disconnect the network after one successful telemetry fetch; the same
> `session+driver+lap` request must resolve from disk (immutable → no revalidation). This is the on-device
> proof that we honor the rate-limit/offline rules.

---

## Task 3: Telemetry repository + race-pace hero enrichment

**Files:**
- Create: `composeApp/src/commonMain/kotlin/dev/pitwall/data/TelemetryRepository.kt`
- Modify: `composeApp/src/commonMain/kotlin/dev/pitwall/di/Modules.kt` (provide API + repo)
- Test: `composeApp/src/commonTest/kotlin/dev/pitwall/data/TelemetryMappingTest.kt`

- [ ] **Step 1: failing mapping test** — assert the API DTO → `ChannelSet` mapping, and that a 2018+
  race-pace response merges into the existing hero rating (the §4.1 companion) **without breaking the
  offline qualifying rating** for pre-2018 (graceful degradation, spec §3 cutoff). Pure mapping, no network.

- [ ] **Step 2: `TelemetryRepository.kt`** — exposes `telemetry(...)`, `pace(...)`, and
  `heroWithRacePace(year)` that overlays the backend race-pace companion onto the Phase-0
  `F1Repository.ratingsForSeason` result **only for 2018+** (older seasons keep qualifying-only). All network
  goes through the cached Ktor client (Task 2); all blobs are immutable-cached.

- [ ] **Step 3: Koin** — `single { pitwallHttpClient(BASE_URL, API_KEY) }`, `single { PitwallApi(get()) }`,
  `single { TelemetryRepository(get(), get()) }`. `BASE_URL`/`API_KEY` injected at build time (BuildConfig /
  desktop system property), **never committed**.

- [ ] **Step 4: run tests → PASS; commit.**

---

## Task 4: Telemetry UI — overlay, delta, track-dominance, tyre-deg, pace (Vico + Canvas)

**Files (commonMain `ui/`):** `TelemetryOverlayScreen.kt` (Vico multi-channel line: speed/throttle/brake/
gear/DRS by distance), `DeltaTimeChart.kt` (Vico, from Task 1 `deltaTime`), `TrackDominanceMap.kt`
(Compose **Canvas/DrawScope** over X/Y, colored by fastest driver per mini-sector — no map SDK, per spec §7),
`TyreDegChart.kt` (fitted LOWESS curve + points from the bake), `RacePaceScreen.kt` (gap evolution), and
wiring into `App.kt` nav. ViewModels per screen mirror the Phase-0 `DriverVsCarViewModel` pattern
(`MutableStateFlow<UiState>`, `viewModelScope`, `Dispatchers.Default` for any decode work).

- [ ] **Step 1:** `TelemetryOverlayViewModel` + screen — pick session/driver/lap, fetch via repo, render Vico.
- [ ] **Step 2:** `TrackDominanceMap` Canvas — scale X/Y to the draw area, segment by mini-sector, color by
      fastest driver. Pure scaling math is unit-tested in `commonTest` (a `scaleToCanvas` helper).
- [ ] **Step 3:** delta-time chart from Task 1; tyre-deg chart from `pace` bake; race-pace/gap-evolution screen.
- [ ] **Step 4: pre-2018 graceful degradation (spec §3 cutoff):** when a selected session is pre-2018, the
      telemetry tabs show an explicit "Telemetry available 2018+ — showing results only" state, **never** an
      error. (Unit-test the era-gate predicate.)
- [ ] **Step 5: update LicensesScreen** — add **FastF1** and **jolpica/Ergast** to the existing f1db CC-BY
      attribution (CC-BY constraint). Commit.
- [ ] **Step 6: run on Android + macOS** and eyeball each chart against a known 2018+ race weekend
      (Section A.11 bake must be live). Commit + tag `v0.2.0-telemetry`.

---

## Self-review

**Spec coverage (Phase 2):**
- Golden rule (FastF1 off the request path) → entire Section A.2 bake ETL; API serves only baked artifacts.
- Backend stack (FastAPI + APScheduler + Postgres + Redis + Docker on Oracle A1, Mumbai/Hyderabad) → A.1–A.4,
  A.9, with the spec's SQLite→Postgres rationale re-confirmed (A.1).
- Storage tiering (FastF1 cache raw layer → Postgres blobs → Redis hot) → A.2.1, A.2.5.
- Compact serving (decimate-by-distance, parallel arrays, gzip) → A.2.4, mirrored on-device in Task 1/Task 2.
- Race-pace companion to the hero (§4.1, 2018+) → A.2.4 + Task 3 (overlays onto Phase-0 ratings, era-gated).
- $0 / TLS / hostname (DuckDNS + Caddy, custom image, DNS-01, port 80 closed) → A.8.
- Privacy (network NSG + shared bearer key + hidden hostname) → A.7; legal lane (spec §9) preserved.
- Client tiering (f1db offline / jolpica = backend-only / telemetry immutable-cached) → Section B header + Task 2/3.

**Net-delta items, each landed somewhere concrete:**
1. 2 OCPU/12 GB re-baseline → A.1, A.2.2, A.5 (one session at a time, memory limits).
2. Idle-reclamation keep-alive → A.6 (must-have).
3. APScheduler 3.11.2 + AsyncIOScheduler → A.4 (with lifespan code), A.2.3.
4. FastAPI native SSE, drop sse-starlette → A.3/A.4 (flagged for Phase 3, not added now).
5. Ktor persistent cache JVM-only → Section B header + Task 2 (`ktor-persistent-cache`/`kachetor`).
6. Single `session.load(laps=True, telemetry=True)` + poll-until-ready/retry/back-off + #865 early-failure →
   A.2.2, A.2.3.
7. jolpica limits down, backend owns the calls → Section B tiering + Task 2/3.

**Constraints honored:** $0 default with Hetzner named only as a flagged last resort (A.9); no marks in any
service/image/hostname/path/UI string (constraints table + A.10); public repo carries code but **no secrets
or hostname** (A.7, A.10); CC-BY attribution extended to FastF1 + jolpica in `/v1/about` and LicensesScreen
(A.3, Task 4 Step 5).

**Explicitly deferred (named, not dropped):** live timing board, push alerts, predictions (Phase 3 — but the
SSE/native-SSE/privacy decisions are pre-made here); AI recap + Wrapped (Phase 4); the Elo/Bayesian
car-adjusted win-probability (Phase-0 plan deferred it; not re-opened here). OpenF1 paid live and the FastF1
SignalR recorder sidecar are Phase-3-boundary decisions (spec §11), noted in A.1 only as the future sidecar slot.

**Open verifications at execution time:** exact `ktor-persistent-cache` coordinate/API (fallback `kachetor`);
ARM wheel availability for the FastF1 stack (expected fine); A1 launch capacity in Mumbai/Hyderabad before
committing (A.9); live confirmation of Oracle's 2/12 vs 4/24 grant at provision time (plan for 2/12 regardless).
