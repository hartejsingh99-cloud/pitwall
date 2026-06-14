# PitWall — Phase 3 (Race Weekend, Live) — Design & Ops Plan

> **Date:** 2026-06-14
> **Status:** Planning (forward-prep). NOT ready to build — Phases 0/1 (hero) and Phase 2 (telemetry bake) must ship first.
> **Author:** Hartej (with Claude)
> **Repo:** public OSS on personal GitHub (`hartejsingh99-cloud`), under `~/Downloads/Repos/personal/pitwall`
> **Reads-with:** the Design Spec (`docs/superpowers/specs/2026-06-14-f1-app-design.md`) and the Phase-0 plan
> (`docs/superpowers/plans/2026-06-14-pitwall-phase0-hero.md`). This plan assumes their architecture, data tiers,
> and constraints verbatim and only adds the live race-weekend layer.

> **For agentic workers:** this is a mix of *ops/architecture design* (backend, infra, the live recorder sidecar)
> and *app-code TDD task breakdowns* (the SSE client transport + alert handling in `commonMain`). Where it is app
> code, use `superpowers:test-driven-development` and follow the checkbox (`- [ ]`) steps. Where it is infra, the
> deliverable is config + a runbook, not a unit test. **Do not run any gradle/build commands or touch the repo's
> code/git while Phases 0–2 are being built in parallel — this is a planning document only.**

---

## 0. The one-paragraph summary

Phase 3 turns PitWall into a live race-weekend board (positions / gaps / last-lap / tyres / flags) plus
Android push alerts (lights-out, red flag, fav-driver pit/podium), at **$0 software**. The live feed comes from
**FastF1's SignalR recorder run as an auto-restarting sidecar** on the same Oracle Always-Free box, parsed
incrementally into a Redis event stream, and fanned out to clients over **SSE** (not WebSocket) and to Android over
**FCM**. **The single biggest risk, newly verified, is that F1 moved the live endpoint to `signalrcore` and now
requires a *paid F1TV subscription* to authenticate the live client** — the spec's "may need a *free* F1.com
account" note is wrong and must be corrected. Literal $0-live is therefore conditional: it holds only if you (or a
friend) already pay for F1TV, otherwise the genuinely-free fallbacks are `no_auth=True` partial data or OpenF1's
free historical replay for off-season testing. The legal line is now evidence-backed (f1-dash is *actively
IP-blocked*; self-hosting is the tolerated remedy), so the live SSE endpoint **must be private — shared-key gated,
no public URL, never advertised**.

---

## 1. Scope, non-goals, and the four sub-streams

**In scope (the three Phase-3 features from spec §5):**

| Feature | Source | Transport | Parity |
|---|---|---|---|
| Live timing board (positions / gaps / intervals / last+best lap / sector / tyre+stint / track status / weather / session state) | FastF1 recorder (`signalrcore`) → backend parse | SSE | 📱 + 💻 |
| Smart push alerts (lights out, red/yellow flag, SC/VSC, fav-driver pit/podium/overtake-into-podium) | backend rules → FCM (Android) + SSE (desktop tray) | FCM + SSE | **📱 background**, 💻 in-app/tray while open |
| Predictions vs friends (lock before lights-out, score after) | backend (Postgres) | REST + SSE result push | 📱 + 💻 |

**Out of scope (non-goals, restated for this phase):**
- Public hosted live service of any kind (this is the line F1 enforces — see §6).
- Real-time *processing* beyond relaying/diffing live timing (no live ML, no live lap prediction).
- iOS/Windows/Linux live clients.
- WebSocket — explicitly rejected; live timing is one-way server→client, SSE is the right tool (§4).
- Re-deriving telemetry live — telemetry stays the post-session Phase-2 bake; the live board uses the
  timing-feed-derived channels only.

**The four de-risk sub-streams this plan covers (matching the research):**
1. The FastF1 live recorder — cadence, the ~2h server drop, file-rotation + dedupe mitigation, and the
   `signalrcore`/auth risk (§3).
2. Backend → Compose client over SSE — event shape, sequence IDs, reconnection, server-side replay buffer (§4).
3. What's actually feasible at $0, and the legal posture (§5, §6).
4. Android push via FCM (free, `priority:high` notification messages) vs desktop (no push) (§7).

---

## 2. Phase-3 architecture (delta over the spec)

```
            ┌─────────────────────────────────────────────────────────────┐
            │  ORACLE CLOUD ALWAYS-FREE BOX (ARM A1) — docker compose       │
            │                                                               │
  F1 live   │   ┌────────────────────┐  writes  ┌──────────────────────┐   │
  feed  ───────►│ live-recorder      │  .txt    │ recording/ (volume)  │   │
  wss://        │ sidecar (FastF1    │  frames  │  segment-NNN.txt     │   │
  livetiming    │ SignalRClient,     │─────────►│  (rotated ~100 min,  │   │
  .formula1     │ auto-restart,      │          │   overlapping)       │   │
  .com/         │ F1TV-authed)       │          └──────────┬───────────┘   │
  signalrcore   └────────────────────┘                     │ tail+parse    │
            │                                               ▼               │
            │   ┌───────────────────────────────────────────────────────┐ │
            │   │ FastAPI app (uvicorn)                                   │ │
            │   │  • tailer task: parse new frames → normalized events    │ │
            │   │  • Redis: per-session event stream (monotonic seq id)   │ │
            │   │           + latest full snapshot + ring buffer          │ │
            │   │  • SSE endpoint  /live/{session}?key=…   (private!)      │ │
            │   │  • REST: predictions, snapshot bootstrap                 │ │
            │   │  • alert engine → FCM HTTP v1 (Android) + SSE (desktop)  │ │
            │   │  • APScheduler: arm recorder T-3min from f1db/jolpica    │ │
            │   └───────────────────────────────────────────────────────┘ │
            │        Postgres (predictions, durable session log)           │
            └───────────────────────────────────────────────────────────┘
                         │ HTTPS (Caddy + Let's Encrypt, DuckDNS host)
            ┌────────────┴───────────────┐
            ▼                            ▼
   📱 Android  (Ktor SSE in            💻 macOS desktop (Ktor SSE in
      commonMain  +  FCM push            commonMain → tray notification
      via firebase-messaging;            while window open; NO background
      foreground service for SSE         push — FCM cannot reach desktop)
      while watching)
```

**What's genuinely new vs Phases 0–2:** the recorder sidecar, the incremental tailer/parser, the Redis event
stream + replay buffer, the SSE endpoint, the alert engine + FCM sender, and the SSE/FCM transport in the client.
Everything else (Oracle box, Caddy/DuckDNS TLS, Postgres, APScheduler, the `commonMain` Ktor client) already exists
from Phase 2 and is reused.

---

## 3. Sub-stream 1 — the FastF1 live recorder (the feed source)

### 3.1 What FastF1's recorder is — and is not
- Live recording uses `fastf1.livetiming.client.SignalRClient`, or the CLI
  `python -m fastf1.livetiming save [--append] [--debug] [--timeout N] <file>`.
- **Hard constraint to bake in: the recorder cannot do real-time processing.** FastF1's docs are explicit that
  this is *record-now / parse-after-session*. So the recorder gives us only the **raw feed capture**; turning it
  into a live board is **our** backend's job — we must **tail the file as it is written and parse incrementally**,
  not call FastF1's normal `Session.load()` (which is the post-session path, Phase 2).
- `--debug` captures full SignalR frames (recommended — richer for incremental parsing); a normal capture can later
  be turned into the load-able form with `python -m fastf1.livetiming extract <in> <out>`.

  *Source: docs.fastf1.dev/livetiming.html*

### 3.2 Cadence / recording discipline (non-negotiable)
- **Start recording 2–3 minutes before the session opens.** If the opening frames are missing, the parser cannot
  initialize state correctly (it never sees the initializing snapshot). The APScheduler job must arm the recorder
  at **T-3min** for every session we care about.
- The schedule comes from the same source the rest of PitWall uses: **jolpica** for fresh per-session UTC start
  times, with **f1db as the offline fallback** (the bundled `race` table carries
  `free_practice_{1,2,3}_{date,time}`, `qualifying_{date,time}`, `sprint_{...}`, and `race`/`time` columns —
  verified in the bundled SQLite — so the box can compute session windows even if jolpica is down).

### 3.3 The ~2-hour drop is real and server-side
- Verbatim from FastF1 docs: *"The SignalR Client seems to get disconnected after 2 hours of recording. It looks
  like the connection is terminated by the server."*
- This is **not** a client bug we can patch — it is an **F1 server timeout**. A GP race + post-session runs longer
  than 2h, so a single recorder *will* drop mid-session. We must **restart around it**, and the restart must be
  seamless.

### 3.4 Mitigation: auto-restarting sidecar with file rotation + dedupe overlap
The intended, documented-safe way to get a gapless record:
- Run the recorder as an **auto-restarting sidecar** (its own container / supervised process) that **rotates to a
  new output file before the 2h mark** — concretely **rotate every ~100 minutes** with deliberate overlap.
- **Keep two overlapping recorders alive during handover:** start recorder N+1, confirm it is connected and
  writing, *then* stop recorder N. Never let the old connection close before the new one is established — that is
  what produces a gap.
- On merge/parse, FastF1's `LiveTimingData('file1.txt','file2.txt', …)` ingests multiple files **in chronological
  order and automatically de-duplicates overlapping frames**. So overlapping windows are *safe by design* and are
  the intended path to a gapless record.
- For the *live* board we are not waiting for the merge — our tailer reads the active segment file. The rotation
  matters for (a) surviving the 2h drop live, and (b) producing one clean merged artifact afterward (which can feed
  a Phase-4 recap). Our tailer must therefore be **rotation-aware**: when a new `segment-NNN.txt` appears, follow
  it, and dedupe by the same monotonic event key we already assign (§4.2), so the live stream never double-emits a
  frame seen in the overlap window.

  *Source: docs.fastf1.dev/livetiming.html*

### 3.5 THE BIG RISK — `/signalr` → `/signalrcore` + mandatory paid auth
- F1 moved the live endpoint from `/signalr` to `wss://livetiming.formula1.com/signalrcore` (surfaced during the
  2025 Monaco GP, FastF1 issue #753, fixed in **FastF1 v3.7.0**).
- The v3.7.0 release notes state plainly: **"Authentication is only required when using the new live timing client,
  since the new endpoints no longer allow unauthenticated access,"** and that auth is via an **F1TV
  Access/Pro/Premium subscription.**
- There is a `no_auth=True` parameter, but it **only works for some sessions and may return partial/empty data** —
  not reliable for a race.
- **This contradicts the spec.** Spec §6 and §11 say live "may now require a *free* F1.com account." **Verified
  wrong:** it requires a **paid F1TV subscription tier**, not a free account. (The post-session telemetry bake of
  Phase 2 is *unaffected* — auth is only for the live client.)

  *Sources: FastF1 issue #753; FastF1 releases (v3.7.0); fastf1/livetiming/client.py*

> **Spec correction 1 (must land in spec §6, §10, §11):** rewrite the Phase-3 cost line. Literal $0-live is
> **conditional on an existing F1TV subscription** (Access is the cheap tier; many fans already hold it for
> streaming). If nobody in the friend group has F1TV, the genuinely-free options are: (a) `no_auth=True` partial
> data (usable for some sessions, unreliable for a race), or (b) OpenF1's **free historical replay** to build/test
> the whole pipeline off-season. The €9.90/mo OpenF1 live line stays as the clean paid de-risk if neither holds.

### 3.6 Pinning & packaging
- The recorder needs **FastF1 ≥ 3.7.0** for `signalrcore`. The spec/Phase-2 plan pin `fastf1==3.8.3` — that
  already satisfies it; keep it pinned and re-verify the `signalrcore` client API at build (the auth surface is
  young and may shift). Keep `pandas<3`, `numpy<3`, `scipy<2` as the spec pins.
- Sidecar is its own slim `python:3.12-slim` service in the existing `docker compose`, sharing only the
  `recording/` volume with the API. It holds the **F1TV credentials as Docker secrets / env**, never in the repo
  (the repo is public — see §6.4).

---

## 4. Sub-stream 2 — backend → Compose client over SSE

### 4.1 Why SSE (decision holds, now re-confirmed)
Live timing is **one-way server→client**, so SSE is correct: lighter than WebSocket, auto-reconnecting,
no upgrade handshake, plain HTTP (sails through Caddy). Server-side use either:
- `sse-starlette`'s `EventSourceResponse` (production-ready, W3C-compliant), or
- FastAPI **0.135.0+** built-in `EventSourceResponse` at `fastapi.sse` (Pydantic serialization on the Rust side).

Either works; prefer the built-in if we are already on FastAPI ≥ 0.135.0, else `sse-starlette`. WebSocket is only
warranted if we later add client→server live messaging (we don't plan to).

*Sources: FastAPI SSE docs; sse-starlette.*

### 4.2 Event shape (what the backend emits)
Each SSE frame carries:
- **`id:` — a monotonic sequence number per session.** This is the linchpin of catch-up: the client's
  `Last-Event-ID` maps directly to a replay cursor in our event store. Assign seq ids from the Redis stream.
- **`event:` — a type:** one of `timing`, `position`, `flag`, `weather`, `session_state`, `alert`, `prediction`.
- **`data:` — a JSON payload.** Payloads are **deltas (changed rows only)** to keep mobile bandwidth/battery low,
  **plus periodic full snapshots** (e.g. every N seconds and on every flag/session-state change) so a fresh or
  reconnecting client can render immediately without waiting for the next change.

Example frame (timing delta):
```
id: 48213
event: timing
data: {"car":"1","pos":1,"gapToLeader":"LAP 12","interval":"+0.000","lastLap":"1:31.402","tyre":"M","stint":7}
```

### 4.3 Reconnection — verified, with a KMP caveat
- **Browser/standard EventSource** auto-reconnects and replays the `Last-Event-ID` header; the server reads it and
  resumes after that seq, skipping already-sent items. *(Not our client, but it's the reference behaviour the
  protocol promises.)*
- **Ktor client SSE** (our `commonMain` transport) added reconnection in **Ktor 3.1.0** (Feb 2025, KTOR-6242):
  `install(SSE) { maxReconnectionAttempts = N; reconnectionTime = 2.seconds }`.
- **The caveat the Ktor docs do NOT spell out:** the doc page does not confirm Ktor's client *automatically
  re-sends `Last-Event-ID`* on reconnect. **Treat that as unverified.** Do **not** assume browser-style free
  server-side catch-up on KMP. → **Manage the cursor ourselves** (track the last `id` received in the ViewModel,
  pass it as a `Last-Event-ID` header or `?lastEventId=` query param when (re)opening the stream).

  *Sources: Ktor client SSE docs; KTOR-6242; Ktor 3.1.0 release.*

> **Spec correction 2 (add to spec §6 / §11 risks):** SSE catch-up on the KMP client is **not free**. Ktor
> auto-*reconnects* (3.1.0+) but does not document auto-resend of `Last-Event-ID`. Implement the **client-side
> cursor + server-side Redis replay buffer** explicitly.

### 4.4 State catch-up after a drop (survives both the 2h F1 drop and mobile Doze)
Backend keeps, per session, in Redis:
- a **ring buffer of recent events keyed by seq id** (e.g. last 10–15 min worth — enough to cover a reconnect /
  short Doze), and
- the **latest full snapshot**.

On (re)connect the SSE endpoint logic is:
1. If the client sends `Last-Event-ID` and it is **within the buffer** → replay buffered events after it, then
   resume the live tail. (Seamless catch-up; no visual reset.)
2. If `Last-Event-ID` is **stale (gap too large)** or **absent (first connect)** → send the **full snapshot**, then
   resume the live tail.

This is the standard *event-store → Flow with monotonic seq ids, `Last-Event-ID` drives replay* pattern, and it is
exactly what makes SSE robust on mobile: Android Doze kills the TCP connection, but reconnection is protocol-level
and the server replays from `Last-Event-ID`. The same machinery absorbs the recorder's 2h rotation (the tailer's
dedupe keeps seq ids monotonic across segment files, so clients never notice the handover).

  *Source: MVP Factory — SSE as a mobile real-time layer.*

### 4.5 App-code task breakdown (the `commonMain` SSE transport — TDD)

> All of this lives in `commonMain` (shared by Android + desktop). Pure cursor/dedupe logic is unit-tested in
> `commonTest`; the actual socket is an integration concern. Use `superpowers:test-driven-development`.

**New files (under the existing `composeApp`):**
- `commonMain/.../live/LiveEvent.kt` — sealed model of the event types + the seq id.
- `commonMain/.../live/LiveCursor.kt` — pure: holds last-seen seq id, decides snapshot-vs-resume, dedupes.
- `commonMain/.../live/LiveTimingClient.kt` — Ktor SSE transport (`install(SSE){…}`), emits a `Flow<LiveEvent>`,
  re-opens with the cursor's `Last-Event-ID` on drop.
- `commonMain/.../ui/LiveBoardViewModel.kt` + `ui/LiveBoardScreen.kt` — the board.
- `commonTest/.../live/LiveCursorTest.kt`.

- [ ] **Step 1 — failing test for the cursor/dedupe (pure, no socket):**
  - `cursor starts with no lastEventId → first connect requests snapshot`
  - `applying events advances lastEventId monotonically`
  - `an event with seq ≤ lastEventId is dropped (dedupe across rotation/overlap)`
  - `after a gap larger than buffer (server replies snapshot) cursor resets to snapshot's maxSeq`
  - `reconnect emits the stored lastEventId as Last-Event-ID`
- [ ] **Step 2 — run, confirm it fails** (`:composeApp:desktopTest`). *(Planning note only — do not run now.)*
- [ ] **Step 3 — implement `LiveCursor` + `LiveEvent`** to pass.
- [ ] **Step 4 — `LiveTimingClient`:** Ktor `SSE` plugin with `maxReconnectionAttempts`, `reconnectionTime`;
  on (re)open, attach `Last-Event-ID` from the cursor; parse `event:`/`data:` into `LiveEvent`; feed each event
  through the cursor (dedupe) before emitting downstream.
- [ ] **Step 5 — `LiveBoardViewModel`** folds the `Flow<LiveEvent>` into an immutable board `UiState` (a map
  keyed by car number; deltas patch rows, a snapshot replaces the map). `LiveBoardScreen` renders the sorted board.
- [ ] **Step 6 — platform background note (no code in commonMain):** Android needs a **foreground service** to keep
  the SSE connection alive while watching (spec §8); desktop keeps it while the window is open. Wire the FGS in
  `androidMain` only.

### 4.6 Predictions vs friends (lightweight, backend-owned)
- REST: `POST /predictions/{session}` (locked at lights-out, server-enforced), `GET /predictions/{session}/me`.
- Scoring runs server-side at session end from the same parsed result; the **scored leaderboard is pushed as an
  `event: prediction` SSE frame** (and as an FCM alert — §7) so friends see results without polling. Stored in
  Postgres (durable), keyed by the shared friend-group identity (a name + the shared key — no real auth, per spec
  non-goals).

---

## 5. Sub-stream 3 — what's feasible at $0 (data) + cost reframe

**Free path = FastF1 recorder via the `signalrcore` client.** It gives the full official live-timing stream:
positions, gaps/intervals, last/best lap, sector times, tyre/stint, track status (flags, SC/VSC), session state,
weather, and the timing-derived car data. **But it now needs F1TV auth** (§3.5) — so "$0" is true *only* if an F1TV
subscription is already in hand. With `no_auth=True` you get partial/possibly-empty data: usable for some sessions
(practice/quali sometimes), **unreliable for a race**.

**OpenF1** confirms the spec's read: **historical data is free (no auth); real-time/live is paid (on-demand) over
MQTT or WebSocket**, accessed by sponsoring the project. License is **CC-BY-NC-SA** — fine for our non-commercial
friend group (we already honor f1db CC-BY; add OpenF1 attribution if used).

  *Sources: openf1.org/docs; openf1.org/auth.*

**Decision matrix for the Phase-3 boundary:**

| Situation | Live source | Monthly cost | Quality |
|---|---|---|---|
| A friend has F1TV (likely) | FastF1 recorder, authed `signalrcore` | **$0** | Full official feed |
| Nobody has F1TV, want full feed | OpenF1 live (sponsored) | ~€9.90 | Clean, full |
| Nobody has F1TV, must stay $0 | FastF1 `no_auth=True` | $0 | Partial / unreliable for races |
| Off-season / building & testing the pipeline | **OpenF1 free historical replay** through the same SSE path | $0 | Real data, not live |

> **Off-season testbed (strongly recommended):** wire the whole pipeline — tailer → Redis stream → SSE → client —
> against **OpenF1 free historical sessions replayed at wall-clock speed**. This lets the entire Phase-3 stack be
> built, tested, and demoed with **zero F1TV dependency and zero live-feed legal exposure**, then swapped to the
> live recorder only on race weekend. It also de-risks the `signalrcore` auth surface: if F1TV access fails the
> morning of a race, the board still works against replay for development.

---

## 6. Sub-stream 3 (cont.) — legal posture (the whole game)

**The public/private line is now evidence-backed, not theoretical.** **f1-dash** (an open-source live-timing
dashboard) is **currently IP-blocked by F1**: its own site states *"Due to IP Blocking measures introduced by
Formula 1, f1-dash is currently unavailable. Users can selfhost f1-dash and continue to use it that way."* This
confirms the spec's thesis exactly: **F1 blocks *public hosted* live-timing services, but the tolerated remedy is
self-hosting (private).**

  *Sources: f1-dash.com/help; f1-dash.com/dashboard; github.com/slowlydev/f1-dash.*

**Hard rules for PitWall's live layer (these override convenience):**

1. **The live SSE endpoint must NEVER have a public URL.** Gate it behind a **shared friend-group key** (a static
   secret in a header or query param, checked by FastAPI before the `EventSourceResponse` opens). Keep the Oracle
   box's live endpoint **un-advertised** — not linked from the public repo, not in the README, not in any listing.
2. **Source code public (OSS norm) is fine; a public live *service* is the line F1 enforces.** Publishing the
   recorder/parser/SSE code is exactly what FastF1 / f1db / OpenF1 / f1-dash all do and is tolerated. What gets
   IP-blocked is an open, hosted, public live board. So: **public repo, private deployment.**
3. **F1's ToU asserts copyright + database rights over live timing and restricts to personal/non-commercial use.**
   Private consumption by a handful of friends is grey-but-tolerated; **public rebroadcast is what gets blocked.**
   Keep usage to the friend group; never expose, never monetize.
4. **Secrets discipline (because the repo is public):** F1TV credentials and the friend-group shared key live in
   Docker secrets / `.env` on the box, in `.gitignore`, **never committed**. Add a `.env.example` with placeholder
   keys only. (The Phase-0 `.gitignore` already excludes `local.properties`; extend the backend's `.gitignore` to
   cover `.env`, `recording/`, and any `*.txt` capture files so feed captures are never pushed.)
5. **Trademark, unchanged:** still no "F1"/"Formula 1"/logo in branding (app name stays **PitWall**). Describing it
   as a Formula 1 companion in prose is fine; using the marks as branding is not.
6. **Attribution, unchanged + extended:** keep f1db CC-BY in the Licenses screen; if OpenF1 is used, add its
   CC-BY-NC-SA credit. Live timing itself is *consumed*, not redistributed publicly, so there is nothing to
   "attribute" publicly — but the disclaimer ("unofficial, not affiliated with Formula 1; F1 marks owned by
   Formula One Licensing B.V.") stays prominent.

> **Spec correction 3 (reinforce spec §9.2):** the live legal rule is now backed by live evidence (f1-dash actively
> IP-blocked → self-host tolerated). Bake the shared-key gate + no-public-URL rule into the SSE endpoint from day
> one; it is not optional hardening.

---

## 7. Sub-stream 4 — Android push (FCM) vs desktop (no push)

### 7.1 FCM is genuinely $0, no limits
Confirmed for 2026: FCM is **free on both the Spark (no-cost) and Blaze plans** — no per-message charge, no
message-count cap, no overage fees, whether sending 10/day or 10M. Sending from our FastAPI backend uses the
**HTTP v1 API authenticated with a Firebase service-account JSON / OAuth2 token** — works from any non-Google
server (the Oracle box) and **does not require the Blaze plan.**

  *Sources: Firebase pricing; FCM HTTP v1 send; FCM server environment.*

### 7.2 Alert shape — `priority: high` *notification* messages (critical reliability detail)
For time-sensitive alerts (lights-out, red flag, SC/VSC, fav-driver podium):
- Send **`priority: high`** so FCM can **wake a Dozing device immediately**. Normal-priority messages are deferred
  to a Doze maintenance window — **unacceptable for "lights out."**
- Send **high-priority *notification* messages (not data-only)**. High-priority notification messages that meet
  criteria are **proxied/displayed by Google Play Services directly**, so the alert shows **even if the app isn't
  running** — the right shape for PitWall's alerts.
- **Reserve data-only messages for non-urgent background syncs.** Data-only messages are known to be **queued in
  Doze even at high priority** — wrong tool for a lights-out alert.

  *Sources: FCM message priority; FCM Android delivery (firebase.blog 2025/04).*

> **Spec correction 4 (add to spec §8 / the alert sender):** FCM alerts must be **`priority: high` *notification*
> messages**, not data-only, to survive Doze and be Play-Services-proxied. Bake this into the backend alert sender.

### 7.3 Desktop has no push — the spec's call holds and is unavoidable
Compose/macOS desktop **cannot receive FCM background push**; the only mechanism is `trayState.sendNotification` →
Notification Center, **and only while the app window is running.** So Phase-3 alerts are **📱 Android only** in the
background; on desktop, alerts are in-app / tray **while open**.

**Clean fan-out architecture (no second alert pathway):** the backend alert engine fans **one alert event** to
**both**:
- **FCM** (Android, background-capable, `priority:high` notification — §7.2), and
- the **SSE stream** as `event: alert` (the desktop app, if open, turns it into a tray notification; the Android
  app, if in foreground, can render it in-app too).

This means alert rules live in exactly one place (the backend), and each client surfaces them with its own
native mechanism.

### 7.4 Alert engine (backend) — rules over the parsed event stream
The engine consumes the same normalized event stream that feeds SSE and fires on transitions (state changes, not
levels), de-duplicated by event seq id:
- `session_state → started` ⇒ **"Lights out"** (high-priority).
- `flag → RED` / `track_status → SC|VSC` ⇒ **flag alerts** (high-priority).
- a watched driver's `position` crosses into top-3, or a watched driver's `stint`/tyre changes (pit) ⇒
  **fav-driver alerts** (high-priority for podium, normal-priority acceptable for routine pit if we want quieter).
- predictions scored ⇒ **leaderboard alert** (normal-priority — not time-sensitive).

Each friend registers their FCM token + their watched driver(s) via a tiny REST call gated by the same shared key.
"Watched driver" maps cleanly to f1db `driver.id`, so the favorite picker reuses the bundled dataset already in the
app — no new data source.

### 7.5 App-code notes for FCM (Android only, `androidMain`)
- Add `firebase-messaging` in `androidMain` only (it cannot live in `commonMain` — Android-only, like Hilt; this is
  the same constraint the spec already records for push). Desktop has no equivalent and must not try to depend on
  it.
- A `FirebaseMessagingService` subclass receives token refreshes (POST to the backend, shared-key gated) and, for
  the rare data-only background sync, handles the payload. For the high-priority *notification* alerts, Play
  Services renders them without app code — that is the point of choosing notification messages.
- **No `google-services.json` in the public repo.** It is project config, not a secret per se, but keep it out of
  the public repo for friend-group privacy and add it via local file / CI secret. (`.gitignore` it.)

---

## 8. Build & ops sequencing (de-risked order)

This phase is **infra-heavy**; build it in an order that lets each piece be validated against free/replay data
*before* touching the live feed:

1. **Off-season pipeline on replay (no F1TV, no live feed):** OpenF1 free historical → tailer → Redis stream
   (seq ids) → SSE endpoint (shared-key gated) → `commonMain` Ktor SSE client → `LiveBoardScreen`. Prove the full
   path, the cursor/replay-buffer reconnection (§4.4), and the dedupe across simulated "rotation."
2. **Alert engine on replay:** fire FCM (`priority:high` notification) + SSE `alert` frames from replayed events;
   verify an Android device shows lights-out/flag alerts while backgrounded and Dozing; verify desktop tray while
   open.
3. **Recorder sidecar (needs F1TV):** stand up the auto-restarting recorder against a **live practice session**
   first (lowest stakes, ≤2h, validates auth + the T-3min arm). Confirm `signalrcore` auth works with the F1TV
   credentials; confirm `no_auth=True` behavior as documented fallback.
4. **Rotation under load:** validate the ~100-min rotation + dual-recorder overlap + dedupe across a full
   **race + post-session** (>2h) — this is where the 2h drop bites; prove the handover is gapless on the client.
5. **Predictions vs friends:** lock/scoring/REST/leaderboard SSE — lowest risk, can land last.

**Per-session runbook (APScheduler-driven, plus a manual checklist):**
- T-1h: scheduler confirms F1TV auth token is valid; pre-warms Redis session keys.
- **T-3min: arm recorder** (the cadence rule from §3.2 — opening frames are mandatory).
- During: tailer parses, board live; rotation every ~100 min with overlap; alert engine armed.
- Session end: stop recorders after a grace window; trigger the FastF1 `extract`/merge of overlapping segments into
  one clean artifact (this also feeds the Phase-4 recap if/when built).
- Cost watch: $0 — confirm Oracle egress well under 10 TB/mo (it will be; a friend-group SSE stream is kilobytes/s).

---

## 9. Risks & open decisions (Phase-3-specific)

| Risk | Severity | Mitigation / decision |
|---|---|---|
| **`signalrcore` requires *paid* F1TV auth** (not free, contra spec) | **High** | Reframe cost line (§3.5). Confirm a friend's F1TV before committing to the free path; else OpenF1 €9.90 or `no_auth` partial. Build/test on OpenF1 replay so live is the only F1TV-dependent step. |
| Ktor SSE may not auto-resend `Last-Event-ID` | Medium | Own the cursor client-side + Redis replay buffer server-side (§4.3–4.4). Do not rely on browser semantics. |
| 2h F1 server drop mid-race | Medium | Auto-restart sidecar, ~100-min rotation, dual-recorder overlap, dedupe on merge + in tailer (§3.4). Validate over a full >2h race (step 4). |
| Public exposure of live service → IP block / C&D | **High (legal)** | Shared-key gate, no public URL, never advertised; public repo / private deployment (§6). |
| Secrets leaking into public repo | High | F1TV creds, shared key, `google-services.json` all `.gitignore`d; `.env.example` only; secrets via Docker secrets/CI (§6.4, §7.5). |
| Android Doze killing live SSE while watching | Medium | Foreground service for the SSE connection (`androidMain`); protocol-level reconnect + replay buffer absorbs short drops (§4.4–4.5). |
| FastF1 `signalrcore`/auth API churn | Medium | Pin `fastf1==3.8.3`; re-verify the live-client + auth API at build (young surface). |
| jolpica fragility for session schedule | Low | f1db `race` per-session date/time columns as offline fallback (§3.2). |
| Oracle A1 / box reliability during a live session | Low–Med | Same box already proven in Phase 2; recorder + API are lightweight; Hetzner CAX11 (~€4.99) is the paid fallback per spec. |

---

## 10. Cost summary (Phase 3, corrected)

| Path | Monthly | When |
|---|---|---|
| FastF1 recorder, **F1TV already owned** | **$0** software (F1TV is a sunk personal cost) | Default if a friend has F1TV |
| FastF1 `no_auth=True` | **$0** | Fallback; partial/unreliable for races |
| OpenF1 live (sponsored) | **~€9.90** | Clean paid de-risk |
| OpenF1 historical replay | **$0** | Build/test/off-season — recommended testbed |
| FCM push (Android) | **$0** | Always — no caps, Spark plan, HTTP v1 from the box |
| SSE / Oracle egress | **$0** | Friend-group traffic is trivially under 10 TB/mo |

**Net:** Phase 3 is **$0 software**; the only possible spend is the optional €9.90 OpenF1 live line, and only if no
one in the group holds F1TV. The spec's blanket "$0 (FastF1 recorder)" for Phase 3 must be qualified with the F1TV
condition.

---

## 11. Four spec corrections this plan requires (consolidated)

1. **Spec §6/§11 "may need a *free* F1.com account" → WRONG.** FastF1 v3.7.0 `signalrcore` requires a **paid F1TV
   Access/Pro/Premium subscription**. Post-session bake (Phase 2) unaffected. Reframe the Phase-3 cost line.
2. **SSE catch-up on KMP is not free.** Ktor auto-reconnects (3.1.0+) but does not document re-sending
   `Last-Event-ID`. Implement client-side cursor + server-side Redis replay buffer.
3. **Live legal rule is evidence-backed, not theoretical.** f1-dash is *actively IP-blocked*; self-host is the
   tolerated path. PitWall's live SSE endpoint **must be private — shared-key gated, no public URL.**
4. **FCM alerts must be `priority:high` *notification* messages** (not data-only) to survive Doze and be
   Play-Services-proxied. Bake into the backend alert sender.

---

## 12. Sources (verified)

- FastF1 live-timing — docs.fastf1.dev/livetiming.html · issue #753 · releases (v3.7.0) · fastf1/livetiming/client.py
- SSE — FastAPI SSE docs · sse-starlette · Ktor client SSE docs · KTOR-6242 · Ktor 3.1.0 release ·
  MVP Factory "SSE as your mobile real-time layer"
- Data/legal — openf1.org/docs · openf1.org/auth · f1-dash.com/help · f1-dash.com/dashboard · github.com/slowlydev/f1-dash
- FCM — Firebase pricing · FCM HTTP v1 send · FCM server environment · FCM message priority · FCM Android delivery (firebase.blog 2025/04)
- PitWall internal — design spec `2026-06-14-f1-app-design.md`; Phase-0 plan `2026-06-14-pitwall-phase0-hero.md`;
  f1db bundled SQLite `race` table (per-session date/time columns, verified)
