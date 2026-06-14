# PitWall

An unofficial, non-commercial, **offline-first** motorsport companion for Android and macOS,
built with Kotlin / Compose Multiplatform. Everything runs against a bundled, read-only copy of the
open [f1db](https://github.com/f1db/f1db) dataset — no backend, no account, no network on any screen.

> PitWall is a fan project and is **not affiliated with, endorsed by, or associated with Formula 1**.
> F1, FORMULA 1, FORMULA ONE and related marks are trademarks of Formula One Licensing B.V.

## Features

- **Driver vs Car** — a teammate-normalized one-lap qualifying rating: the median symmetric gap to a
  driver's teammate on their deepest *shared* qualifying segment, same car only.
- **Browse** — seasons → races → results (Qualifying / Sprint / Race tabs) and championship standings,
  aware of the in-progress season.
- **Head-to-Head** — career and teammate-era comparison between any two drivers (reuses the gap engine).
- **Title Race** — who's mathematically still alive, max reachable points, and clinch scenarios (2010+).
- **Records & On-This-Day** — all-time leaderboards with era filters, and what happened on today's date.

## Building

Both targets need a copy of the f1db SQLite database, which is **not committed** (it's ~70 MB).
Download a release from [f1db/f1db](https://github.com/f1db/f1db/releases) (the SQLite split build)
and place it at:

```
composeApp/src/commonMain/composeResources/files/f1db.db
```

Then:

```bash
./gradlew :composeApp:assembleDebug      # Android APK  -> composeApp/build/outputs/apk/debug/
./gradlew :composeApp:run                # run the macOS desktop app
./gradlew :composeApp:packageDmg         # build a macOS .dmg
./gradlew :composeApp:desktopTest        # run the test suite
```

Requires JDK 17. The desktop distribution bundles the `java.sql` module so the SQLite JDBC driver
works inside the `jlink`-minimized runtime.

## Data & licensing

- Application code: **MIT** (see [LICENSE](LICENSE)).
- Historical data: **f1db**, licensed **CC BY 4.0** — see [NOTICE](NOTICE) for attribution. The data is
  bundled and transformed (packaged and queried) for offline use.
