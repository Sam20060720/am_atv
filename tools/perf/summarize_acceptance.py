#!/usr/bin/env python3
"""Validate complete same-candidate coverage across disjoint automated runs.

Reads saved artifacts only. Functional coverage is distinct from rendering
quality; this report never turns missing GPU counters into zero GPU work.
"""
from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import statistics

from run_tv_perf import SCENE_IDS, requested_scenes_passed


def summarize(paths, superseded=None):
    superseded = superseded or {}
    errors, runs, rows, candidates, modes, seen = [], [], [], set(), set(), Counter()
    for path in paths:
        host = json.loads((path / "host.json").read_text())
        ledger = json.loads((path / "manifest.json").read_text())
        report = json.loads((path / "report.json").read_text())
        candidate = host.get("candidate_sha256")
        candidates.add(candidate)
        modes.add(host.get("render_mode", "visible"))
        scenes = ledger.get("scenes", [])
        expected = host.get("expected_scene_ids", [])
        if (host.get("status") != "complete" or host.get("iterations") != 3
                or not host.get("candidate_unchanged") or not host.get("measurement_environment_stable")
                or not requested_scenes_passed(scenes, expected, 3)):
            errors.append(f"{path.name}: incomplete or uncontrolled three-trial run")
        if ledger.get("candidate_sha256") != candidate or report.get("candidate_sha256") != candidate:
            errors.append(f"{path.name}: ledger/report candidate mismatch")
        if not host.get("trace_enabled") or not (path / "survey.pftrace").is_file():
            errors.append(f"{path.name}: missing trace capture")
        if host.get("suite") == "playing":
            cleanup = ledger.get("cleanup", {})
            if not all(cleanup.get(k) for k in ("paused", "view_restored", "controls_restored")) or cleanup.get("restore_error"):
                errors.append(f"{path.name}: playing cleanup not verified")
        runs.append({"run_id": path.name, "directory": str(path.resolve()), "suite": host.get("suite"),
                     "render_mode": host.get("render_mode", "visible"),
                     "scene_count": len(scenes), "candidate_sha256": candidate})
        analyzed = {(t["scene_id"], t["trial"]): t for t in report.get("trials", []) if t.get("phase") == "measure"}
        for scene in scenes:
            scene_id = scene["scene_id"]
            if scene_id in superseded.get(path.name, {}):
                continue  # Raw artifacts stay intact; the complete union below still requires a replacement.
            seen[scene_id] += 1
            trials = []
            for trial in scene.get("trials", []):
                key = (scene_id, trial.get("trial"))
                analysis = analyzed.get(key, {})
                trace = analysis.get("trace", {})
                ownership = trace.get("app_attribution", {})
                if not (trace.get("scope_valid") and trace.get("bounds_covered") and ownership.get("identified_app_threads")):
                    errors.append(f"{path.name}/{scene_id}/{trial.get('trial')}: trace ownership or bounds unavailable")
                metrics = analysis.get("gfxinfo", {}).get("metrics_ms", {})
                inputs = trial.get("input_actual", [])
                layers = trace.get("frame_timeline", {}).get("layers", [])
                trials.append({"trial": trial.get("trial"), "status": trial.get("status"),
                    "analysis_status": analysis.get("analysis_status"),
                    "p90_ms": {name: metrics.get(name, {}).get("p90") for name in (
                        "ui_wall", "rt_command_issue_wall", "gpu_completion_after_submit_proxy", "frame_total_wall")},
                    "maximum_input_lateness_ms": max((i.get("late_ns", 0) / 1e6 for i in inputs), default=None),
                    "maximum_effective_input_lateness_ms": max((
                        max(0, i["actual_ns"] - i.get("effective_not_before_ns", i["scheduled_ns"])) / 1e6
                        for i in inputs), default=None),
                    "intentional_hold_ms": sum(i.get("intentional_hold_ms", 0) for i in inputs),
                    "capture_health_stats": trace.get("capture_health_stats", []),
                    "layers": [{k: layer.get(k) for k in ("layer", "surface_frames", "late_present_surface_frames",
                        "app_late_finish_frames", "app_deadline_missed_surface_frames")} for layer in layers]})
            medians = {}
            for name in ("ui_wall", "rt_command_issue_wall", "gpu_completion_after_submit_proxy", "frame_total_wall"):
                values = [t["p90_ms"][name] for t in trials if t["p90_ms"][name] is not None]
                medians[name] = statistics.median(values) if values else None
            rows.append({"scene_id": scene_id, "run_id": path.name, "trials": trials, "median_trial_p90_ms": medians})
    expected_all = set().union(*SCENE_IDS.values())
    if set(seen) != expected_all:
        errors.append(f"Missing scenes: {sorted(expected_all - set(seen))}; unexpected: {sorted(set(seen) - expected_all)}")
    if any(n != 1 for n in seen.values()):
        errors.append(f"Overlapping scene groups: {sorted(k for k, n in seen.items() if n != 1)}")
    if len(candidates) != 1 or None in candidates:
        errors.append("Runs do not share one verified target candidate")
    if len(modes) != 1:
        errors.append("Headless and visible host presentation metrics cannot share one performance cycle")
    return {"schema_version": 1, "candidate_sha256": next(iter(candidates)) if len(candidates) == 1 else None,
            "render_mode": next(iter(modes)) if len(modes) == 1 else "mixed",
            "all_40_scenes_three_trials_with_trace": not errors,
            "scene_count": len(seen), "measured_trials": sum(len(r["trials"]) for r in rows),
            "superseded_measurements": superseded,
            "errors": errors, "runs": runs, "scenes": rows,
            "limitations": ["Functional and trace coverage do not certify visual or performance quality; review the saved renders and per-trial presentation data.",
                "Headless host-GPU captures preserve guest rendering but omit native host-window presentation; do not pool fence/dequeue/presentation results with visible-window baselines.",
                "Emulator GPU completion/fence proxies are not physical GPU execution time.",
                "Surface layers must not be summed as display dropped frames. Raw Prediction Error alone is not a dropped frame."]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("runs", type=Path, nargs="+")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = summarize(args.runs)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({k: v for k, v in result.items() if k in ("all_40_scenes_three_trials_with_trace", "scene_count", "measured_trials", "errors")}))
    return 0 if result["all_40_scenes_three_trials_with_trace"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
