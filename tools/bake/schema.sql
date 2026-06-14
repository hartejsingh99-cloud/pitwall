-- telemetry.db schema — the contract shared with the Kotlin side (Telemetry.sq must match 1:1).
-- The app opens this file read-only via SQLDelight and never runs Schema.create (pre-populated),
-- exactly like f1db.db. IF NOT EXISTS lets the bake append sessions across multiple invocations.

CREATE TABLE IF NOT EXISTS session (
  id            TEXT PRIMARY KEY,   -- "2024-1-R" (year-round-sessionType)
  year          INTEGER NOT NULL,
  round         INTEGER NOT NULL,
  session_type  TEXT NOT NULL,      -- 'R','Q','S','SQ','FP1'...
  event_name    TEXT NOT NULL,      -- neutral event name, e.g. "Bahrain"
  circuit_name  TEXT NOT NULL,
  baked_at      TEXT NOT NULL       -- ISO date the bake ran
);

CREATE TABLE IF NOT EXISTS lap (
  session_id    TEXT NOT NULL,
  driver_id     TEXT NOT NULL,      -- f1db driver slug where resolvable, else FastF1 abbreviation
  driver_label  TEXT NOT NULL,      -- display "Max Verstappen (VER)"
  lap_number    INTEGER NOT NULL,
  lap_time_ms   INTEGER,            -- nullable (in/out/no-time laps)
  compound      TEXT,               -- SOFT/MEDIUM/HARD/INTERMEDIATE/WET
  tyre_life     INTEGER,
  stint         INTEGER,
  is_accurate   INTEGER NOT NULL DEFAULT 0,  -- 0/1
  PRIMARY KEY (session_id, driver_id, lap_number)
);

CREATE TABLE IF NOT EXISTS telemetry_channel (   -- one row per driver-lap; channels are comma-delimited strings
  session_id    TEXT NOT NULL,
  driver_id     TEXT NOT NULL,
  lap_number    INTEGER NOT NULL,
  distance      TEXT NOT NULL,      -- "0,25,50,..." decimated by distance
  speed         TEXT,
  throttle      TEXT,
  brake         TEXT,
  gear          TEXT,
  drs           TEXT,
  x             TEXT,
  y             TEXT,
  PRIMARY KEY (session_id, driver_id, lap_number)
);

CREATE TABLE IF NOT EXISTS driver_pace (         -- race-pace companion + tyre-deg fit per driver-session
  session_id        TEXT NOT NULL,
  driver_id         TEXT NOT NULL,
  driver_label      TEXT NOT NULL,
  median_pace_ms    INTEGER,        -- median accurate green-flag lap
  pace_pct_vs_team  REAL,           -- symmetric % vs teammate (negative = faster); NULL if no teammate
  deg_ms_per_lap    REAL,           -- fitted linear tyre-deg slope (ms/lap), fuel-corrected
  PRIMARY KEY (session_id, driver_id)
);
