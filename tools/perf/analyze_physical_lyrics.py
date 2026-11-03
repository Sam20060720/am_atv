#!/usr/bin/env python3
"""Offline analysis of the exact PhysicalLyricsFrameTest MONOTONIC interval.

App-window wall latency and actual SurfaceFlinger presentation gaps are separate
observations. Neither the gfxinfo GPU completion proxy nor a swap/fence wait is
measured GPU execution time. This script never controls a device.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path
import re

from analyze_tv_perf import MAX_TS, distribution, frame_phases, parse_gfxinfo
from physical_frame_windows import presentation_intervals_ns

MAIN_WINDOW = r"^dev\.amatv/dev\.amatv\.MainActivity(?:$|[#\s])"


def _integer(value, name):
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValueError(f"{name} must be an integer")
    return value


def _coverage(timestamps, start, end):
    selected = sorted(set(t for t in timestamps if start <= t <= end))
    return {
        "n": len(selected),
        "first_ns": selected[0] if selected else None,
        "last_ns": selected[-1] if selected else None,
        "observed_span_ms": (selected[-1] - selected[0]) / 1_000_000 if selected else None,
        "head_gap_ms": (selected[0] - start) / 1_000_000 if selected else None,
        "tail_gap_ms": (end - selected[-1]) / 1_000_000 if selected else None,
    }


def analyze_run(run: Path, window_regex: str = MAIN_WINDOW):
    result = json.loads((run / "result.json").read_text())
    if result.get("status") != "passed":
        raise ValueError(f"Measurement did not pass: {result.get('status')}: {result.get('error', '')}")
    start = _integer(result.get("start_ns"), "start_ns")
    end = _integer(result.get("end_ns"), "end_ns")
    if not 0 < start < end < MAX_TS:
        raise ValueError("start_ns/end_ns must form a positive MONOTONIC interval")
    pattern = re.compile(window_regex)
    gfx_paths = sorted(p for p in run.glob("*gfxinfo*.txt") if "reset" not in p.name)
    if not gfx_paths:
        raise ValueError("No gfxinfo frame snapshot was collected")
    unique, sources = {}, []
    for path in gfx_paths:
        rows, summary = parse_gfxinfo(path)
        sources.append({"file": path.name, **summary})
        for row in rows:
            identity = (row["window"], row["raw"]["IntendedVsync"])
            previous = unique.get(identity)
            # An early dump may still show an incomplete row. Prefer its later
            # completed version when overlapping snapshots contain the same frame.
            def completion(candidate):
                value = candidate["raw"].get("FrameCompleted", 0)
                return value if 0 < value < MAX_TS else 0
            if previous is None or completion(row) >= completion(previous):
                unique[identity] = row
    selected = [row for row in unique.values()
                if start <= row["raw"]["IntendedVsync"] < end and pattern.search(row["window"])]
    if not selected:
        raise ValueError("No actual main-activity window frames in the exact MONOTONIC interval")
    metrics, rejected = defaultdict(list), Counter()
    eligible = [row for row in selected if row["raw"]["Flags"] == 0]
    for row in eligible:
        phases, invalid = frame_phases(row["raw"])
        rejected.update(invalid)
        for name, value in phases.items():
            metrics[name].append(value)

    surface_paths = sorted(run.glob("*surface-latency*.txt"))
    present, periods, surface_rows = set(), set(), 0
    for path in surface_paths:
        for line in path.read_text(errors="replace").splitlines():
            cells = line.split()
            if len(cells) == 1 and cells[0].isdigit() and 0 < int(cells[0]) <= 1_000_000_000:
                periods.add(int(cells[0]))
            if len(cells) != 3 or not all(cell.isdigit() for cell in cells):
                continue
            actual = int(cells[1])  # desired, actual, ready — never the ready timestamp.
            if 0 < actual < MAX_TS:
                surface_rows += 1
                present.add(actual)
    intervals = [value / 1_000_000 for value in presentation_intervals_ns(present, [(start, end)])]
    before, after = result.get("before", {}), result.get("after", {})
    advance = (_integer(after.get("projected_position_ms"), "after.projected_position_ms") -
               _integer(before.get("projected_position_ms"), "before.projected_position_ms"))
    original, cleanup = result.get("original", {}), result.get("cleanup", {})
    identity = before.get("identity")
    same_track = bool(identity) and identity == after.get("identity") == original.get("identity")
    delta, tolerance = cleanup.get("position_delta_ms"), cleanup.get("position_tolerance_ms")
    delta_valid = (isinstance(delta, int) and not isinstance(delta, bool) and
                   isinstance(tolerance, int) and not isinstance(tolerance, bool) and tolerance >= 0)
    coverage = _coverage(present, start, end)
    warnings = []
    if not eligible:
        warnings.append("No zero-Flags frame is eligible; no app phase quantile can be claimed.")
    if any(source.get("aggregate_total_frames", 0) > source["raw_rows"] for source in sources):
        warnings.append("gfxinfo PROFILEDATA is a retained ring-buffer sample, not all rendered frames in the six-second scene.")
    if not surface_paths or not intervals:
        warnings.append("No actual-present intervals were retained; absence is not zero GPU work or a smoothness pass.")
    if coverage["head_gap_ms"] is not None and coverage["head_gap_ms"] > 250:
        warnings.append("SurfaceFlinger history misses the beginning of the measured interval; presentation quantiles describe the retained tail.")
    if not same_track or advance < 5_000 or before.get("state") != 3 or after.get("state") != 3:
        warnings.append("Same-track actively playing six-second measurement proof is incomplete or inconsistent.")
    if cleanup.get("status") != "passed" or not delta_valid or abs(delta) > tolerance:
        warnings.append("Exact cursor cleanup proof is incomplete or outside its reported tolerance.")
    duration_ms = (end - start) / 1_000_000
    if abs(duration_ms - 6_000) > 250:
        warnings.append("Actual marked interval differs from the nominal six seconds; exact markers were retained without clipping.")
    return {
        "run_id": result.get("run_id"), "measurement_status": result["status"],
        "hardware": result.get("hardware"), "pid": result.get("pid"),
        "sample_unit": "app_window_frame_latency_not_fps", "clock": "monotonic",
        "active_windows_monotonic_ns": [[start, end]], "actual_measurement_ms": duration_ms,
        "requested_measurement_ms": result.get("measurement_ms"), "window_regex": window_regex,
        "selected_windows": sorted({row["window"] for row in selected}),
        "raw_sources": sources, "unique_gfxinfo_frames": len(unique),
        "selected_flags": dict(Counter(str(row["raw"]["Flags"]) for row in selected)),
        "eligible_frame_count": len(eligible), "rejected_phase_fields": dict(rejected),
        "metrics_ms": {name: distribution(values) for name, values in sorted(metrics.items())},
        "gfxinfo_interval_coverage": _coverage([row["raw"]["IntendedVsync"] for row in selected], start, end),
        "surface_sources": [path.name for path in surface_paths], "surface_refresh_period_ns": sorted(periods),
        "surface_valid_rows": surface_rows, "surface_duplicate_rows": surface_rows - len(present),
        "actual_present_interval_ms": distribution(intervals),
        "actual_present_gaps_over_45ms": sum(value > 45 for value in intervals),
        "presentation_interval_coverage": coverage,
        "playback_proof": {"identity": identity, "same_track": same_track,
                           "before": before, "after": after, "projected_position_advance_ms": advance,
                           "minimum_expected_advance_ms": 5_000,
                           "new_track_or_queue_actions": result.get("new_track_or_queue_actions")},
        "cleanup_proof": {"status": cleanup.get("status"), "position_delta_ms": delta,
                          "position_tolerance_ms": tolerance,
                          "within_reported_tolerance": abs(delta) <= tolerance if delta_valid else None,
                          "same_track": identity == cleanup.get("media", {}).get("identity") if identity else None,
                          "view_restored": cleanup.get("view_restored"),
                          "listener_setting_restored": cleanup.get("listener_setting_restored")},
        "warnings": warnings,
        "interpretation": "Phase values are app-window wall latencies, not FPS. GPU completion after swap/submit is a latency proxy, not GPU execution time. SurfaceFlinger gaps use deduplicated actual-present timestamps with both endpoints inside the exact marked interval; boundary gaps are neither clipped nor invented as frames.",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run", type=Path)
    parser.add_argument("--window-regex", default=MAIN_WINDOW)
    args = parser.parse_args()
    try:
        report = analyze_run(args.run, args.window_regex)
    except (ValueError, OSError, KeyError) as error:
        parser.exit(2, f"Cannot analyze lyrics measurement: {error}\n")
    (args.run / "active-frames.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    for name in ("frame_total_wall", "ui_wall", "rt_command_issue_wall", "gpu_completion_after_swap_proxy"):
        print(name, report["metrics_ms"].get(name, distribution([])))
    print("actual_present_interval_ms", report["actual_present_interval_ms"])
    print("actual_present_gaps_over_45ms", report["actual_present_gaps_over_45ms"])
    print("projected_position_advance_ms", report["playback_proof"]["projected_position_advance_ms"])
    print("cleanup_proof", report["cleanup_proof"])
    for warning in report["warnings"]:
        print("warning:", warning)


if __name__ == "__main__":
    main()
