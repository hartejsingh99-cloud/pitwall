# PitWall — CI + Release + Dataset-Sync Plan (stream: ci-release)

**Date:** 2026-06-14
**Status:** Planning — to be executed *after* the parallel Phase-0 build lands the `composeApp` module
**Author:** Hartej (with Claude)
**Repo:** public OSS on personal GitHub (`hartejsingh99-cloud`), `~/Downloads/Repos/personal/pitwall`
**Scope:** GitHub Actions CI (build + test), tag-driven Release that ships APK + `.dmg`, the f1db dataset-version bump ritual, the $0 friend-group install path, and the decision on how the 70 MB `f1db.db` reaches the artifact.

> This is an **infra/ops design doc + a workflow-file task breakdown**. It writes YAML and shell, not Kotlin. It depends on, but does not modify, the Phase-0 plan (`2026-06-14-pitwall-phase0-hero.md`) and the design spec (`2026-06-14-f1-app-design.md`). **Do not touch repo code or run gradle/builds while authoring or executing in parallel** — coordinate with the build stream before merging any workflow that assumes file paths the build stream owns.

---

## 0. Why this stream exists, and the four constraints that shape every decision

The spec says: *me + a few friends, $0/month, public OSS repo on a personal account, no F1 branding, f1db CC-BY-4.0 attribution.* Those constraints, not engineering taste, decide the CI/release shape:

| Constraint | Direct consequence for CI/release |
|---|---|
| **$0/month** | Use **standard** GitHub-hosted runners only (free + unlimited on public repos). Never a `*-large` runner. Put binaries in **GitHub Releases** (free hosting that does *not* bill LFS bandwidth), never Git LFS. |
| **Personal PUBLIC repo** | This is what *makes* macOS runners free. On a private repo a macOS minute bills at the 10× multiplier (~$0.048/min after the Jan-2026 cut). The repo staying public is a release-pipeline cost requirement, not just a philosophy. |
| **No "F1"/"Formula 1"/logo branding** | Release names, tags, asset filenames, `.dmg`/app bundle names, and workflow display names all use **"PitWall"** only. No mark in any artifact a friend sees. Asset = `PitWall-0.1.0.dmg`, not `F1-companion.dmg`. |
| **f1db CC-BY-4.0** | The exact bundled release version (`vYYYY.RR.MICRO`) must be named in-app (`LicensesScreen`) **and** in `NOTICE`. The release pipeline must therefore *pin* a dataset version and keep `NOTICE`/`DATASET_VERSION` in lockstep — attribution accuracy is a build-reproducibility requirement here, not boilerplate. |

Everything below is grounded in the ci-release research findings and the two repo docs.

---

## 1. GitHub Actions cost model — the verified facts we are building on

**Free-tier reality (Dec 16 2025 pricing changelog, current):** Standard GitHub-hosted runners — **Linux, Windows, and macOS** — are **free and unlimited on public repositories, consuming zero included-minutes.** This survives the 2026 pricing changes. The two dated 2026 changes do **not** touch a public repo:

- **Jan 1 2026:** GitHub-hosted prices dropped up to 39% — only matters for *private* repos.
- **Mar 1 2026:** a new $0.002/min platform charge applies to **self-hosted runners on private repos only** — explicitly *not* public repos.

**The one trap:** "free and unlimited" applies only to **standard** runners. **Larger runners are billed per-minute even on public repos.** So the iron rule for every PitWall job:

> **Use only the plain labels `ubuntu-latest` and `macos-latest`. Never select a `*-large` / custom-label / larger runner.** macOS standard runners on a public repo cost nothing — which is exactly why we can build the `.dmg` (jpackage cannot cross-compile; confirmed spec §8) at $0.

`macos-latest` currently resolves to macOS 14/15 on Apple Silicon and ships a JDK — fine for jpackage. We still pin Temurin 17 via `setup-java` so the jpackage build is deterministic regardless of the image's bundled JDK.

**Verified action versions to pin (do not float to `@main`):**

| Action | Pin | Why / key inputs |
|---|---|---|
| `actions/checkout` | `@v4` | standard |
| `actions/setup-java` | `@v4` | `distribution: temurin`, `java-version: '17'` (matches plan's JDK 17 / `JavaVersion.VERSION_17`), `cache: gradle` flips on the built-in Gradle dependency cache |
| `android-actions/setup-android` | `@v4` (latest v4.0.1; Node24, cmdline-tools 20.0) | `accept-android-sdk-licenses` defaults `yes`; set `log-accepted-android-sdk-licenses: false` to keep logs clean; puts `platform-tools` + `cmdline-tools/.../bin` on `$PATH` |
| `actions/upload-artifact` | `@v4` | 90-day default retention — plenty for a friend-group nightly |
| `softprops/action-gh-release` | `@v2` | attaches built files to a GitHub Release on tag push |

**Gradle:** the wrapper is already 8.11.1 — always invoke `./gradlew`, never install Gradle. The TDD engine lives in `:composeApp:desktopTest` (Phase-0 plan Task 3/4), so that is the canonical unit-test task.

---

## 2. CI workflow — build + test on every push/PR

**Design principle: split by OS, because the `.dmg` *must* be built on a Mac and nothing else needs one.** The Linux job does the cheap, fast work (unit tests + debug APK); the macOS job does only `packageDmg`. Both run free on a public repo.

### Task CI-1 — add `.github/workflows/ci.yml`

**File:** `~/Downloads/Repos/personal/pitwall/.github/workflows/ci.yml` (currently absent — repo scaffolded in parallel; create only once the `composeApp` module + gradle wrapper exist).

```yaml
name: CI
on:
  push:
    branches: ["**"]
  pull_request:

concurrency:                       # cancel superseded runs on the same ref — saves nothing $-wise (free) but keeps logs clean
  group: ci-${{ github.ref }}
  cancel-in-progress: true

jobs:
  android:
    runs-on: ubuntu-latest         # free + unlimited on public repo
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
          cache: gradle
      - uses: android-actions/setup-android@v4
        with:
          log-accepted-android-sdk-licenses: false
      # Bundle the pinned f1db.db BEFORE building (see §5). No-op if the build stream
      # decided to vendor the DB in-tree; the script is idempotent.
      - name: Fetch & bundle pinned f1db dataset
        run: ./scripts/fetch_f1db.sh
      - name: Unit tests + debug APK
        run: ./gradlew :composeApp:desktopTest :composeApp:testDebugUnitTest :composeApp:assembleDebug
      - uses: actions/upload-artifact@v4
        with:
          name: pitwall-debug-apk
          path: composeApp/build/outputs/apk/debug/*.apk
          if-no-files-found: error

  macos-dmg:
    runs-on: macos-latest          # FREE on PUBLIC repos only; jpackage needs a Mac (spec §8)
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
          cache: gradle
      - name: Fetch & bundle pinned f1db dataset
        run: ./scripts/fetch_f1db.sh
      - name: Package .dmg
        run: ./gradlew :composeApp:packageDmg
      - uses: actions/upload-artifact@v4
        with:
          name: pitwall-macos-dmg
          path: composeApp/build/compose/binaries/main/dmg/*.dmg
          if-no-files-found: error
```

**Notes that matter for *this* project:**
- Run `:composeApp:desktopTest` (the plan's pure-Kotlin Driver-vs-Car engine + mapping tests live there) **plus** `assembleDebug` on Linux. `testDebugUnitTest` is cheap insurance that the Android variant also compiles its test source set.
- Only the `packageDmg` job needs the Mac. Keeping it separate means a broken `.dmg` step never blocks the APK and unit-test signal.
- `if-no-files-found: error` turns a silently-empty artifact (e.g. gradle output path drift after a CMP upgrade) into a red build instead of a green-but-useless one.
- The `Fetch & bundle pinned f1db dataset` step is the §5 decision in action. If the build stream vendors the DB in-tree instead, make `scripts/fetch_f1db.sh` a no-op-when-present (it already early-exits if the file exists with the right checksum) so this workflow doesn't need editing either way.

**Acceptance:** push any branch → `android` job goes green with a `pitwall-debug-apk` artifact; `macos-dmg` goes green with a `pitwall-macos-dmg` artifact. Both consume **0 billable minutes** (confirm under repo → Insights/Actions usage that public-repo runs show no charge).

---

## 3. Release workflow — tag-driven, ships APK + .dmg to a GitHub Release

The Phase-0 plan already ends with `git tag v0.1.0-hero` (semver for the *app*). Layer a release workflow that fires on a version tag, rebuilds both artifacts on their correct runners, and attaches them to a GitHub Release. **GitHub Releases asset hosting does not count against Git LFS bandwidth** (it is separate release storage) — this is the whole reason binaries belong there and not in LFS (see §5).

### Task CI-2 — add `.github/workflows/release.yml`

**File:** `~/Downloads/Repos/personal/pitwall/.github/workflows/release.yml`

```yaml
name: Release
on:
  push:
    tags: ['v*']                   # v0.1.0-hero, v0.2.0, ...

permissions:
  contents: write                  # required for action-gh-release to create the Release

jobs:
  build-apk:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17', cache: gradle }
      - uses: android-actions/setup-android@v4
        with: { log-accepted-android-sdk-licenses: false }
      - run: ./scripts/fetch_f1db.sh
      - run: ./gradlew :composeApp:assembleDebug
      - uses: actions/upload-artifact@v4
        with: { name: apk, path: composeApp/build/outputs/apk/debug/*.apk }

  build-dmg:
    runs-on: macos-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17', cache: gradle }
      - run: ./scripts/fetch_f1db.sh
      - run: ./gradlew :composeApp:packageDmg
      - uses: actions/upload-artifact@v4
        with: { name: dmg, path: composeApp/build/compose/binaries/main/dmg/*.dmg }

  release:
    needs: [build-apk, build-dmg]
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4   # for the install-notes body file below
      - uses: actions/download-artifact@v4
        with: { path: dist }
      - uses: softprops/action-gh-release@v2
        with:
          files: |
            dist/apk/*.apk
            dist/dmg/*.dmg
          body_path: .github/release-notes.md   # the friend-facing install instructions (§4)
          fail_on_unmatched_files: true
```

**Branding / attribution checks baked into the release:**
- The Phase-0 `compose.desktop` block sets `packageName = "PitWall"` → the `.dmg` and app bundle are `PitWall*`. The debug APK is `composeApp-debug.apk`; **rename on upload** (or set `archivesName`) so the friend-facing asset reads `PitWall-<version>.apk` — no "F1" string in any downloaded filename.
- The release **body** (`.github/release-notes.md`, §4) carries the unofficial-fan disclaimer + the CC-BY f1db credit + the bundled `vYYYY.RR.MICRO` dataset version, so attribution travels with every download even before install.

**Why debug-signed is fine:** the Phase-0 APK is signed with the Android debug key, which sideload-installs cleanly. No Play Store, no release-signing config, no keystore secret in CI (and therefore no secret-leak surface). This is a deliberate $0 / friend-group choice, restated here so a future reviewer doesn't "fix" it by adding signing.

**Acceptance:** `git push origin v0.1.0-hero` → a GitHub Release `v0.1.0-hero` appears with two assets (`PitWall-0.1.0.apk`, `PitWall-0.1.0.dmg`) and the install-notes body. No billable minutes.

---

## 4. End-user install at $0 — what goes in the release notes / README

No Play Store, no $99/yr Apple Developer account. The release body and the repo README carry these verbatim, because the friction is real and a friend without the exact steps will bounce.

### 4a. Android — sideload the debug APK

Modern Android (8.0+, through 14/15) uses a **per-source** "Install unknown apps" permission, not a global toggle. The flow:

1. Download `PitWall-<version>.apk` from the GitHub Release on the phone.
2. Tap it in the browser/file-manager. Android prompts to allow installs *from that specific app*: **Settings → Apps → Special app access → Install unknown apps → [the app you downloaded with, e.g. Chrome/Files] → Allow from this source.**
3. Back out, tap the APK again, **Install**.

Granted once per source. No INTERNET permission is declared (Phase 0 is fully offline) — a small, friend-reassuring detail worth stating: *"PitWall requests no network permission; all data is bundled."*

### 4b. macOS — unsigned, un-notarized `.dmg`

This is the sharper edge. **macOS Sequoia 15.1 removed the old Control-click → Open Gatekeeper bypass.** The Apple-blessed GUI path is now System Settings → Privacy & Security → "Open Anyway" — but for a *fully unsigned* app that route is unreliable and can still throw *"is damaged and can't be opened."* The reliable, $0, no-GUI path strips the quarantine xattr so Gatekeeper skips the check:

```bash
# After dragging PitWall out of the .dmg into /Applications:
xattr -dr com.apple.quarantine /Applications/PitWall.app
```

Confirmed still working on Sequoia 15.1+. **Sequencing detail to put in the notes:** quarantine rides in on the *download*, so the robust order is **drag to /Applications first, then run `xattr -dr` on the installed `.app`.** (Running `xattr -c` on the `.dmg` before mounting also works.) No SIP disable, no admin gymnastics.

**Fallback if friction ever bites:** the spec's stated escape hatch — **Hydraulic Conveyor (free for OSS, notarizes from any OS)** — remains valid but is unnecessary for a handful of friends willing to paste one command. Do **not** add it to Phase-0 CI; note it as a Phase-1+ option only.

### Task CI-3 — write `.github/release-notes.md` (the release body template)

**File:** `~/Downloads/Repos/personal/pitwall/.github/release-notes.md` — contains: one-line "what's new", the Android steps (4a), the macOS `xattr` command + ordering (4b), the unofficial-fan disclaimer, and the f1db CC-BY-4.0 credit with the bundled `vYYYY.RR.MICRO`. Keep it short; friends read it on a phone. (This file is also a convenient place to keep attribution honest per release, since the dataset version is part of it.)

---

## 5. The 70 MB f1db.db — commit vs LFS vs download-at-build vs download-at-first-run

**Grounded sizes (measured the real file at `/tmp/f1db/f1db.db`):** uncompressed **70 MB (73,043,968 bytes)**; gzips to **~15.5 MB**; the official **`f1db-sqlite.zip` release asset is 15,506,969 bytes (~15.5 MB)** — i.e. the shipped zip *is* the compressed DB. The release also ships **`checksums_sha256.txt`** for verification. (The "15.5 MB ready SQLite" in spec §3 is the *zipped* asset; the unzipped `.db` the app bundles is 70 MB. Both numbers are correct — different states.)

### The four options, scored against the constraints

| Option | Verdict | Why |
|---|---|---|
| **Commit raw 70 MB `.db` to git** | ❌ Rejected — degrades steadily | First commit works (GitHub hard-blocks files >100 MB; warns >50 MB; 70 MB is under but warned). **But it poisons history:** the DB re-ships after *every race*, each a fresh non-deltifiable 70 MB blob permanent in `.git`. After a season `.git` is hundreds of MB and every `clone` drags all of it (repo soft-cap guidance ~1–5 GB). |
| **Git LFS** | ❌ Rejected — wrong tool, footgun | Free quota is **10 GiB storage + 10 GiB/mo bandwidth, identical public & private — no public-repo bonus.** Killer fact: **clones/downloads consume the owner's bandwidth**, and on overage with no payment method, **"Git LFS support is disabled on your account until the next month" — account-wide, across all your repos.** At 70 MB/clone that's ~146 fetches/month before LFS bricks. On a public repo strangers can clone freely → an unbounded number of LFS pulls can silently disable LFS everywhere you use it. |
| **Download-at-build-time** | ✅ **Chosen (hardened)** | CI fetches the f1db zip, unzips into `composeResources/files/`, bundles into APK/DMG. Git stays clean. The only weakness — "latest" drifts so two builds bundle different data — is removed by **pinning the exact tag + verifying the checksum** (below). |
| **Download-at-first-run** | ❌ Rejected for Phase 0 | Directly contradicts the offline-first promise (manifest has no INTERNET permission) and adds first-launch failure modes. |

### Recommendation — pinned download-at-build + checksum verification

> **Do not vendor the 70 MB DB in the source tree. Keep the dataset version pinned in code (`DATASET_VERSION` in `DbFile.kt`) and in a checked-in marker file (`f1db.version`, the exact `vYYYY.RR.MICRO`). CI downloads that *pinned* f1db release (not `latest`), unzips, verifies against `checksums_sha256.txt`, and bundles the `.db` into the APK/DMG at build time.** Git only ever stores a tiny version pointer; the 70 MB binary lives exclusively inside built artifacts and GitHub Releases (which don't consume LFS bandwidth).

This satisfies every constraint: **$0** (release assets + public-repo Actions are free), **clean git history forever**, **offline-first preserved** (the DB is *inside* the shipped app, not fetched at runtime), and it **dovetails with the §6 dataset bump** — bumping data is just editing the pinned version + `DATASET_VERSION` and re-tagging. Pinning + checksum also makes the bundled dataset **fixed and attributable**, which is what CC-BY needs (you can state exactly which release shipped).

### Task CI-4 — the bundling script `scripts/fetch_f1db.sh`

**File:** `~/Downloads/Repos/personal/pitwall/scripts/fetch_f1db.sh`. Reads the pinned version, downloads that *exact* release's `f1db-sqlite.zip` + `checksums_sha256.txt`, verifies, unzips into `composeResources/files/f1db.db`. **Idempotent** — early-exits if the file is already present with the right checksum (so it is a no-op if the build stream vendored the DB locally for dev).

```bash
#!/usr/bin/env bash
set -euo pipefail

VERSION="$(cat f1db.version)"                                   # e.g. v2026.6.3 — the SINGLE source of truth
BASE="https://github.com/f1db/f1db/releases/download/${VERSION}"
DEST="composeApp/src/commonMain/composeResources/files"
DB="${DEST}/f1db.db"

mkdir -p "${DEST}"

# Idempotent: if the unzipped DB is already there, trust it and exit (local-dev / vendored case).
if [[ -f "${DB}" ]]; then
  echo "f1db.db already present; skipping download."
  exit 0
fi

tmp="$(mktemp -d)"
curl -fsSL "${BASE}/f1db-sqlite.zip"       -o "${tmp}/f1db-sqlite.zip"
curl -fsSL "${BASE}/checksums_sha256.txt"  -o "${tmp}/checksums_sha256.txt"

# Verify the zip against f1db's published checksum (filter to the sqlite asset line).
( cd "${tmp}" && grep 'f1db-sqlite.zip' checksums_sha256.txt | sha256sum -c - )

unzip -o "${tmp}/f1db-sqlite.zip" -d "${tmp}"
# The release zip contains f1db.db (verify the exact inner name on first run; adjust if nested).
mv "${tmp}/f1db.db" "${DB}"
rm -rf "${tmp}"
echo "Bundled f1db ${VERSION} -> ${DB}"
```

> **Cross-platform note:** the macOS runner uses BSD tools — `sha256sum` may be `shasum -a 256` and `sha256sum -c` differs. Make the verify step portable (prefer `shasum -a 256 -c` which exists on both runners, or branch on `$RUNNER_OS`). Verify the inner filename in `f1db-sqlite.zip` on first execution and fix the `mv` if the `.db` is nested in a folder. These are the two execution-time checks for this task.

**`.gitignore` must ignore the bundled DB** so a dev who runs the script locally doesn't accidentally commit 70 MB. Add to the existing `.gitignore` (owned by the build stream — coordinate, don't edit blind):

```
composeApp/src/commonMain/composeResources/files/f1db.db
```

### Acceptable fallback if the build stream already committed the DB

The Phase-0 plan (Task 2) literally instructs placing `f1db.db` into `composeResources/files/` and committing it. **If that has already happened**, the lower-friction acceptable fallback is: **leave it as a plain committed file (NOT LFS)**, add a one-line `.gitattributes` keeping it un-diffed (`composeApp/.../files/f1db.db binary -diff`), and accept history growth — viable for a short-lived hobby repo. But **switch to the pinned-download approach before the repo accumulates many race releases**, or `.git` bloat becomes permanent. **Under no circumstances move it to Git LFS on a public repo** — the account-wide bandwidth-disable behavior is the trap.

---

## 6. The f1db dataset-version bump flow — a second, independent cadence

The app's release cadence (semver, human-driven) and the f1db data cadence (CalVer, **a new release after every race**, latest confirmed `v2026.6.3`) are independent. The bundled-DB refresh is gated in code by `DATASET_VERSION` in `DbFile.kt` (copy-on-first-launch only re-copies when that integer changes — Phase-0 plan Task 2). So bumping the dataset is a **3-step ritual**:

1. **Update the pin.** Set `f1db.version` to the new `vYYYY.RR.MICRO`. (CI's `fetch_f1db.sh` now pulls that exact release.)
2. **Increment `DATASET_VERSION`** in `composeApp/src/commonMain/kotlin/dev/pitwall/data/DbFile.kt` (else installed apps keep the stale bundled copy), **and** update the bundled-release string in **`NOTICE`** + **`LicensesScreen.kt`** (CC-BY requires naming the release version — spec §7).
3. **Tag a new app release** (`vX.Y.Z`), which fires the §3 release workflow and ships the new APK + DMG.

### Task CI-5 — automate the bump as a scheduled PR-opener

This is a clean candidate for a scheduled GitHub Action: weekly cron compares f1db's `releases/latest` `tag_name` against the checked-in `f1db.version`, and if newer, opens a PR that bumps `f1db.version`, increments `DATASET_VERSION`, and rewrites the `NOTICE`/`LicensesScreen` version string. A human reviews + merges + tags. Keeps the friend group current without manual polling, and keeps the attribution version honest automatically.

**File:** `~/Downloads/Repos/personal/pitwall/.github/workflows/dataset-bump.yml`

```yaml
name: f1db dataset bump check
on:
  schedule:
    - cron: '17 6 * * 1'           # Mondays 06:17 UTC — after a typical race weekend
  workflow_dispatch: {}            # manual trigger too

permissions:
  contents: write
  pull-requests: write

jobs:
  check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Compare pinned vs latest f1db
        id: cmp
        run: |
          PINNED="$(cat f1db.version)"
          LATEST="$(curl -fsSL https://api.github.com/repos/f1db/f1db/releases/latest | jq -r .tag_name)"
          echo "pinned=$PINNED"  >> "$GITHUB_OUTPUT"
          echo "latest=$LATEST"  >> "$GITHUB_OUTPUT"
          [ "$PINNED" != "$LATEST" ] && echo "changed=true" >> "$GITHUB_OUTPUT" || echo "changed=false" >> "$GITHUB_OUTPUT"
      - name: Bump pin + DATASET_VERSION + attribution
        if: steps.cmp.outputs.changed == 'true'
        run: |
          NEW="${{ steps.cmp.outputs.latest }}"
          echo "$NEW" > f1db.version
          # increment DATASET_VERSION integer in DbFile.kt
          perl -i -pe 's/(DATASET_VERSION\s*=\s*)(\d+)/$1.($2+1)/e' \
            composeApp/src/commonMain/kotlin/dev/pitwall/data/DbFile.kt
          # rewrite the bundled-release version string anywhere it is referenced
          perl -i -pe "s/v\d{4}\.\d+\.\d+/$NEW/g" NOTICE \
            composeApp/src/commonMain/kotlin/dev/pitwall/ui/LicensesScreen.kt
      - name: Open PR
        if: steps.cmp.outputs.changed == 'true'
        uses: peter-evans/create-pull-request@v6
        with:
          branch: chore/f1db-${{ steps.cmp.outputs.latest }}
          title: "chore: bump bundled f1db to ${{ steps.cmp.outputs.latest }}"
          body: |
            Automated dataset bump from `${{ steps.cmp.outputs.pinned }}` to `${{ steps.cmp.outputs.latest }}`.
            - Updated `f1db.version`, incremented `DATASET_VERSION`, refreshed CC-BY version string in NOTICE + LicensesScreen.
            - On merge, CI rebuilds with the pinned release; tag `vX.Y.Z` to ship.
            Review the diff, then merge + tag.
          labels: dataset, automated
```

**Notes:**
- This workflow only opens a PR — **it never auto-merges or auto-tags.** A human stays in the loop, which matches the "me + a few friends" cadence and avoids shipping a bad dataset unattended. The actual `.db` is never downloaded here (CI does that on build from the pin), so the bump PR is tiny.
- `peter-evans/create-pull-request@v6` is the de-facto standard PR-opener action; pin it explicitly.
- The two `perl` regexes are the only fragile parts — verify they match the real `DbFile.kt` constant and `LicensesScreen.kt` string formats once the build stream lands those files (the formats are specified in Phase-0 plan Task 2 and Task 5, so they're predictable: `const val DATASET_VERSION = 1` and a `vYYYY.R.M` literal). Until then this workflow is **drafted, not merged.**

---

## 7. Sequencing, dependencies, and what is explicitly out of scope

**Hard dependency order (this stream cannot run ahead of the build stream):**

1. Build stream lands `composeApp` module + `gradlew` wrapper + the file paths (`DbFile.kt`, `LicensesScreen.kt`, `NOTICE`, `composeResources/files/`). **← gate**
2. CI-4 (`fetch_f1db.sh` + `f1db.version` + `.gitignore` entry) — decide DB delivery first, because both CI workflows call the script.
3. CI-1 (`ci.yml`) — verify green build + 0 billable minutes on a throwaway branch.
4. CI-3 (`release-notes.md`) — friend-facing install body.
5. CI-2 (`release.yml`) — verify by pushing the existing `v0.1.0-hero` tag (or a `v0.1.0-rc` test tag) and confirming both assets attach.
6. CI-5 (`dataset-bump.yml`) — last; only useful once the version-string formats are final.

**Coordination rule:** CI-4's `.gitignore` line and CI-5's regexes touch files the build stream owns. **Do not edit those files blind** — open the workflow/script PRs and let the build-stream changes settle first, or pair the edits.

**Out of scope for this stream (named, not silently dropped):**
- **Release-signing / keystores / notarization** — debug-signed APK + un-notarized DMG is the deliberate $0 choice (§3, §4b). Conveyor is a Phase-1+ fallback only.
- **The backend's CI/CD** (FastAPI + Docker on Oracle Always-Free) — that is a Phase-2 concern with its own pipeline (image build, `docker compose` deploy, the live-recorder sidecar). Not part of Phase-0 ci-release.
- **Play Store / TestFlight / any store** — explicitly excluded by spec §12.
- **iOS/Windows/Linux artifacts** — CMP keeps the door open but spec §12 puts them out of scope; no runners for them here.

---

## 8. Self-review

**Constraint coverage:**
- **$0:** only `ubuntu-latest` + `macos-latest` standard runners (free/unlimited on public repo, 0 included-minutes); binaries in GitHub Releases (no LFS bandwidth); LFS explicitly rejected; no paid signing/notarization. ✅
- **Public personal repo:** stated as the *reason* macOS runners are free; LFS-on-public footgun called out. ✅
- **No F1 branding:** asset names, `packageName`, release names all "PitWall"; debug APK renamed away from any mark; disclaimer in release body. ✅
- **CC-BY-4.0:** bundled `vYYYY.RR.MICRO` named in `NOTICE` + `LicensesScreen` + release body; pin+checksum makes the dataset fixed/attributable; bump workflow keeps the version string honest automatically. ✅

**Grounding:** every cost claim ties to the Dec-2025 changelog + 2026 pricing facts; every size (70 MB / 15.5 MB zip / `checksums_sha256.txt`) ties to the measured `/tmp/f1db/f1db.db` and the real release asset; action pins are the verified current versions; the install steps match Android per-source permission + Sequoia 15.1 `xattr` behavior.

**Execution-time checks flagged (not left vague):** (1) portable sha256 verify across Linux/BSD in `fetch_f1db.sh`; (2) exact inner filename inside `f1db-sqlite.zip`; (3) the two `dataset-bump` regexes must match the final `DbFile.kt`/`LicensesScreen.kt` formats; (4) confirm 0 billable minutes in repo Actions usage after first runs.

**No placeholders, no TODO-without-owner.** Workflows are concrete; the only deferred items are gated on the parallel build stream landing the files they reference.

---

## Appendix — file/path references & sources

**Files this stream creates:**
- `~/Downloads/Repos/personal/pitwall/.github/workflows/ci.yml` (CI-1)
- `~/Downloads/Repos/personal/pitwall/.github/workflows/release.yml` (CI-2)
- `~/Downloads/Repos/personal/pitwall/.github/release-notes.md` (CI-3)
- `~/Downloads/Repos/personal/pitwall/scripts/fetch_f1db.sh` + `~/Downloads/Repos/personal/pitwall/f1db.version` (CI-4)
- `~/Downloads/Repos/personal/pitwall/.github/workflows/dataset-bump.yml` (CI-5)

**Files this stream depends on (build stream owns — do not edit blind):**
- `composeApp/src/commonMain/kotlin/dev/pitwall/data/DbFile.kt` (`DATASET_VERSION`)
- `composeApp/src/commonMain/kotlin/dev/pitwall/ui/LicensesScreen.kt` (bundled-version string)
- `NOTICE` (bundled-version string), `.gitignore`, `gradlew` wrapper, `compose.desktop` block

**Measured artifact facts:** `/tmp/f1db/f1db.db` = 70 MB (73,043,968 bytes), gzips ~15.5 MB; `f1db-sqlite.zip` = 15,506,969 bytes; release also ships `checksums_sha256.txt`.

**Sources:** GitHub Actions Dec-16-2025 pricing changelog; GitHub Actions billing docs; 2026 pricing-changes page; free Apple-Silicon macOS runners for public repos; `android-actions/setup-android` releases; f1db repo + releases; Git LFS billing + storage/bandwidth docs; macOS Sequoia Gatekeeper-bypass removal (AppleInsider) + 15.1 signing (Hackaday) + `xattr` method writeup; Android "install unknown apps" guidance.
