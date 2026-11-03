import importlib.util
import copy
import json
import sqlite3
from pathlib import Path
import tempfile
import unittest
from unittest import mock

SPEC = importlib.util.spec_from_file_location("analyze_tv_perf", Path(__file__).parents[1] / "analyze_tv_perf.py")
perf = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(perf)
FIXTURE = Path(__file__).parent / "fixtures/framestats-labels.txt"


class OfflineFrameAnalysisTests(unittest.TestCase):
    def test_raw_labels_flags_unknown_gpu_and_ring_coverage(self):
        rows, summary = perf.parse_gfxinfo(FIXTURE)
        result, normalized = perf.summarize_gfx(rows, summary, {})
        self.assertEqual(result["valid_flag_zero_rows"], 2)
        self.assertEqual(result["metrics_ms"]["ui_wall"]["p90"], 2.0)
        self.assertEqual(result["metrics_ms"]["surface_dequeue_wall"]["p90"], 2.9)
        self.assertEqual(result["metrics_ms"]["gpu_completion_after_submit_proxy"]["n"], 1)
        self.assertEqual(result["metrics_ms"]["gpu_completion_after_submit_proxy"]["p90"], 5.0)
        self.assertEqual(result["deadline"]["missed_frames"], 1)
        self.assertEqual(result["deadline"]["missed_pct"], 50.0)
        self.assertTrue(result["coverage"]["raw_retention_truncated"])
        self.assertTrue(any("FrameInterval" in item for item in result["warnings"]))
        self.assertEqual(normalized[0]["raw"]["GpuTaskCompleted"], 9000000000)
        self.assertEqual(result["source_summary_unscoped"]["aggregate_jank_pct"], 70.0)

    def test_half_open_monotonic_scene_and_clock_rejection(self):
        rows, summary = perf.parse_gfxinfo(FIXTURE)
        result, _ = perf.summarize_gfx(rows, summary, {"start_ns": 1000000000, "end_ns": 1033000000})
        self.assertEqual(result["selected_rows"], 1)
        with self.assertRaises(ValueError):
            perf.summarize_gfx(rows, summary, {"clock": "boottime"})

    def test_activity_and_dialog_same_vsync_are_distinct_surfaces(self):
        text = FIXTURE.read_text()
        header, first_row = text.split("---PROFILEDATA---")[1].strip().splitlines()[:2]
        text += "\nWindow: dev.amatv/Dialog\n---PROFILEDATA---\n" + header + "\n" + first_row + "\n---PROFILEDATA---\n"
        with tempfile.TemporaryDirectory() as tmp:
            file = Path(tmp) / "windows.txt"
            file.write_text(text)
            rows, summary = perf.parse_gfxinfo(file)
        result, _ = perf.summarize_gfx(rows, summary, {})
        self.assertEqual(result["selected_rows"], 4)
        self.assertEqual(len(result["windows"]), 2)
        self.assertEqual(result["sample_unit"], "app_window_frame_not_display_vsync")

    def test_column_order_is_not_a_positional_contract(self):
        text = FIXTURE.read_text()
        lines = text.splitlines()
        active = False
        for index, line in enumerate(lines):
            if line == "---PROFILEDATA---":
                active = not active
            elif active:
                lines[index] = ",".join(reversed(line.split(",")[:-1])) + ","
        with tempfile.TemporaryDirectory() as tmp:
            file = Path(tmp) / "reordered.txt"
            file.write_text("\n".join(lines))
            rows, summary = perf.parse_gfxinfo(file)
        result, _ = perf.summarize_gfx(rows, summary, {})
        self.assertEqual(result["metrics_ms"]["gpu_completion_after_submit_proxy"]["p90"], 5.0)

    def test_nested_skip_and_interrupted_jsonl(self):
        document = {"schema_version": 1, "scenes": [
            {"scene_id": "home", "status": "ok", "trials": [{"trial": 2, "status": "failed"}]},
            {"scene_id": "radio", "status": "skip", "skip_reason": "unavailable", "trials": []}]}
        flat = perf.flatten_ledger(document)
        self.assertEqual([(x["scene_id"], x["trial"], x["status"]) for x in flat],
                         [("home", 2, "failed"), ("radio", None, "skip")])
        with tempfile.TemporaryDirectory() as tmp:
            file = Path(tmp) / "progress.jsonl"
            file.write_text(json.dumps({"schema_version": 1, "run_id": "test"}) + "\n" +
                            json.dumps({"scene_id": "home", "trial": 1, "status": "ok"}) + '\n{"scene_id":')
            loaded, scenes = perf.read_ledger(file)
        self.assertTrue(loaded["incomplete_last_jsonl_record"])
        self.assertEqual(len(scenes), 1)

    def test_trials_are_not_pooled_and_failed_trials_are_excluded(self):
        trials = []
        for index, (ui, wait) in enumerate([(1, 100), (3, 1), (5, 2)]):
            trials.append({"scene_id": "home", "trial": index, "status": "ok", "analysis_status": "ok", "gfxinfo": {
                "metrics_ms": {"ui_wall": perf.distribution([ui]), "surface_dequeue_wall": perf.distribution([wait])}}})
        trials.append({"scene_id": "home", "status": "failed", "analysis_status": "not_measured"})
        result = perf.trial_group_summary(trials)[0]
        self.assertEqual(result["trial_p90_distributions_ms"]["gfxinfo.surface_dequeue_wall"]["p50"], 2)
        self.assertEqual(result["dominant_observed_phase_by_median_trial_p90"], "CPU/UI wall (not CPU running)")
        self.assertFalse(result["classification_is_root_cause"])

    def test_missing_trace_clock_never_aliases_monotonic_and_boottime(self):
        from collections import defaultdict
        rows = defaultdict(list)
        rows[("capabilities", -1)] = [{"clock_id": 6, "trace_start_ns": 0, "trace_end_ns": 100,
                                     "gpu_slice_rows": 0}]
        result = perf.summarize_trace(rows, 0, {"start_ns": 1, "end_ns": 90})
        self.assertFalse(result["scope_valid"])
        self.assertIsNone(result["pure_gpu_execution_ms"])

    def test_trace_sql_removes_nested_wait_and_separates_sleep_from_cpu(self):
        db = sqlite3.connect(":memory:")
        self.addCleanup(db.close)
        db.executescript("""
          CREATE TABLE metadata(name TEXT,int_value INT);
          INSERT INTO metadata VALUES('trace_time_clock_id',6);
          CREATE TABLE trace_bounds(start_ts INT,end_ts INT);
          INSERT INTO trace_bounds VALUES(0,100);
          CREATE TABLE gpu_slice(ts INT,dur INT);
          CREATE TABLE stats(name TEXT,severity TEXT,value INT);
          CREATE TABLE process(upid INT,pid INT,name TEXT,start_ts INT,end_ts INT);
          INSERT INTO process VALUES(1,11,'dev.amatv',NULL,NULL);
          CREATE TABLE thread(utid INT,tid INT,name TEXT,upid INT);
          INSERT INTO thread VALUES(2,22,'RenderThread',1),(6,26,'RenderThread',1);
          CREATE TABLE thread_track(id INT,utid INT);
          INSERT INTO thread_track VALUES(3,2);
          CREATE TABLE slice(id INT,ts INT,dur INT,depth INT,name TEXT,track_id INT);
          INSERT INTO slice VALUES(4,10,50,0,'dequeueBuffer',3),(5,10,50,1,'dequeueBuffer',3);
          CREATE TABLE thread_state(utid INT,ts INT,dur INT,state TEXT);
          INSERT INTO thread_state VALUES(2,10,1,'Running'),(2,11,49,'S'),(6,10,50,'S');
          CREATE TABLE actual_frame_timeline_slice(ts INT,dur INT,upid INT,layer_name TEXT,
            surface_frame_token INT,display_frame_token INT,jank_type TEXT,on_time_finish INT,
            present_type TEXT,gpu_composition INT);
        """)
        scene = {"start_ns": 1000, "end_ns": 2000, "trace_start_ns": 0, "trace_end_ns": 100}
        rows = list(db.execute(perf.trace_query([(0, scene)], "dev.amatv")))
        waits = [json.loads(bytes.fromhex(payload)) for kind, _, payload in rows if kind == "slice"]
        self.assertEqual(len(waits), 1)
        self.assertEqual(waits[0]["id"], 4)
        self.assertEqual(waits[0]["running_ns"], 1)
        self.assertEqual(waits[0]["sleeping_ns"], 49)
        self.assertEqual(waits[0]["covered_ns"], 50)
        thread_rows = [json.loads(bytes.fromhex(payload)) for kind, _, payload in rows if kind == "thread_state"]
        self.assertEqual({row["tid"] for row in thread_rows}, {22, 26})

    def test_idle_zero_frames_are_quiescent_only_with_complete_artifacts(self):
        from collections import defaultdict
        trace_rows = defaultdict(list)
        trace_rows[("capabilities", -1)] = [{"clock_id": 6, "trace_start_ns": 0, "trace_end_ns": 100,
            "gpu_slice_rows": 0, "frame_timeline_rows": 2, "thread_state_rows": 1}]
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            (base / "empty-gfx.txt").write_text("Stats since: 0ns\nTotal frames rendered: 0\n"
                "Window: dev.amatv/MainActivity\n---PROFILEDATA---\nFlags,IntendedVsync,\n---PROFILEDATA---\n")
            (base / "trace").write_bytes(b"test fixture; processor results mocked")
            trial = {"trial": 1, "status": "ok", "workload": "idle", "gfxinfo": "empty-gfx.txt",
                     "trace": "trace", "start_ns": 1, "end_ns": 90, "trace_start_ns": 1, "trace_end_ns": 90}
            for index in range(6):
                trace_rows[("attribution", index)] = [{"mode": "legacy_process_name",
                    "identified_processes": 1, "identified_threads": 1, "identified_ui_threads": 1}]
                trace_rows[("thread_state", index)] = [{"pid": 11, "tid": 11, "utid": 1,
                    "role": "ui", "thread": "dev.amatv", "state": "S", "dur": 89}]
            trace_rows[("attribution", 5)] = [{"mode": "manifest_pid_scoped",
                "identified_processes": 0, "identified_threads": 0, "identified_ui_threads": 0}]
            trace_rows[("thread_state", 5)] = []
            scenes = [{**trial, "scene_id": "idle"},
                      {**trial, "scene_id": "missing", "gfxinfo": "missing.txt"},
                      {**trial, "scene_id": "outside", "trace_start_ns": 110, "trace_end_ns": 190},
                      {**trial, "scene_id": "interactive_failed", "workload": "interactive", "status": "failed"},
                      {**trial, "scene_id": "interactive_zero", "workload": "interactive"},
                      {**trial, "scene_id": "unidentified_idle", "app_pids": [99]}]
            with mock.patch.object(perf, "find_processor", return_value=base / "unused"), \
                    mock.patch.object(perf, "analyze_trace", return_value=trace_rows):
                report, _ = perf.analyze({"package": "dev.amatv"}, scenes, base)
        self.assertEqual([item["analysis_status"] for item in report["trials"]],
                         ["quiescent", "unavailable", "unavailable", "not_measured", "unavailable", "unavailable"])
        self.assertFalse(report["trials"][5]["trace"]["app_attribution"]["identified_app_threads"])
        self.assertEqual(report["trials"][5]["trace"]["app_attribution"]["requested_pids"], [99])
        gpu = report["trials"][0]["gfxinfo"]["metrics_ms"]["gpu_completion_after_submit_proxy"]
        self.assertEqual(gpu["n"], 0)
        self.assertIsNone(gpu["p90"])
        self.assertIsNone(gpu["sum"])
        self.assertIsNone(report["trials"][0]["trace"]["pure_gpu_execution_ms"])

    def test_no_periodic_flush_is_an_explicit_sorting_risk_not_measured_loss(self):
        from collections import defaultdict
        rows = defaultdict(list)
        rows[("capabilities", -1)] = [{"clock_id": 6, "trace_start_ns": 0, "trace_end_ns": 100,
                                     "gpu_slice_rows": 0}]
        warning = {"name": "config_write_into_file_no_flush", "severity": "error", "value": 1}
        rows[("stat", -1)] = [warning]
        window = {"trace_start_ns": 1, "trace_end_ns": 90}
        unfixed = perf.summarize_trace(rows, 0, window)
        self.assertTrue(unfixed["import_sorting_risk"])
        self.assertEqual(unfixed["capture_health_stats"], [])
        rows[("import_options", -1)] = [{"full_sort": True}]
        fixed = perf.summarize_trace(rows, 0, window)
        self.assertFalse(fixed["import_sorting_risk"])
        self.assertEqual(fixed["configuration_warnings"], [warning])
        rows[("stat", -1)].append({"name": "long_trace_mode_bytes_overwritten", "severity": "data_loss", "value": 1024})
        lossy = perf.summarize_trace(rows, 0, window)
        self.assertEqual(len(lossy["capture_health_stats"]), 1)
        self.assertFalse(lossy["import_sorting_risk"])

    def test_service_global_scope_postprocess_preserves_raw_and_real_loss_gates(self):
        global_stat = {"name": "traced_chunks_discarded", "severity": "info", "value": 4}
        session_loss = {"name": "traced_buf_chunks_overwritten", "severity": "data_loss", "value": 1}
        invalid = {"name": "traced_invalid_packets", "severity": "info", "value": 1}
        observations = [{"packet_index": index, "sample_lower_ns": lo, "sample_upper_ns": hi,
                         "counters": {"traced_chunks_discarded": value, "traced_patches_discarded": 0}}
                        for index, (lo, hi, value) in enumerate([(0, 100, 3), (100, 200, 4), (900, 1000, 4)])]
        evidence = {"status": "complete", "observations": observations, "errors": []}
        scenes, records = [], []
        for name in ["safe", "idle", "session_loss", "unknown", "monotonic", "invalid", "severe_global"]:
            raw = [copy.deepcopy(global_stat)]
            if name == "session_loss":
                raw.append(session_loss)
            if name == "invalid":
                raw.append(invalid)
            if name == "severe_global":
                raw[0]["severity"] = "data_loss"
            scenes.append({"scene_id": name, "trial": 1, "trace_start_ns": 50 if name == "unknown" else 400,
                           "trace_end_ns": 800, "start_ns": 400, "end_ns": 800})
            records.append({"scene_id": name, "trial": 1, "phase": "measure", "status": "ok", "errors": [],
                "workload": "idle" if name == "idle" else "interaction",
                "gfxinfo": {"valid_flag_zero_rows": 0 if name == "idle" else 1,
                    "coverage": {"scope": "clock_window", "raw_retention_truncated": False},
                    "source_summary_unscoped": {"aggregate_total_frames": 0}},
                "trace": {"path": "/synthetic/trace.pftrace", "sha256": "fixture", "capture_health_stats": raw,
                    "capabilities": {"clock_id": 3 if name == "monotonic" else 6, "frame_timeline_rows": 2},
                    "bounds_covered": True, "import_sorting_risk": False,
                    "app_attribution": {"identified_app_threads": True, "ui_scheduler_coverage_fraction": 1.0},
                    "frame_timeline": {"layers": [], "incomplete_surface_frames": 0}, "warnings": []}})
        original_raw = [copy.deepcopy(record["trace"]["capture_health_stats"]) for record in records]
        report = {"trials": records}
        with mock.patch.object(perf.trace_health_scope, "inspect_service_global_counters", return_value=evidence) as inspect:
            perf.apply_service_health_scope(report, scenes, Path("/"))
        inspect.assert_called_once()
        self.assertEqual([record["analysis_status"] for record in records],
                         ["ok", "quiescent", "partial", "partial", "partial", "partial", "partial"])
        self.assertEqual([record["trace"]["capture_health_stats"] for record in records], original_raw)
        self.assertEqual(records[0]["trace"]["per_session_capture_health_stats"], [])
        self.assertEqual(records[2]["trace"]["per_session_capture_health_stats"], [session_loss])
        self.assertEqual(records[5]["trace"]["per_session_capture_health_stats"], [invalid])
        self.assertEqual(records[4]["trace"]["service_global_health"]["reason"], "trace_primary_clock_not_verified_boottime")
        self.assertTrue(records[0]["trace"]["warnings"])
        self.assertEqual(len(report["trace_service_health"]), 1)
        self.assertEqual(report["trace_service_health"][0]["evidence"]["observations"], observations)

    def test_trace_pid_ownership_is_per_scene_and_lifetime_not_global_name_union(self):
        from collections import defaultdict
        db = sqlite3.connect(":memory:")
        self.addCleanup(db.close)
        db.executescript("""
          CREATE TABLE metadata(name TEXT,int_value INT);
          INSERT INTO metadata VALUES('trace_time_clock_id',6);
          CREATE TABLE trace_bounds(start_ts INT,end_ts INT);
          INSERT INTO trace_bounds VALUES(0,300);
          CREATE TABLE gpu_slice(ts INT,dur INT);
          CREATE TABLE stats(name TEXT,severity TEXT,value INT);
          CREATE TABLE process(upid INT,pid INT,name TEXT,start_ts INT,end_ts INT);
          INSERT INTO process VALUES(1,11,NULL,0,100),(2,12,'dev.amatv',0,100),
                                    (3,11,'reused-pid',200,300);
          CREATE TABLE thread(utid INT,tid INT,name TEXT,upid INT);
          INSERT INTO thread VALUES(1,11,'main',1),(2,21,'RenderThread',1),
                                   (3,12,'main',2),(4,22,'RenderThread',2),
                                   (5,11,'main',3);
          CREATE TABLE thread_track(id INT,utid INT);
          INSERT INTO thread_track VALUES(2,2),(4,4);
          CREATE TABLE slice(id INT,ts INT,dur INT,depth INT,name TEXT,track_id INT);
          INSERT INTO slice VALUES(1,10,50,0,'dequeueBuffer',2),(2,10,50,0,'dequeueBuffer',4);
          CREATE TABLE thread_state(utid INT,ts INT,dur INT,state TEXT);
          INSERT INTO thread_state VALUES(1,0,100,'S'),(2,0,100,'S'),
                                         (3,0,100,'S'),(4,0,100,'S'),(5,200,100,'S');
          CREATE TABLE actual_frame_timeline_slice(ts INT,dur INT,upid INT,layer_name TEXT,
            surface_frame_token INT,display_frame_token INT,jank_type TEXT,on_time_finish INT,
            present_type TEXT,gpu_composition INT);
          INSERT INTO actual_frame_timeline_slice VALUES(10,10,1,'unknown-name-layer',1,1,'None',1,'On-time Present',0),
            (10,10,2,'other-scene-layer',2,2,'None',1,'On-time Present',0),
            (210,10,3,'reused-pid-layer',3,3,'None',1,'On-time Present',0);
        """)
        window = {"trace_start_ns": 0, "trace_end_ns": 100}
        scenes = [(0, {**window, "app_pids": [11]}), (1, {**window, "app_pids": [12]}),
                  (2, {**window, "app_pids": []}), (3, window),
                  (4, {"trace_start_ns": 200, "trace_end_ns": 300, "app_pids": [12]}),
                  (5, {"trace_start_ns": 50, "trace_end_ns": 100, "app_pids": [11]})]
        results = defaultdict(list)
        for kind, index, payload in db.execute(perf.trace_query(scenes, "dev.amatv")):
            results[(kind, index)].append(json.loads(bytes.fromhex(payload)))
        self.assertEqual([r["pid"] for r in results[("attributed_process", 0)]], [11])
        self.assertEqual([r["upid"] for r in results[("attributed_process", 0)]], [1])
        self.assertEqual([r["pid"] for r in results[("timeline", 0)]], [11])
        self.assertEqual([r["pid"] for r in results[("slice", 0)]], [11])
        self.assertEqual({r["pid"] for r in results[("thread_state", 0)]}, {11})
        self.assertEqual([r["pid"] for r in results[("timeline", 1)]], [12])
        self.assertEqual(results[("timeline", 2)], [])
        self.assertEqual(results[("slice", 2)], [])
        self.assertEqual(results[("thread_state", 2)], [])
        self.assertEqual([r["pid"] for r in results[("timeline", 3)]], [12])
        self.assertEqual(results[("attributed_process", 4)], [])
        # The same process in another window must use only that window's clipped
        # scheduler states; a boundary-crossing slice keeps its partial coverage.
        self.assertEqual([r["dur"] for r in results[("thread_state", 5)]], [50, 50])
        self.assertEqual(len(results[("slice", 5)]), 1)
        self.assertTrue(results[("slice", 5)][0]["clipped"])
        self.assertEqual(results[("slice", 5)][0]["covered_ns"], 10)
        self.assertEqual(results[("slice", 5)][0]["sleeping_ns"], 10)
        summary = perf.summarize_trace(results, 0, scenes[0][1])
        self.assertEqual(summary["app_attribution"]["mode"], "manifest_pid_scoped")
        self.assertTrue(summary["app_attribution"]["identified_app_threads"])
        self.assertEqual(summary["app_attribution"]["ui_scheduler_coverage_fraction"], 1.0)
        self.assertEqual(summary["app_attribution"]["observed_row_counts"],
                         {"timeline": 1, "slice": 1, "thread_state": 2})
        missing = perf.summarize_trace(results, 2, scenes[2][1])
        self.assertFalse(missing["app_attribution"]["identified_app_threads"])
        self.assertEqual(missing["app_attribution"]["confidence"], "missing_app_threads")

    def test_manifest_pid_contract_rejects_invalid_and_preserves_explicit_empty(self):
        self.assertIsNone(perf.manifest_pids({}))
        self.assertEqual(perf.manifest_pids({"app_pids": []}), [])
        self.assertEqual(perf.manifest_pids({"app_pids": [12, 11, 12]}), [11, 12])
        for values in (None, "11", [True], [0], [-1], ["11"]):
            with self.subTest(values=values), self.assertRaises(ValueError):
                perf.manifest_pids({"app_pids": values})

    def test_prediction_error_only_is_not_reported_as_late_or_dropped_frames(self):
        layer = {"surface_frames": 54, "jank_known_frames": 54, "janky_surface_frames": 54,
                 "jank_pct": 100.0, "jank_type_counts": {"Prediction Error": 54},
                 "present_type_counts": {"Early Present": 54}, "app_on_time_finish_counts": {"1": 54}}
        report = {"run_id": "prediction-only", "candidate_sha256": "fixture", "scenes": [],
                  "trials": [{"scene_id": "focus", "trial": 1, "status": "ok", "analysis_status": "ok",
                              "errors": [], "trace": {"frame_timeline": {"layers": [layer]}}}]}
        perf.add_timeline_semantics(report)
        self.assertEqual(layer["janky_surface_frames"], 54)
        self.assertEqual(layer["jank_pct"], 100.0)
        self.assertEqual(layer["jank_type_counts"], {"Prediction Error": 54})
        self.assertEqual(layer["prediction_error_only_surface_frames"], 54)
        self.assertEqual(layer["late_present_surface_frames"], 0)
        self.assertEqual(layer["app_late_finish_frames"], 0)
        self.assertEqual(layer["app_deadline_missed_surface_frames"], 0)
        self.assertIn(perf.PREDICTION_ONLY_WARNING, report["trials"][0]["trace"]["warnings"])
        md = perf.markdown_report(report)
        self.assertIn("FT dominant n / late present / app late", md)
        self.assertIn("54 / 0 / 0", md)
        self.assertNotIn("n / janky", md)
        mixed = perf.derive_timeline_layer_counts({"jank_type_counts": {
            "Prediction Error": 4, "Prediction Error, App Deadline Missed": 2},
            "present_type_counts": {"Late Present": 2, "Early Present": 4},
            "app_on_time_finish_counts": {"0": 1, "1": 5}})
        self.assertEqual(mixed["prediction_error_only_surface_frames"], 4)
        self.assertEqual(mixed["app_deadline_missed_surface_frames"], 2)
        self.assertEqual(mixed["late_present_surface_frames"], 2)
        self.assertEqual(mixed["app_late_finish_frames"], 1)
        missing = perf.derive_timeline_layer_counts({})
        self.assertIsNone(missing["late_present_surface_frames"])
        self.assertIsNone(missing["app_late_finish_frames"])
        self.assertIsNone(missing["prediction_error_only_surface_frames"])
        self.assertIsNone(missing["app_deadline_missed_surface_frames"])


if __name__ == "__main__":
    unittest.main()
