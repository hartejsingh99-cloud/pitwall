"""PitWall telemetry bake — local FastF1 → telemetry.db (offline, $0, no server).

The pure transforms (transform.py) take plain Python lists and are unit-tested with
stdlib unittest (no pandas/fastf1 needed). The FastF1 adapter (extract.py) is the only
module that imports fastf1/pandas and is run locally to refresh real data.
"""
