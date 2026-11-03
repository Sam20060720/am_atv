import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

PERF = Path(__file__).parents[1]
sys.path.insert(0, str(PERF))
spec = importlib.util.spec_from_file_location("analyze_physical_lyrics", PERF / "analyze_physical_lyrics.py")
lyrics = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lyrics)


class PhysicalLyricsAnalysisTests(unittest.TestCase):
    START, END = 1_000_000_000, 7_000_000_000

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.run = Path(self.temp.name)
        self.result = {
            "run_id": "lyrics-test", "hardware": "m7632", "status": "passed", "measurement_ms": 6000,
            "start_ns": self.START, "end_ns": self.END,
            # BOOTTIME differs; it must never be substituted for the frame bounds.
            "trace_start_ns": self.START + 900_000_000_000, "trace_end_ns": self.END + 900_000_000_000,
            "new_track_or_queue_actions": False,
            "original": {"identity": "original-song", "state": 2, "position_ms": 12345},
            "before": {"identity": "original-song", "state": 3, "projected_position_ms": 60500},
            "after": {"identity": "original-song", "state": 3, "projected_position_ms": 66510},
            "cleanup": {"status": "passed", "position_delta_ms": -3, "position_tolerance_ms": 250,
                        "view_restored": True, "listener_setting_restored": True,
                        "media": {"identity": "original-song", "state": 2}},
        }
        self.write_result()

    def write_result(self):
        (self.run / "result.json").write_text(json.dumps(self.result))

    @staticmethod
    def frame(ts, flags=0, complete=None, gpu=True):
        return [flags, ts, ts + 1_000_000, ts + 3_000_000, ts + 4_000_000,
                ts + 5_000_000, ts + 10_000_000 if complete is None else complete,
                ts + 7_000_000 if gpu else 0]

    def gfx(self, rows, name="gfxinfo.txt", window="dev.amatv/dev.amatv.MainActivity", total=None):
        header = "Flags,IntendedVsync,HandleInputStart,SyncQueued,IssueDrawCommandsStart,SwapBuffers,FrameCompleted,GpuCompleted"
        content = f"Total frames rendered: {total or len(rows)}\nWindow: {window}\n---PROFILEDATA---\n{header}\n"
        content += "\n".join(",".join(map(str, row)) for row in rows) + "\n---PROFILEDATA---\n"
        (self.run / name).write_text(content)

    def surface(self, actual, name="surface-latency.txt"):
        (self.run / name).write_text("16666666\n" + "\n".join(f"1 {ts} 2" for ts in actual) + "\n")

    def test_exact_monotonic_main_window_and_zero_flags_only(self):
        self.gfx([self.frame(self.START - 1), self.frame(self.START),
                  self.frame(self.START + 10_000_000, flags=2), self.frame(self.END - 1, gpu=False), self.frame(self.END)])
        self.gfx([self.frame(self.START + 20_000_000)], "dialog-gfxinfo.txt", "AMATV.ContextMenu")
        report = lyrics.analyze_run(self.run)
        self.assertEqual(report["active_windows_monotonic_ns"], [[self.START, self.END]])
        self.assertEqual(report["selected_flags"], {"0": 2, "2": 1})
        self.assertEqual(report["eligible_frame_count"], 2)
        self.assertEqual(report["metrics_ms"]["frame_total_wall"]["n"], 2)
        self.assertEqual(report["metrics_ms"]["frame_total_wall"]["p50"], 10)
        self.assertEqual(report["metrics_ms"]["gpu_completion_after_swap_proxy"]["n"], 1)
        self.assertFalse(any("gpu_execution" in key for key in report["metrics_ms"]))
        self.assertEqual(report["hardware"], "m7632")
        self.assertEqual(report["playback_proof"]["projected_position_advance_ms"], 6010)
        self.assertTrue(report["playback_proof"]["same_track"])
        self.assertEqual(report["cleanup_proof"]["position_delta_ms"], -3)
        self.assertTrue(report["cleanup_proof"]["within_reported_tolerance"])

    def test_overlapping_frame_snapshots_prefer_complete_without_duplicate_samples(self):
        self.gfx([self.frame(self.START, complete=0)], "early-gfxinfo.txt")
        self.gfx([self.frame(self.START), self.frame(self.START)])
        report = lyrics.analyze_run(self.run)
        self.assertEqual(report["eligible_frame_count"], 1)
        self.assertEqual(report["metrics_ms"]["frame_total_wall"]["p50"], 10)

    def test_actual_present_column_is_deduplicated_and_boundary_gaps_are_not_clipped(self):
        self.gfx([self.frame(self.START)])
        timestamps = [self.START - 1, self.START + 10, self.START + 50_000_010, self.END, self.END + 1]
        self.surface(timestamps + [0, lyrics.MAX_TS])
        self.surface(timestamps, "second-surface-latency.txt")
        report = lyrics.analyze_run(self.run)
        self.assertEqual(report["surface_duplicate_rows"], 5)
        self.assertEqual(report["surface_refresh_period_ns"], [16666666])
        self.assertEqual(report["actual_present_interval_ms"]["n"], 2)
        self.assertEqual(report["actual_present_interval_ms"]["p50"], 2999.999995)
        self.assertEqual(report["actual_present_gaps_over_45ms"], 2)
        self.assertEqual(report["presentation_interval_coverage"]["n"], 3)

    def test_partial_ring_buffer_and_bad_cleanup_are_visible_not_smoothness_success(self):
        self.gfx([self.frame(self.END - 50_000_000)], total=400)
        self.surface([self.END - 50_000_000, self.END - 10_000_000])
        self.result["after"]["identity"] = "unexpected-song"
        self.result["cleanup"]["position_delta_ms"] = 500
        self.write_result()
        report = lyrics.analyze_run(self.run)
        self.assertFalse(report["playback_proof"]["same_track"])
        self.assertFalse(report["cleanup_proof"]["within_reported_tolerance"])
        warnings = " ".join(report["warnings"])
        self.assertIn("ring-buffer", warnings)
        self.assertIn("retained tail", warnings)
        self.assertIn("incomplete or inconsistent", warnings)
        self.assertIn("outside", warnings)

    def test_failed_measurement_invalid_clock_or_absent_main_window_cannot_make_report(self):
        self.gfx([self.frame(self.START)])
        self.result["status"] = "failed"; self.write_result()
        with self.assertRaisesRegex(ValueError, "did not pass"):
            lyrics.analyze_run(self.run)
        self.result["status"] = "passed"; self.result["end_ns"] = self.START; self.write_result()
        with self.assertRaisesRegex(ValueError, "MONOTONIC"):
            lyrics.analyze_run(self.run)
        self.result["end_ns"] = self.END; self.write_result()
        self.gfx([self.frame(self.START)], window="unknown")
        with self.assertRaisesRegex(ValueError, "main-activity"):
            lyrics.analyze_run(self.run)


if __name__ == "__main__":
    unittest.main()
