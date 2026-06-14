import unittest

from pitwall_bake.transform import (
    decimate_by_distance,
    is_green_lap,
    pack_channels,
    symmetric_pace_pct,
    tyre_deg_slope,
)


class TransformTest(unittest.TestCase):
    def test_decimate_keeps_endpoints_and_spacing(self):
        dist = [i * 1.0 for i in range(0, 101)]  # 0..100 m, 1 m apart
        speed = [100.0 + i for i in range(0, 101)]
        d2, chans = decimate_by_distance(dist, {"speed": speed}, step_m=25.0)
        self.assertEqual(d2[0], 0.0)
        self.assertEqual(d2[-1], 100.0)  # last sample always kept
        self.assertTrue(all(b - a >= 25.0 - 1e-9 for a, b in zip(d2, d2[1:])))
        self.assertEqual(len(d2), len(chans["speed"]))
        # speed values must be the ones at the kept distance indices
        self.assertEqual(chans["speed"][0], 100.0)
        self.assertEqual(chans["speed"][-1], 200.0)

    def test_decimate_short_input_returns_as_is(self):
        d2, chans = decimate_by_distance([0.0, 5.0], {"speed": [10.0, 20.0]}, step_m=25.0)
        self.assertEqual(d2, [0.0, 5.0])
        self.assertEqual(chans["speed"], [10.0, 20.0])

    def test_decimate_drops_none_channels(self):
        d2, chans = decimate_by_distance([0.0, 50.0, 100.0], {"speed": None}, step_m=25.0)
        self.assertIsNone(chans["speed"])

    def test_pack_channels_csv_roundtrip(self):
        # integer-valued floats pack compactly (Kotlin parseChannel reads all channels as Double,
        # so "0" and "0.0" are equivalent on the wire — compact wins for bundle size).
        self.assertEqual(pack_channels([0.0, 10.5, 21.25]), "0,10.5,21.25")
        self.assertEqual(pack_channels([1, 2, 3]), "1,2,3")
        self.assertIsNone(pack_channels(None))
        self.assertEqual(pack_channels([]), "")

    def test_symmetric_pace_pct_sign(self):
        # driver 90.0s vs teammate 90.9s -> driver faster -> negative
        self.assertLess(symmetric_pace_pct(90000, 90900), 0.0)
        self.assertGreater(symmetric_pace_pct(90900, 90000), 0.0)
        self.assertAlmostEqual(symmetric_pace_pct(90000, 90000), 0.0, places=9)

    def test_symmetric_pace_pct_none_when_missing(self):
        self.assertIsNone(symmetric_pace_pct(None, 90000))
        self.assertIsNone(symmetric_pace_pct(90000, None))

    def test_tyre_deg_slope_positive_when_degrading(self):
        # lap times rising with tyre life -> positive ms/lap slope
        self.assertGreater(tyre_deg_slope([1, 2, 3, 4], [90000, 90200, 90400, 90600]), 0.0)
        self.assertAlmostEqual(tyre_deg_slope([1, 2, 3, 4], [90000, 90200, 90400, 90600]), 200.0, places=6)

    def test_tyre_deg_slope_zero_when_insufficient(self):
        self.assertEqual(tyre_deg_slope([1], [90000]), 0.0)
        self.assertEqual(tyre_deg_slope([], []), 0.0)

    def test_is_green_lap(self):
        self.assertTrue(is_green_lap("1"))       # green throughout
        self.assertTrue(is_green_lap("11"))      # repeated green code
        self.assertFalse(is_green_lap(""))       # unknown -> NOT green
        self.assertFalse(is_green_lap(None))     # missing -> NOT green
        self.assertFalse(is_green_lap("12"))     # a yellow occurred
        self.assertFalse(is_green_lap("4"))      # SC


if __name__ == "__main__":
    unittest.main()
