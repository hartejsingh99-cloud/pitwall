# PitWall — Phase 0 + Driver-vs-Car Hero — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a working Compose Multiplatform app (Android + macOS desktop) that bundles the f1db SQLite, reads it offline via SQLDelight, computes the **Driver-vs-Car teammate-qualifying rating** in shared Kotlin, and shows a ranked screen — all $0, no backend.

**Architecture:** One Kotlin Multiplatform `composeApp` module. `commonMain` holds the bundled DB read path (SQLDelight), the pure-Kotlin stats engine, a repository, a ViewModel, and Compose UI. `androidMain`/`desktopMain` hold only entry points + the platform SQLite driver + a file-copy `expect/actual`. No networking, no FastF1, no live data in this plan (those are later phases).

**Tech Stack:** Kotlin 2.2.20+ · Compose Multiplatform (latest stable 1.10.x/1.11.x) · SQLDelight 2.3.2 · Koin 4.2.1 · androidx.lifecycle ViewModel (CMP) 2.10.0 · kotlinx-coroutines. JDK 17. minSdk 24.

> **Version note (research 2026-06-14):** library versions churn fast. Before/while executing, verify each coordinate in `gradle/libs.versions.toml` against Maven Central and use the latest stable in its line. The exact f1db schema below was dumped from release `v2026.6.x` — re-dump with `sqlite3 f1db.db '.schema'` if a much newer release is bundled.

> **Reference f1db schema (verified):**
> - `driver(id TEXT pk, name, first_name, last_name, full_name, abbreviation, permanent_number, nationality_country_id, ...)`
> - `constructor(id TEXT pk, name, full_name, country_id, ...)`
> - `race(id INT pk, year INT, round INT, date, grand_prix_id, official_name, qualifying_format, circuit_id, ...)`
> - `qualifying_result` **VIEW**: `(race_id INT, position_number, driver_id, constructor_id, time_millis INT, q1_millis INT, q2_millis INT, q3_millis INT, gap_millis, laps)`
> - `race_result` **VIEW**: `(race_id INT, position_number, driver_id, constructor_id, reason_retired TEXT, points, grid_position_number, ...)`

---

## File structure

```
~/Downloads/Repos/personal/pitwall/
├── LICENSE                      # MIT (own code)
├── NOTICE                       # f1db CC-BY-4.0 attribution
├── README.md                    # what it is + unofficial-fan disclaimer
├── .gitignore                   # gradle/idea/build + .superpowers/
├── docs/superpowers/{specs,plans}/…   # spec + this plan, moved in (Task 1)
├── settings.gradle.kts
├── build.gradle.kts             # root
├── gradle/libs.versions.toml    # version catalog
└── composeApp/
    ├── build.gradle.kts
    └── src/
        ├── commonMain/
        │   ├── kotlin/dev/pitwall/
        │   │   ├── App.kt                       # root @Composable
        │   │   ├── data/DbFile.kt               # expect: copy bundled db → writable path
        │   │   ├── data/F1dbDriverFactory.kt    # expect: SqlDriver over the copied file (read-only)
        │   │   ├── data/F1Repository.kt         # SQLDelight → domain
        │   │   ├── domain/Models.kt             # QualiLap, TeammatePairing, DriverCarRating…
        │   │   ├── domain/DriverVsCar.kt        # PURE stats engine (the crown jewel)
        │   │   ├── ui/DriverVsCarViewModel.kt
        │   │   ├── ui/DriverVsCarScreen.kt
        │   │   ├── ui/LicensesScreen.kt
        │   │   └── di/Modules.kt                # Koin
        │   ├── sqldelight/dev/pitwall/db/F1db.sq # CREATE (for codegen) + labelled SELECTs
        │   └── composeResources/files/f1db.db   # bundled dataset (Task 2)
        ├── androidMain/kotlin/dev/pitwall/
        │   ├── MainActivity.kt
        │   ├── data/DbFile.android.kt
        │   └── data/F1dbDriverFactory.android.kt
        └── desktopMain/kotlin/dev/pitwall/
            ├── main.kt
            ├── data/DbFile.desktop.kt
            └── data/F1dbDriverFactory.desktop.kt
```

---

## Task 1: Repo + KMP project skeleton + license/disclaimer

**Files:**
- Create: repo at `~/Downloads/Repos/personal/pitwall/` with `settings.gradle.kts`, root `build.gradle.kts`, `gradle/libs.versions.toml`, `composeApp/build.gradle.kts`, `LICENSE`, `NOTICE`, `README.md`, `.gitignore`
- Move: `~/Downloads/Repos/docs/superpowers/` → `~/Downloads/Repos/personal/pitwall/docs/superpowers/`

- [ ] **Step 1: Generate the project from the KMP wizard template**

Use the JetBrains KMP wizard output as the base (Android + Desktop targets, Compose UI, no iOS/web). Either download from `https://kmp.jetbrains.com/` (select Android + Desktop) into `~/Downloads/Repos/personal/pitwall/`, or hand-create the files in the following steps. The wizard names the module `composeApp` and uses `jvm("desktop")` → `desktopMain`.

- [ ] **Step 2: Write `gradle/libs.versions.toml`**

```toml
[versions]
kotlin = "2.2.20"
agp = "8.7.3"
composeMultiplatform = "1.11.1"
sqldelight = "2.3.2"
koin = "4.2.1"
lifecycle = "2.10.0"
coroutines = "1.10.2"
androidMinSdk = "24"
androidCompileSdk = "35"

[libraries]
sqldelight-android = { module = "app.cash.sqldelight:android-driver", version.ref = "sqldelight" }
sqldelight-sqlite = { module = "app.cash.sqldelight:sqlite-driver", version.ref = "sqldelight" }
sqldelight-coroutines = { module = "app.cash.sqldelight:coroutines-extensions", version.ref = "sqldelight" }
koin-core = { module = "io.insert-koin:koin-core", version.ref = "koin" }
koin-compose = { module = "io.insert-koin:koin-compose", version.ref = "koin" }
koin-compose-viewmodel = { module = "io.insert-koin:koin-compose-viewmodel", version.ref = "koin" }
lifecycle-viewmodel-compose = { module = "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
coroutines-swing = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-swing", version.ref = "coroutines" }
kotlin-test = { module = "org.jetbrains.kotlin:kotlin-test", version.ref = "kotlin" }

[plugins]
kotlinMultiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
androidApplication = { id = "com.android.application", version.ref = "agp" }
composeMultiplatform = { id = "org.jetbrains.compose", version.ref = "composeMultiplatform" }
composeCompiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
sqldelight = { id = "app.cash.sqldelight", version.ref = "sqldelight" }
```

- [ ] **Step 3: Write `LICENSE`, `NOTICE`, `README.md`, `.gitignore`**

`LICENSE` = standard MIT (year 2026, author Hartej Singh).
`NOTICE`:
```
PitWall bundles the F1DB dataset (https://github.com/f1db/f1db),
licensed under Creative Commons Attribution 4.0 International (CC BY 4.0):
https://creativecommons.org/licenses/by/4.0/
The dataset has been transformed (packaged into the app and queried). Bundled f1db release: vYYYY.R.M.
PitWall is an unofficial fan project, not associated with Formula 1.
F1, FORMULA 1, FORMULA ONE and related marks are trademarks of Formula One Licensing B.V.
```
`README.md` opens with: "PitWall — an unofficial, non-commercial Formula 1 companion (Android + macOS). Not affiliated with Formula 1; F1 marks owned by Formula One Licensing B.V." plus a one-line build instruction.
`.gitignore`:
```
.gradle/
build/
.idea/
local.properties
*.iml
.kotlin/
.superpowers/
```

- [ ] **Step 4: Move the spec/plan into the repo, then init git with the personal identity**

```bash
mkdir -p ~/Downloads/Repos/personal/pitwall
# (project files created above already live here)
mv ~/Downloads/Repos/docs/superpowers ~/Downloads/Repos/personal/pitwall/docs/
git -C ~/Downloads/Repos/personal/pitwall init
git -C ~/Downloads/Repos/personal/pitwall add -A
git -C ~/Downloads/Repos/personal/pitwall commit -m "chore: scaffold PitWall (KMP composeApp, license, spec+plan)"
```
Expected: commit succeeds. `~/Downloads/Repos/personal/` is covered by the `includeIf` rule, so `git log -1 --format='%an <%ae>'` shows the **personal** identity (`hartejsingh99-cloud`), not the org one. **Verify this before pushing.**

- [ ] **Step 5: Verify the project configures**

Run: `~/Downloads/Repos/personal/pitwall/gradlew -p ~/Downloads/Repos/personal/pitwall tasks`
Expected: Gradle resolves and lists tasks including `:composeApp:assembleDebug` and `:composeApp:run` with no configuration error.

---

## Task 2: SQLDelight + bundled f1db read path (copy-on-first-launch, read-only)

**Files:**
- Create: `composeApp/src/commonMain/sqldelight/dev/pitwall/db/F1db.sq`
- Create: `composeApp/src/commonMain/composeResources/files/f1db.db` (the dataset)
- Create: `composeApp/src/commonMain/kotlin/dev/pitwall/data/DbFile.kt` (expect)
- Create: `composeApp/src/{androidMain,desktopMain}/kotlin/dev/pitwall/data/DbFile.{android,desktop}.kt`
- Create: `composeApp/src/commonMain/kotlin/dev/pitwall/data/F1dbDriverFactory.kt` (expect) + actuals
- Modify: `composeApp/build.gradle.kts` (SQLDelight plugin + drivers + resources)

- [ ] **Step 1: Add the dataset and SQLDelight config to `composeApp/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.sqldelight)
}

sqldelight {
    databases {
        create("F1db") {
            packageName.set("dev.pitwall.db")
            // We open an EXISTING, pre-populated f1db file — do NOT let SQLDelight create/verify-migrate.
            verifyMigrations.set(false)
        }
    }
}

kotlin {
    androidTarget()
    jvm("desktop")
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime); implementation(compose.foundation)
            implementation(compose.material3); implementation(compose.components.resources)
            implementation(libs.koin.core); implementation(libs.koin.compose); implementation(libs.koin.compose.viewmodel)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.sqldelight.coroutines)
        }
        commonTest.dependencies { implementation(libs.kotlin.test) }
        androidMain.dependencies { implementation(libs.sqldelight.android) }
        val desktopMain by getting {
            dependencies { implementation(compose.desktop.currentOs); implementation(libs.sqldelight.sqlite); implementation(libs.coroutines.swing) }
        }
    }
}
```
Copy the unzipped `f1db.db` to `composeApp/src/commonMain/composeResources/files/f1db.db`.

- [ ] **Step 2: Write `F1db.sq` (declare the tables/view for codegen + the queries we need)**

Declares the real f1db schema (so SQLDelight can map result rows) and the labelled SELECTs. Tables already exist in the bundled DB, so **we never call `F1db.Schema.create()`**.

```sql
-- Schema declarations matching the bundled f1db (subset).
CREATE TABLE driver (
  id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, full_name TEXT NOT NULL,
  abbreviation TEXT NOT NULL, permanent_number TEXT, nationality_country_id TEXT NOT NULL
);
CREATE TABLE constructor (
  id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, full_name TEXT NOT NULL
);
CREATE TABLE race (
  id INTEGER NOT NULL PRIMARY KEY, year INTEGER NOT NULL, round INTEGER NOT NULL,
  date TEXT NOT NULL, grand_prix_id TEXT NOT NULL, official_name TEXT NOT NULL,
  qualifying_format TEXT NOT NULL, circuit_id TEXT NOT NULL
);
CREATE VIEW qualifying_result AS SELECT 0 AS race_id, 0 AS position_number, '' AS driver_id,
  '' AS constructor_id, 0 AS time_millis, 0 AS q1_millis, 0 AS q2_millis, 0 AS q3_millis;
CREATE VIEW race_result AS SELECT 0 AS race_id, 0 AS position_number, '' AS driver_id,
  '' AS constructor_id, '' AS reason_retired;

-- Queries
selectSeasons:
SELECT DISTINCT year FROM race ORDER BY year DESC;

-- All qualifying rows for a season, joined to driver/constructor/race, for the teammate engine.
qualifyingForSeason:
SELECT q.race_id, r.round, r.official_name, q.driver_id, d.full_name AS driver_name,
       d.abbreviation, q.constructor_id, c.name AS constructor_name,
       q.time_millis, q.q1_millis, q.q2_millis, q.q3_millis
FROM qualifying_result q
JOIN race r ON r.id = q.race_id
JOIN driver d ON d.id = q.driver_id
JOIN constructor c ON c.id = q.constructor_id
WHERE r.year = :year
ORDER BY r.round, q.constructor_id, q.position_number;

-- Race results for a season (for DNF/car-fault classification companion).
raceResultsForSeason:
SELECT rr.race_id, r.round, rr.driver_id, rr.constructor_id, rr.position_number, rr.reason_retired
FROM race_result rr JOIN race r ON r.id = rr.race_id
WHERE r.year = :year;
```

> Note: the view bodies above are placeholders only so codegen knows column **names/types**; at runtime SQLDelight queries the real f1db views. The column list must match the live view (verified above).

- [ ] **Step 3: Write `DbFile.kt` expect + actuals (copy bundled db to a writable path, once)**

`commonMain/.../data/DbFile.kt`:
```kotlin
package dev.pitwall.data
// Returns absolute path to a writable f1db.db, copying it out of resources on first run
// (or when the bundled dataset version changes). DATASET_VERSION bumps on each re-bundle.
const val DATASET_VERSION = 1
expect suspend fun ensureF1dbFile(): String
```
`androidMain/.../data/DbFile.android.kt`:
```kotlin
package dev.pitwall.data
import android.content.Context
import org.jetbrains.compose.resources.ExperimentalResourceApi
import pitwall.composeapp.generated.resources.Res
import java.io.File

lateinit var appContext: Context // set in MainActivity.onCreate

@OptIn(ExperimentalResourceApi::class)
actual suspend fun ensureF1dbFile(): String {
    val out = appContext.getDatabasePath("f1db.db")
    val stamp = File(out.parentFile, "f1db.version")
    if (!out.exists() || stamp.takeIf { it.exists() }?.readText()?.trim() != "$DATASET_VERSION") {
        out.parentFile?.mkdirs()
        out.writeBytes(Res.readBytes("files/f1db.db"))
        stamp.writeText("$DATASET_VERSION")
    }
    return out.absolutePath
}
```
`desktopMain/.../data/DbFile.desktop.kt`:
```kotlin
package dev.pitwall.data
import org.jetbrains.compose.resources.ExperimentalResourceApi
import pitwall.composeapp.generated.resources.Res
import java.io.File

@OptIn(ExperimentalResourceApi::class)
actual suspend fun ensureF1dbFile(): String {
    val dir = File(System.getProperty("user.home"), "Library/Application Support/PitWall").apply { mkdirs() }
    val out = File(dir, "f1db.db"); val stamp = File(dir, "f1db.version")
    if (!out.exists() || (stamp.takeIf { it.exists() }?.readText()?.trim() != "$DATASET_VERSION")) {
        out.writeBytes(Res.readBytes("files/f1db.db")); stamp.writeText("$DATASET_VERSION")
    }
    return out.absolutePath
}
```

- [ ] **Step 4: Write `F1dbDriverFactory.kt` expect + actuals (read-only driver over the copied file)**

`commonMain`:
```kotlin
package dev.pitwall.data
import app.cash.sqldelight.db.SqlDriver
expect fun makeF1dbDriver(path: String): SqlDriver
```
`androidMain` (read-only open; no Schema.create):
```kotlin
package dev.pitwall.data
import android.database.sqlite.SQLiteDatabase
import app.cash.sqldelight.android.AndroidSqliteDriver
import app.cash.sqldelight.db.SqlDriver
import dev.pitwall.db.F1db
actual fun makeF1dbDriver(path: String): SqlDriver {
    val factory = { name: String? -> SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY) }
    // Open the existing file directly; do not run Schema.create.
    return AndroidSqliteDriver(F1db.Schema, appContext, name = null, factory = SupportReadOnlyFactory(path))
}
```
`desktopMain`:
```kotlin
package dev.pitwall.data
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.db.SqlDriver
actual fun makeF1dbDriver(path: String): SqlDriver =
    JdbcSqliteDriver("jdbc:sqlite:$path?open_mode=1") // open_mode=1 = SQLITE_OPEN_READONLY; no Schema.create()
```
> **Gotcha to resolve at execution:** opening a *pre-populated external* SQLite with SQLDelight's Android driver needs a `SupportSQLiteOpenHelper.Factory` that opens the given path read-only without recreating tables. If the simple factory above is awkward, use the `requery`/`androidx.sqlite` framework helper pointed at the absolute path. The desktop JDBC URL approach is straightforward. Either way: **never call `F1db.Schema.create(driver)`** — the tables already exist.

- [ ] **Step 5: Commit**

```bash
git -C ~/Downloads/Repos/personal/pitwall add -A
git -C ~/Downloads/Repos/personal/pitwall commit -m "feat: bundle f1db + SQLDelight read-only driver and queries"
```

---

## Task 3: Domain models + the Driver-vs-Car stats engine (pure, TDD)

This is the crown jewel — pure functions, no I/O, fully unit-tested in `commonTest`.

**Files:**
- Create: `composeApp/src/commonMain/kotlin/dev/pitwall/domain/Models.kt`
- Create: `composeApp/src/commonMain/kotlin/dev/pitwall/domain/DriverVsCar.kt`
- Test: `composeApp/src/commonTest/kotlin/dev/pitwall/domain/DriverVsCarTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.pitwall.domain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DriverVsCarTest {
    // helper: a qualifying row
    private fun q(race: Int, drv: String, con: String, q1: Long? = null, q2: Long? = null, q3: Long? = null, t: Long? = null) =
        QualiRow(race, drv, con, t, q1, q2, q3)

    @Test fun symmetricGap_isOrderInvariantAndPercent() {
        // 90.000s vs 90.900s -> ~+0.995% for the slower driver
        val g = symmetricGapPct(90_000, 90_900)
        assertEquals(-0.995, g, 0.01)            // driver i is FASTER -> negative
        assertEquals(0.995, symmetricGapPct(90_900, 90_000), 0.01) // reversed flips sign
    }

    @Test fun lastCommonSegment_prefersQ3thenQ2thenQ1() {
        // both have Q2; only i has Q3 -> compare on Q2
        val pair = lastCommonSegment(
            i = q(1, "i", "x", q1 = 80_000, q2 = 79_000, q3 = 78_000),
            j = q(1, "j", "x", q1 = 80_500, q2 = 79_400)
        )
        assertEquals(79_000L to 79_400L, pair)
    }

    @Test fun lastCommonSegment_fallsBackToCombinedTime() {
        val pair = lastCommonSegment(q(1, "i", "x", t = 91_000), q(1, "j", "x", t = 91_500))
        assertEquals(91_000L to 91_500L, pair)
    }

    @Test fun rating_usesMedianAndCountsHeadToHead() {
        // i beats teammate j in races 1,2 by ~0.5%, loses race 3 by 1% -> median favors i, H2H 2-1
        val rows = listOf(
            q(1, "i", "x", t = 90_000), q(1, "j", "x", t = 90_450),
            q(2, "i", "x", t = 88_000), q(2, "j", "x", t = 88_440),
            q(3, "i", "x", t = 92_000), q(3, "j", "x", t = 91_080),
        )
        val ratings = computeDriverVsCar(rows)
        val i = ratings.first { it.driverId == "i" }
        assertEquals(3, i.events)
        assertEquals(2, i.headToHeadWins)            // won 2 of 3
        assertTrue(i.oneLapRatingPct > 0)            // median gap favors i (faster)
    }

    @Test fun ignoresEventsWithNoTeammate() {
        val rows = listOf(q(1, "solo", "z", t = 90_000)) // only one car entry that race
        assertTrue(computeDriverVsCar(rows).all { it.events == 0 })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `~/Downloads/Repos/personal/pitwall/gradlew -p ~/Downloads/Repos/personal/pitwall :composeApp:desktopTest`
Expected: FAIL — `QualiRow`, `symmetricGapPct`, `lastCommonSegment`, `computeDriverVsCar` unresolved.

- [ ] **Step 3: Write `Models.kt`**

```kotlin
package dev.pitwall.domain

data class QualiRow(
    val raceId: Int, val driverId: String, val constructorId: String,
    val timeMillis: Long?, val q1Millis: Long?, val q2Millis: Long?, val q3Millis: Long?,
)

data class DriverCarRating(
    val driverId: String,
    val events: Int,
    val headToHeadWins: Int,
    val oneLapRatingPct: Double,   // median of (-gap%); positive = faster than teammate
)
```

- [ ] **Step 4: Write `DriverVsCar.kt` (minimal implementation to pass)**

```kotlin
package dev.pitwall.domain

/** Symmetric percent gap: 100*(ti-tj)/((ti+tj)/2). Negative when i is faster. */
fun symmetricGapPct(ti: Long, tj: Long): Double =
    100.0 * (ti - tj) / ((ti + tj) / 2.0)

/** Deepest segment Q3>Q2>Q1 where BOTH set a time; else both combined times; null if neither comparable. */
fun lastCommonSegment(i: QualiRow, j: QualiRow): Pair<Long, Long>? {
    listOf(QualiRow::q3Millis, QualiRow::q2Millis, QualiRow::q1Millis).forEach { seg ->
        val a = seg(i); val b = seg(j)
        if (a != null && a > 0 && b != null && b > 0) return a to b
    }
    val a = i.timeMillis; val b = j.timeMillis
    return if (a != null && a > 0 && b != null && b > 0) a to b else null
}

/** Teammate-normalized one-lap rating per driver across a set of qualifying rows. */
fun computeDriverVsCar(rows: List<QualiRow>): List<DriverCarRating> {
    val gaps = HashMap<String, MutableList<Double>>()   // driverId -> list of (-gap%) vs teammate
    val wins = HashMap<String, Int>()
    val seen = HashSet<String>()
    rows.forEach { seen += it.driverId }

    rows.groupBy { it.raceId to it.constructorId }.forEach { (_, carRows) ->
        // pair every driver with every same-car teammate that race (usually exactly one)
        for (a in carRows) for (b in carRows) {
            if (a.driverId == b.driverId) continue
            val seg = lastCommonSegment(a, b) ?: continue
            val gap = symmetricGapPct(seg.first, seg.second)   // <0 means a faster
            gaps.getOrPut(a.driverId) { mutableListOf() }.add(-gap) // store so higher = faster
            if (gap < 0) wins[a.driverId] = (wins[a.driverId] ?: 0) + 1
        }
    }
    return seen.map { id ->
        val g = gaps[id].orEmpty()
        DriverCarRating(
            driverId = id,
            events = g.size,
            headToHeadWins = wins[id] ?: 0,
            oneLapRatingPct = median(g),
        )
    }
}

private fun median(xs: List<Double>): Double {
    if (xs.isEmpty()) return 0.0
    val s = xs.sorted(); val m = s.size / 2
    return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `~/Downloads/Repos/personal/pitwall/gradlew -p ~/Downloads/Repos/personal/pitwall :composeApp:desktopTest`
Expected: PASS (all 5 tests).

- [ ] **Step 6: Commit**

```bash
git -C ~/Downloads/Repos/personal/pitwall add -A
git -C ~/Downloads/Repos/personal/pitwall commit -m "feat: pure Driver-vs-Car teammate-qualifying engine (TDD)"
```

---

## Task 4: Repository (SQLDelight → domain) + Koin DI

**Files:**
- Create: `composeApp/src/commonMain/kotlin/dev/pitwall/data/F1Repository.kt`
- Create: `composeApp/src/commonMain/kotlin/dev/pitwall/di/Modules.kt`
- Test: `composeApp/src/commonTest/kotlin/dev/pitwall/data/MappingTest.kt`

- [ ] **Step 1: Write the failing mapping test (pure mapping, no DB)**

```kotlin
package dev.pitwall.data
import dev.pitwall.db.QualifyingForSeason
import dev.pitwall.domain.QualiRow
import kotlin.test.Test
import kotlin.test.assertEquals

class MappingTest {
    @Test fun mapsGeneratedRowToDomain() {
        val row = QualifyingForSeason(
            race_id = 5, round = 1, official_name = "GP", driver_id = "ver", driver_name = "Max Verstappen",
            abbreviation = "VER", constructor_id = "rb", constructor_name = "Red Bull",
            time_millis = 90_000, q1_millis = 92_000, q2_millis = 91_000, q3_millis = 90_000
        )
        assertEquals(QualiRow(5, "ver", "rb", 90_000, 92_000, 91_000, 90_000), row.toDomain())
    }
}
```
(`QualifyingForSeason` is the data class SQLDelight generates from the `qualifyingForSeason:` query.)

- [ ] **Step 2: Run to verify it fails**

Run: `~/Downloads/Repos/personal/pitwall/gradlew -p ~/Downloads/Repos/personal/pitwall :composeApp:desktopTest`
Expected: FAIL — `toDomain()` unresolved.

- [ ] **Step 3: Write `F1Repository.kt`**

```kotlin
package dev.pitwall.data
import dev.pitwall.db.F1db
import dev.pitwall.db.QualifyingForSeason
import dev.pitwall.domain.DriverCarRating
import dev.pitwall.domain.QualiRow
import dev.pitwall.domain.computeDriverVsCar

fun QualifyingForSeason.toDomain() =
    QualiRow(race_id.toInt(), driver_id, constructor_id, time_millis, q1_millis, q2_millis, q3_millis)

class F1Repository(private val db: F1db) {
    fun seasons(): List<Int> = db.f1dbQueries.selectSeasons().executeAsList().map { it.toInt() }

    fun driverNames(): Map<String, String> =
        db.f1dbQueries // simple cache of id->full_name via the qualifying rows we load
            .let { emptyMap() } // names come with the query rows; see ratingsForSeason

    fun ratingsForSeason(year: Long): List<Pair<DriverCarRating, String>> {
        val rows = db.f1dbQueries.qualifyingForSeason(year).executeAsList()
        val names = rows.associate { it.driver_id to "${it.driver_name} (${it.abbreviation})" }
        val ratings = computeDriverVsCar(rows.map { it.toDomain() })
        return ratings.filter { it.events > 0 }
            .sortedByDescending { it.oneLapRatingPct }
            .map { it to (names[it.driverId] ?: it.driverId) }
    }
}
```

- [ ] **Step 4: Write `di/Modules.kt`**

```kotlin
package dev.pitwall.di
import app.cash.sqldelight.db.SqlDriver
import dev.pitwall.data.F1Repository
import dev.pitwall.data.ensureF1dbFile
import dev.pitwall.data.makeF1dbDriver
import dev.pitwall.db.F1db
import dev.pitwall.ui.DriverVsCarViewModel
import org.koin.core.module.Module
import org.koin.dsl.module

// driverPath is resolved at startup (suspend) and passed in.
fun appModule(driverPath: String): Module = module {
    single<SqlDriver> { makeF1dbDriver(driverPath) }
    single { F1db(get()) }                 // do NOT call F1db.Schema.create — DB is pre-populated
    single { F1Repository(get()) }
    factory { DriverVsCarViewModel(get()) }
}
```

- [ ] **Step 5: Run tests to verify pass**

Run: `~/Downloads/Repos/personal/pitwall/gradlew -p ~/Downloads/Repos/personal/pitwall :composeApp:desktopTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git -C ~/Downloads/Repos/personal/pitwall add -A
git -C ~/Downloads/Repos/personal/pitwall commit -m "feat: repository mapping f1db -> Driver-vs-Car ratings + Koin module"
```

---

## Task 5: ViewModel + Compose UI (the screen)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/dev/pitwall/ui/DriverVsCarViewModel.kt`
- Create: `composeApp/src/commonMain/kotlin/dev/pitwall/ui/DriverVsCarScreen.kt`
- Create: `composeApp/src/commonMain/kotlin/dev/pitwall/ui/LicensesScreen.kt`
- Create: `composeApp/src/commonMain/kotlin/dev/pitwall/App.kt`

- [ ] **Step 1: Write `DriverVsCarViewModel.kt`**

```kotlin
package dev.pitwall.ui
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pitwall.data.F1Repository
import dev.pitwall.domain.DriverCarRating
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val seasons: List<Int> = emptyList(),
    val selectedYear: Int? = null,
    val rows: List<Pair<DriverCarRating, String>> = emptyList(),
    val loading: Boolean = true,
)

class DriverVsCarViewModel(private val repo: F1Repository) : ViewModel() {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init { load(null) }

    fun load(year: Int?) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val (seasons, chosen, rows) = withContext(Dispatchers.Default) {
                val s = repo.seasons()
                val y = year ?: s.first()
                Triple(s, y, repo.ratingsForSeason(y.toLong()))
            }
            _state.value = UiState(seasons, chosen, rows, loading = false)
        }
    }
}
```

- [ ] **Step 2: Write `DriverVsCarScreen.kt`**

```kotlin
package dev.pitwall.ui
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun DriverVsCarScreen(vm: DriverVsCarViewModel = koinViewModel()) {
    val s by vm.state.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Driver vs Car — qualifying skill", style = MaterialTheme.typography.headlineSmall)
        Text("Teammate-normalized one-lap rating · median symmetric gap · same car only",
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        if (s.seasons.isNotEmpty()) {
            // simple year stepper
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                s.selectedYear?.let { y ->
                    Text("Season: $y", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(12.dp))
                    val idx = s.seasons.indexOf(y)
                    Button(enabled = idx < s.seasons.lastIndex, onClick = { vm.load(s.seasons[idx + 1]) }) { Text("◀ older") }
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = idx > 0, onClick = { vm.load(s.seasons[idx - 1]) }) { Text("newer ▶") }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (s.loading) { CircularProgressIndicator() }
        else LazyColumn(Modifier.fillMaxSize()) {
            items(s.rows) { (r, name) ->
                ListItem(
                    headlineContent = { Text(name, fontWeight = FontWeight.SemiBold) },
                    supportingContent = {
                        val sign = if (r.oneLapRatingPct >= 0) "+" else ""
                        Text("$sign${"%.3f".format(r.oneLapRatingPct)}% vs teammate · H2H ${r.headToHeadWins}/${r.events}")
                    },
                )
                HorizontalDivider()
            }
        }
    }
}
```

- [ ] **Step 3: Write `LicensesScreen.kt` (CC-BY-4.0 attribution)**

```kotlin
package dev.pitwall.ui
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun LicensesScreen() = Column(Modifier.padding(16.dp)) {
    Text("Open-source data", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(8.dp))
    Text("Historical data: F1DB (github.com/f1db/f1db), licensed CC BY 4.0 " +
        "(creativecommons.org/licenses/by/4.0/). Bundled and transformed for offline use.")
    Spacer(Modifier.height(12.dp))
    Text("PitWall is an unofficial fan project, not associated with Formula 1. " +
        "F1, FORMULA 1, FORMULA ONE and related marks are trademarks of Formula One Licensing B.V.",
        style = MaterialTheme.typography.bodySmall)
}
```

- [ ] **Step 4: Write `App.kt` (root composable with simple two-tab nav)**

```kotlin
package dev.pitwall
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import dev.pitwall.ui.DriverVsCarScreen
import dev.pitwall.ui.LicensesScreen

@Composable
fun App() = MaterialTheme {
    var tab by remember { mutableStateOf(0) }
    Scaffold(bottomBar = {
        NavigationBar {
            NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                icon = {}, label = { Text("Driver vs Car") })
            NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                icon = {}, label = { Text("About / Data") })
        }
    }) { pad ->
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(
            top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding())) {
            if (tab == 0) DriverVsCarScreen() else LicensesScreen()
        }
    }
}
```

- [ ] **Step 5: Commit**

```bash
git -C ~/Downloads/Repos/personal/pitwall add -A
git -C ~/Downloads/Repos/personal/pitwall commit -m "feat: Driver-vs-Car screen, About/licenses screen, app shell"
```

---

## Task 6: Android entry point + run

**Files:**
- Create: `composeApp/src/androidMain/kotlin/dev/pitwall/MainActivity.kt`
- Create/verify: `composeApp/src/androidMain/AndroidManifest.xml`
- Modify: `composeApp/build.gradle.kts` (android block, applicationId `dev.pitwall`)

- [ ] **Step 1: Write `MainActivity.kt`**

```kotlin
package dev.pitwall
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import dev.pitwall.data.appContext
import dev.pitwall.data.ensureF1dbFile
import dev.pitwall.di.appModule
import kotlinx.coroutines.launch
import org.koin.core.context.startKoin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appContext = applicationContext
        lifecycleScope.launch {
            val path = ensureF1dbFile()
            startKoin { modules(appModule(path)) }
            setContent { App() }
        }
    }
}
```

- [ ] **Step 2: Ensure android block in `composeApp/build.gradle.kts`**

```kotlin
android {
    namespace = "dev.pitwall"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig {
        applicationId = "dev.pitwall"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidCompileSdk.get().toInt()
        versionCode = 1; versionName = "0.1.0"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
```
`AndroidManifest.xml` declares `MainActivity` as launcher. (No INTERNET permission needed — fully offline.)

- [ ] **Step 3: Build the debug APK**

Run: `~/Downloads/Repos/personal/pitwall/gradlew -p ~/Downloads/Repos/personal/pitwall :composeApp:assembleDebug`
Expected: `BUILD SUCCESSFUL`; APK at `composeApp/build/outputs/apk/debug/composeApp-debug.apk`.

- [ ] **Step 4: Run on an emulator/device and eyeball**

Run: `~/Downloads/Repos/personal/pitwall/gradlew -p ~/Downloads/Repos/personal/pitwall :composeApp:installDebug` (with an emulator running), launch the app.
Expected: the Driver-vs-Car list renders for the latest season (drivers ranked by teammate gap, H2H counts), the year stepper changes seasons, and the About tab shows the f1db CC-BY credit.

- [ ] **Step 5: Commit**

```bash
git -C ~/Downloads/Repos/personal/pitwall add -A
git -C ~/Downloads/Repos/personal/pitwall commit -m "feat: Android entry point; offline app runs end-to-end"
```

---

## Task 7: macOS desktop entry point + run + .dmg packaging

**Files:**
- Create: `composeApp/src/desktopMain/kotlin/dev/pitwall/main.kt`
- Modify: `composeApp/build.gradle.kts` (`compose.desktop` block)

- [ ] **Step 1: Write `main.kt`**

```kotlin
package dev.pitwall
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.pitwall.data.ensureF1dbFile
import dev.pitwall.di.appModule
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin

fun main() {
    val path = runBlocking { ensureF1dbFile() }
    startKoin { modules(appModule(path)) }
    application { Window(onCloseRequest = ::exitApplication, title = "PitWall") { App() } }
}
```

- [ ] **Step 2: Add the `compose.desktop` packaging block to `composeApp/build.gradle.kts`**

```kotlin
compose.desktop {
    application {
        mainClass = "dev.pitwall.MainKt"
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg)
            packageName = "PitWall"
            packageVersion = "0.1.0"
            macOS { bundleID = "dev.pitwall" }
        }
    }
}
```

- [ ] **Step 3: Run the desktop app**

Run: `~/Downloads/Repos/personal/pitwall/gradlew -p ~/Downloads/Repos/personal/pitwall :composeApp:run`
Expected: a macOS window titled "PitWall" opens showing the same Driver-vs-Car screen + About tab, reading from `~/Library/Application Support/PitWall/f1db.db`.

- [ ] **Step 4: Build the .dmg (must be on a Mac; JDK 17+)**

Run: `~/Downloads/Repos/personal/pitwall/gradlew -p ~/Downloads/Repos/personal/pitwall :composeApp:packageDmg`
Expected: `BUILD SUCCESSFUL`; `.dmg` under `composeApp/build/compose/binaries/main/dmg/`. (Un-notarized — friends run `xattr -dr com.apple.quarantine /Applications/PitWall.app` once after install. Notarization/Conveyor is a later concern.)

- [ ] **Step 5: Commit + tag**

```bash
git -C ~/Downloads/Repos/personal/pitwall add -A
git -C ~/Downloads/Repos/personal/pitwall commit -m "feat: macOS desktop entry point + .dmg packaging; Phase 1 hero shippable"
git -C ~/Downloads/Repos/personal/pitwall tag v0.1.0-hero
```

---

## Self-review

**Spec coverage (Phase 0 + hero slice):**
- Repo on personal GitHub, public OSS, MIT + f1db CC-BY attribution + disclaimer → Task 1, Task 5 (LicensesScreen), NOTICE/README.
- $0 / offline / no backend → whole plan is client-only; no Ktor/FastF1/live.
- Compose Multiplatform, Android + macOS, shared core → Tasks 5–7; ~all logic in `commonMain`.
- SQLDelight over a bundled read-only f1db, copy-on-first-launch, version-gated, never `Schema.create()` → Task 2.
- Driver-vs-Car methodology: symmetric % gap, last-common-segment (Q3→Q2→Q1) + fallback, median, head-to-head count → Task 3 (TDD), real f1db columns.
- DNF/car-fault data path available (`race_result.reason_retired`, `raceResultsForSeason` query) → declared in Task 2; the car-adjusted Elo + DNF-exclusion + race-pace companion are **explicitly deferred** to Plan 2 (they need either more UI or the FastF1 backend). *This plan ships the qualifying one-lap rating + H2H, which is the offline, all-eras core.*
- Pre-2018 graceful behavior → the engine uses qualifying times only (all eras); no telemetry assumed.

**Deferred to later plans (named, not silently dropped):** car-adjusted win-probability (Elo/Bayesian), DNF car-fault/driver-fault classification UI, race-pace companion (FastF1 — Phase 2), the other Phase 1 features (career H2H, title permutations, records, results/standings browse), jolpica freshness, charts (Vico).

**Placeholder scan:** no "TBD/handle errors/etc." Two flagged execution-time verifications (exact Android read-only `SupportSQLiteOpenHelper.Factory`; live library versions) are called out explicitly with the concrete fallback, not left vague.

**Type consistency:** `QualiRow`, `DriverCarRating`, `symmetricGapPct`, `lastCommonSegment`, `computeDriverVsCar`, `F1Repository.ratingsForSeason(Long)`, `UiState`, `DriverVsCarViewModel`, `appModule(String)` are used consistently across Tasks 3–7. SQLDelight-generated `QualifyingForSeason`/`F1db.f1dbQueries` names follow the `F1db.sq` query labels.
