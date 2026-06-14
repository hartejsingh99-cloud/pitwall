import os
import sqlite3
import tempfile
import unittest

from pitwall_bake.write_db import write_telemetry_db


def _payload():
    return {
        "session": {
            "id": "2024-1-R", "year": 2024, "round": 1, "session_type": "R",
            "event_name": "Bahrain", "circuit_name": "Bahrain International Circuit",
            "baked_at": "2026-06-14",
        },
        "laps": [
            {"driver_id": "max-verstappen", "driver_label": "Max Verstappen (VER)", "lap_number": 1,
             "lap_time_ms": 95000, "compound": "SOFT", "tyre_life": 1, "stint": 1, "is_accurate": True},
            {"driver_id": "max-verstappen", "driver_label": "Max Verstappen (VER)", "lap_number": 2,
             "lap_time_ms": 95200, "compound": "SOFT", "tyre_life": 2, "stint": 1, "is_accurate": True},
        ],
        "channels": [
            {"driver_id": "max-verstappen", "lap_number": 1, "distance": "0,25,50",
             "speed": "280,120,300", "throttle": "100,0,100", "brake": "0,1,0",
             "gear": "7,3,8", "drs": "0,0,1", "x": "0,10,20", "y": "0,5,0"},
        ],
        "pace": [
            {"driver_id": "max-verstappen", "driver_label": "Max Verstappen (VER)",
             "median_pace_ms": 95100, "pace_pct_vs_team": -0.5, "deg_ms_per_lap": 200.0},
        ],
    }


class WriteDbTest(unittest.TestCase):
    def test_writes_and_roundtrips(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "telemetry.db")
            write_telemetry_db(path, _payload())
            conn = sqlite3.connect(path)
            try:
                self.assertEqual(conn.execute("SELECT COUNT(*) FROM session").fetchone()[0], 1)
                self.assertEqual(conn.execute("SELECT COUNT(*) FROM lap").fetchone()[0], 2)
                self.assertEqual(conn.execute("SELECT COUNT(*) FROM telemetry_channel").fetchone()[0], 1)
                self.assertEqual(conn.execute("SELECT COUNT(*) FROM driver_pace").fetchone()[0], 1)
                dist = conn.execute("SELECT distance FROM telemetry_channel").fetchone()[0]
                self.assertEqual([float(x) for x in dist.split(",")], [0.0, 25.0, 50.0])
            finally:
                conn.close()

    def test_rebake_is_idempotent(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "telemetry.db")
            write_telemetry_db(path, _payload())
            write_telemetry_db(path, _payload())  # same session again
            conn = sqlite3.connect(path)
            try:
                self.assertEqual(conn.execute("SELECT COUNT(*) FROM session").fetchone()[0], 1)
                self.assertEqual(conn.execute("SELECT COUNT(*) FROM lap").fetchone()[0], 2)
            finally:
                conn.close()


if __name__ == "__main__":
    unittest.main()
