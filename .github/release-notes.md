PitWall — an unofficial, offline-first motorsport companion for Android and macOS.
No account, no network: all data is bundled.

## Install

### Android (sideload the APK)
1. Download `PitWall-<version>.apk` to your phone.
2. Tap it. Android will ask to allow installs from the app you downloaded with:
   **Settings → Apps → Special app access → Install unknown apps → [your browser/Files] → Allow from this source.**
3. Back out, tap the APK again, **Install**.

PitWall declares **no network permission** — everything is bundled and offline.

### macOS (unsigned `.dmg`)
The app is not signed with an Apple Developer certificate (this is a free, friends-only project), so
Gatekeeper will block it until you clear the download quarantine:
1. Open `PitWall-<version>.dmg` and drag **PitWall** into **Applications**.
2. Run this once in Terminal:
   ```bash
   xattr -dr com.apple.quarantine /Applications/PitWall.app
   ```
3. Launch PitWall normally.

## Data & licensing
- Historical data: **f1db** (github.com/f1db/f1db), **CC BY 4.0**. Bundled f1db release is named in the
  app's About screen and in `NOTICE`.
- Car data (telemetry): pre-computed offline with **FastF1** (MIT). This build bundles **real 2026
  car data — rounds 1–7** (Race + Qualifying, plus the China/Miami/Canada sprints; Barcelona is
  Qualifying-only as the race had not been published when this was baked).

PitWall is a fan project and is **not affiliated with, endorsed by, or associated with Formula 1**.
F1, FORMULA 1, FORMULA ONE and related marks are trademarks of Formula One Licensing B.V.
