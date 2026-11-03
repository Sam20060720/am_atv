from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import runpy
import sys
import tempfile
import unittest
from unittest import mock


PERF = Path(__file__).parents[1]


class PhysicalFrameAnalysisTests(unittest.TestCase):
    def test_later_valid_phases_survive_an_incomplete_first_frame(self):
        with tempfile.TemporaryDirectory() as directory:
            run = Path(directory)
            (run / "result.json").write_text(json.dumps({
                "status": "passed", "run_id": "phase-key-regression",
                "inputs": [{"start_ms": 1000, "dispatch_ms": 0}],
            }))
            (run / "gfxinfo.txt").write_text(
                "Window: dev.amatv/dev.amatv.MainActivity\n---PROFILEDATA---\n"
                "Flags,IntendedVsync,HandleInputStart,SyncQueued,IssueDrawCommandsStart,"
                "SwapBuffers,FrameCompleted,GpuCompleted\n"
                "0,1000000000,1001000000,0,1004000000,1005000000,0,0\n"
                "0,1010000000,1011000000,1013000000,1014000000,1015000000,1020000000,1017000000\n"
                "---PROFILEDATA---\n"
            )
            script = PERF / "analyze_physical_frames.py"
            with mock.patch.object(sys, "argv", [str(script), str(run)]), \
                    mock.patch.object(sys, "path", [str(PERF), *sys.path]), \
                    redirect_stdout(io.StringIO()):
                runpy.run_path(str(script), run_name="__main__")
            metrics = json.loads((run / "active-frames.json").read_text())["metrics_ms"]

        for name, expected_ms in [("frame_total_wall", 10), ("ui_wall", 2),
                                  ("gpu_completion_after_swap_proxy", 2)]:
            with self.subTest(metric=name):
                self.assertEqual(metrics[name]["n"], 1)
                self.assertEqual(metrics[name]["p50"], expected_ms)
        self.assertEqual(metrics["rt_command_issue_wall"]["n"], 2)


if __name__ == "__main__":
    unittest.main()
