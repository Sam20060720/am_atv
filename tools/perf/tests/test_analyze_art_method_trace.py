import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("art_method_trace", Path(__file__).parents[1] / "analyze_art_method_trace.py")
art = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(art)


def fixture(events, clock="dual", overflow=False, version=3):
    size = 14 if clock == "dual" else 10
    text = (f"*version\n{version}\ndata-file-overflow={str(overflow).lower()}\nclock={clock}\n"
            f"elapsed-time-usec=10000\nnum-method-calls={len(events)}\n*threads\n42\tmain\n"
            "*methods\n0x4\ta.A\tx\t()V\tA.java\n0x8\tandroid.graphics.Canvas\tnDraw\t()V\tCanvas.java\n*end\n").encode()
    header = struct.pack("<4sHHQH", b"SLOW", version, 32, 123456789, size).ljust(32, b"\0")
    binary = b"".join(struct.pack("<HIII", 42, method | action, cpu, wall) if clock == "dual"
                      else struct.pack("<HII", 42, method | action, wall if clock == "wall" else cpu)
                      for method, action, cpu, wall in events)
    return text + header + binary


class ArtMethodTraceTests(unittest.TestCase):
    def test_stack_diff_same_timestamp_is_not_counted_as_multiple_samples(self):
        events = [(4, 0, 0, 1000), (8, 0, 0, 1000), (8, 1, 200, 3000),
                  (8, 0, 500, 4000), (8, 1, 600, 6000), (4, 1, 600, 6000)]
        report = art.analyze(fixture(events))
        main = report["threads"][0]
        rows = {row["method_id"]: row for row in main["exclusive_leaf"]}
        self.assertEqual(report["status"], "parsed_complete_records")
        self.assertEqual(main["covered_wall_ms"], 5)
        self.assertEqual(main["covered_thread_cpu_ms"], 0.6)
        self.assertEqual(rows["0x8"]["wall_ms"], 4)
        self.assertEqual(rows["0x8"]["thread_cpu_ms"], 0.3)
        self.assertEqual(rows["0x4"]["wall_ms"], 1)
        self.assertEqual(main["inclusive_ancestor"][0]["thread_cpu_ms"], 0.6)

    def test_relative_wall_scope_does_not_mix_setup_and_estimates_partial_cpu(self):
        report = art.analyze(fixture([(4, 0, 0, 1000), (8, 0, 200, 3000),
                                     (8, 1, 600, 7000), (4, 1, 900, 9000)]),
                             start_us=4000, end_us=6000)
        main = report["threads"][0]
        self.assertEqual(main["covered_wall_ms"], 2)
        self.assertEqual(main["covered_thread_cpu_ms"], 0.2)
        self.assertEqual(main["exclusive_leaf"][0]["method_id"], "0x8")

    def test_disjoint_windows_exclude_the_gap_and_overlap_is_rejected(self):
        data = fixture([(4, 0, 0, 1000), (8, 0, 200, 3000),
                        (8, 1, 600, 7000), (4, 1, 900, 9000)])
        report = art.analyze(data, windows_us=[(1000, 2000), (7000, 8000)])
        main = report["threads"][0]
        self.assertEqual(main["covered_wall_ms"], 2)
        self.assertEqual(main["covered_thread_cpu_ms"], 0.25)
        self.assertEqual(main["exclusive_leaf"][0]["method_id"], "0x4")
        with self.assertRaisesRegex(ValueError, "Overlapping"):
            art.analyze(data, windows_us=[(1000, 5000), (4000, 7000)])

    def test_wall_only_never_claims_cpu_time(self):
        report = art.analyze(fixture([(4, 0, 0, 1000), (4, 1, 0, 5000)], clock="wall", version=2))
        self.assertIsNone(report["threads"][0]["covered_thread_cpu_ms"])
        self.assertEqual(report["threads"][0]["covered_wall_ms"], 4)

    def test_final_tail_excludes_window_gaps_and_stops_at_trace_elapsed(self):
        data = fixture([(4, 0, 0, 1000), (8, 0, 200, 2000)])
        for windows, expected_ms in [([(1000, 3000), (7000, 8000)], 2),
                                     ([(1000, 3000), (7000, 12000)], 4)]:
            with self.subTest(windows=windows):
                report = art.analyze(data, windows_us=windows)
                self.assertEqual(report["threads"][0]["unattributed_final_wall_tail_ms"], expected_ms)

    def test_overflow_and_corruption_are_marked(self):
        report = art.analyze(fixture([(4, 0, 0, 1000), (8, 1, 1, 2000)], overflow=True))
        self.assertEqual(report["status"], "partial_or_anomalous")
        self.assertEqual(report["anomalies"]["unmatched_exit_stack_reset"], 1)

    def test_r8_descriptor_match_and_inlining_ambiguity_are_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "mapping.txt"
            path.write_text("Original -> a.A:\n"
                            "    1:2:void exact(int):3:4 -> x\n"
                            "    1:2:void Outer.inlined():3:4 -> x\n"
                            "    1:2:void leaf():3:4 -> x\n")
            mapping = art.Mapping(path)
            self.assertEqual(mapping.labels("a.A", "x", "(I)V")["candidates"], ["Original.exact(int):void"])
            ambiguous = mapping.labels("a.A", "x", "()V")
            self.assertEqual(len(ambiguous["candidates"]), 2)
            self.assertIn("ambiguous", ambiguous["deobfuscation"])


if __name__ == "__main__":
    unittest.main()
