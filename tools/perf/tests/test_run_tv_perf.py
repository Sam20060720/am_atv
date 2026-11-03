import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("tv_perf_runner", Path(__file__).parents[1] / "run_tv_perf.py")
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class CompletionGateTests(unittest.TestCase):
    def rows(self, count=29, trials=3):
        return [{"scene_id": f"scene-{i}", "status": "ok", "trials": [{"trial": n + 1, "status": "ok"} for n in range(trials)]} for i in range(count)]

    def test_twenty_six_of_twenty_nine_is_incomplete(self):
        self.assertFalse(runner.requested_scenes_passed(self.rows(26), {f"scene-{i}" for i in range(29)}, 3))

    def test_skipped_route_is_incomplete_even_if_junit_passed(self):
        rows = self.rows()
        rows[-1]["status"] = "skip"
        self.assertFalse(runner.requested_scenes_passed(rows, {r["scene_id"] for r in rows}, 3))

    def test_missing_trial_cannot_be_hidden_by_scene_status(self):
        rows = self.rows()
        rows[0]["trials"].pop()
        self.assertFalse(runner.requested_scenes_passed(rows, {r["scene_id"] for r in rows}, 3))

    def test_duplicate_scene_cannot_replace_a_missing_route(self):
        rows = self.rows()
        expected = {r["scene_id"] for r in rows}
        rows[-1]["scene_id"] = rows[0]["scene_id"]
        self.assertFalse(runner.requested_scenes_passed(rows, expected, 3))

    def test_all_requested_routes_and_trials_are_required(self):
        rows = self.rows()
        self.assertTrue(runner.requested_scenes_passed(rows, {r["scene_id"] for r in rows}, 3))

    def test_duplicate_trial_cannot_replace_the_third_repeat(self):
        rows = self.rows()
        rows[0]["trials"][2]["trial"] = 2
        self.assertFalse(runner.requested_scenes_passed(rows, {r["scene_id"] for r in rows}, 3))
