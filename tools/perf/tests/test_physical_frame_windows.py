import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    "physical_frame_windows", Path(__file__).parents[1] / "physical_frame_windows.py")
windows = importlib.util.module_from_spec(spec)
spec.loader.exec_module(windows)


class PhysicalFrameWindowTests(unittest.TestCase):
    def test_long_stall_across_overlapping_keys_is_not_discarded(self):
        # 500ms stalled present spans the continuous rapid-input burst, despite
        # no individual 300ms key window containing both presentation endpoints.
        self.assertEqual(windows.presentation_intervals_ns(
            [0, 500_000_000, 550_000_000],
            [(0, 300_000_000), (140_000_000, 440_000_000),
             (280_000_000, 580_000_000)]), [500_000_000, 50_000_000])

    def test_disjoint_idle_gap_is_not_counted_or_clipped(self):
        self.assertEqual(windows.presentation_intervals_ns(
            [0, 10, 20, 25, 30, 40, 50], [(0, 20), (30, 50)]),
            [10, 10, 10, 10])

    def test_touching_windows_merge_and_repeated_snapshots_dedupe(self):
        self.assertEqual(windows.merge_windows([(10, 20), (0, 10), (8, 8)]), [(0, 20)])
        self.assertEqual(windows.presentation_intervals_ns([0, 0, 20, 20], [(0, 10), (10, 20)]), [20])
