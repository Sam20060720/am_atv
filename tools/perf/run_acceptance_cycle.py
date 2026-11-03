#!/usr/bin/env python3
"""Run the complete automated TV acceptance cycle, preserving every attempt.

No manual navigation. A locked desktop waits for the user; only environment
interruptions may retry automatically. An app/harness failure returns control
for diagnosis and repair instead of being skipped.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
from pathlib import Path
import re
import signal
import subprocess
import sys
import time

from run_tv_perf import ROOT, SCENE_IDS, command, requested_scenes_passed, write_json
from summarize_acceptance import summarize

GROUPS = {
    "a": "home_idle home_horizontal home_vertical browse_idle browse_horizontal browse_vertical home_square_horizontal sidebar_focus sidebar_open_close sidebar_page_browse sidebar_page_library sidebar_page_search".split(),
    "b": "library_categories library_grid album_right_down album_right_up playlist_right_down playlist_right_up context_menu_open_close context_menu_focus context_menu_long_press context_menu_ellipsis settings_focus album_back_to_source playlist_back_to_source sidebar_back_home_focus".split(),
    "c": "search_type_switch search_results_songs search_results_albums artist_newjeans_sections artist_newjeans_horizontal paused_player_controls paused_existing_queue paused_player_idle artist_back_to_search artist_left_sidebar paused_player_back_to_home".split(),
}
STRICT_PILOT = "sidebar_focus sidebar_open_close search_type_switch search_results_songs search_results_albums library_categories artist_newjeans_sections context_menu_open_close context_menu_focus context_menu_long_press context_menu_ellipsis artist_left_sidebar".split()


def validate_partition():
    flattened = sum(GROUPS.values(), [])
    if len(flattened) != len(set(flattened)) or set(flattened) != SCENE_IDS["browse"]:
        raise ValueError("Acceptance groups must cover every browse scene exactly once")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--expected-sha256", required=True)
    parser.add_argument("--render-mode", choices=("visible", "headless"), default="visible")
    parser.add_argument("--restore-position-ms", type=int)
    parser.add_argument("--reuse-group", action="append", default=[], metavar="NAME=RUN_DIRECTORY",
                        help="Explicitly reuse a reviewed, unaffected full group on the same candidate/render mode")
    parser.add_argument("--full-captures-only", action="store_true",
                        help="Pilots were already reviewed; all 40 measured scenes and their excluded warmups still run")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,40}", args.run_id) or not re.fullmatch(r"[a-f0-9]{64}", args.expected_sha256):
        parser.error("Expected a short unique run ID and a SHA-256 candidate digest")
    validate_partition()
    reused = {}
    for value in args.reuse_group:
        name, separator, value_path = value.partition("=")
        if not separator or name not in GROUPS or name in reused:
            parser.error("reuse-group must identify a unique a/b/c group and its saved directory")
        path = Path(value_path).resolve()
        host = json.loads((path / "host.json").read_text())
        ledger = json.loads((path / "manifest.json").read_text())
        if (host.get("status") != "complete" or host.get("suite") != "browse" or host.get("iterations") != 3
                or host.get("candidate_sha256") != args.expected_sha256 or not host.get("candidate_unchanged")
                or not host.get("measurement_environment_stable") or host.get("render_mode", "visible") != args.render_mode
                or set(host.get("expected_scene_ids", [])) != set(GROUPS[name])
                or not requested_scenes_passed(ledger.get("scenes", []), GROUPS[name], 3)
                or not (path / "survey.pftrace").is_file()):
            parser.error("Reused group must be a complete three-trial trace on this same candidate and render mode")
        reused[name] = path
    directory = ROOT / "work/performance" / args.run_id
    directory.mkdir(exist_ok=False)
    state_path = directory / "cycle.json"
    state = {"run_id": args.run_id, "candidate_sha256": args.expected_sha256,
             "render_mode": args.render_mode,
             "reviewed_reused_groups": {name: str(path) for name, path in reused.items()},
             "pilots_previously_reviewed": args.full_captures_only,
             "started_utc": dt.datetime.now(dt.timezone.utc).isoformat(), "status": "preflight", "attempts": []}
    write_json(state_path, state)
    processes = command(["ps", "-axo", "pid,args"]).stdout.decode()
    qemu = [line for line in processes.splitlines() if "qemu-system-aarch64" in line and
            re.search(r"-avd\s+AMATV_Promo_TV(?:\s|$)", line)]
    if len(qemu) != 1:
        raise RuntimeError("Expected the retained AMATV_Promo_TV emulator")
    qemu_pid = int(qemu[0].split()[0])
    helper = directory / "host_state"
    command(["xcrun", "swiftc", "-O", ROOT / "tools/perf/host_state.swift", "-o", helper], timeout=90)
    tester_sources = [ROOT / "benchmark/src/main/java/dev/amatv/benchmark" / name for name in (
        "RenderingSurveyTest.kt", "RenderingSurveyUi.kt", "PlayingRenderingSurveyTest.kt")]
    source_hashes = {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in tester_sources}

    def wait_for_desktop():
        if args.render_mode == "headless":
            return  # Each collector verifies actual headless host-GPU arguments.
        reported = False
        while True:
            observed = json.loads(command([helper, str(qemu_pid)]).stdout)
            if not observed.get("frontmost_is_loginwindow"):
                return
            state.update(status="waiting_for_desktop", last_host_observation=observed)
            write_json(state_path, state)
            if not reported:
                print(json.dumps({"status": "waiting_for_desktop", "cycle": args.run_id}), flush=True)
                reported = True
            time.sleep(5)

    def run(label, suite, scenes, iterations, trace):
        for attempt in range(1, 4):
            wait_for_desktop()
            if any(hashlib.sha256(Path(p).read_bytes()).hexdigest() != h for p, h in source_hashes.items()):
                raise RuntimeError("Tester source changed during the cycle; rebuild before continuing")
            run_id = f"{args.run_id}-{label}-{attempt}"
            argv = [sys.executable, str(ROOT / "tools/perf/run_tv_perf.py"), "--suite", suite,
                    "--render-mode", args.render_mode,
                    "--iterations", str(iterations), "--run-id", run_id, "--timeout", "1800", "--skip-build",
                    "--expected-sha256", args.expected_sha256]
            if scenes:
                argv += ["--scenes", ",".join(scenes)]
            if suite == "playing" and iterations == 3 and args.restore_position_ms is not None:
                argv += ["--restore-position-ms", str(args.restore_position_ms)]
            if not trace:
                argv += ["--no-trace"]
            state.update(status="running", active_run=run_id)
            write_json(state_path, state)
            print(json.dumps({"status": "running", "run_id": run_id}), flush=True)
            with (directory / f"{label}-{attempt}.log").open("wb") as log:
                child = subprocess.Popen(argv, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
                try:
                    exit_code = child.wait()
                except BaseException:
                    child.terminate()
                    try:
                        child.wait(timeout=60)
                    except subprocess.TimeoutExpired:
                        state["collector_cleanup_pending_pid"] = child.pid
                    raise
            run_dir = ROOT / "work/performance" / run_id
            host = json.loads((run_dir / "host.json").read_text())
            state["attempts"].append({"run_id": run_id, "exit_code": exit_code,
                                       "status": host.get("status"), "error": host.get("error")})
            write_json(state_path, state)
            if exit_code == 0 and host.get("status") == "complete":
                return run_dir
            error = (host.get("error") or "").lower()
            environment_interruption = ("foreground" in error or "session locked" in error or "session is locked" in error or
                (host.get("all_requested_scenes_passed") and not host.get("measurement_environment_stable")))
            if not environment_interruption:
                raise RuntimeError(f"{run_id} requires diagnosis; no scene is skipped")
        raise RuntimeError(f"{label}: repeated environment interruption; evidence retained")

    try:
        # Pilots validate new contracts and restored playback before long captures.
        if not args.full_captures_only:
            run("playing-pilot", "playing", [], 1, False)
            run("strict-pilot", "browse", STRICT_PILOT, 1, False)
        accepted = [reused[label] if label in reused else run(label, "browse", scenes, 3, True)
                    for label, scenes in GROUPS.items()]
        accepted.append(run("playing", "playing", [], 3, True))
        result = summarize(accepted)
        write_json(directory / "acceptance.json", result)
        state.update(status="coverage_complete" if result["all_40_scenes_three_trials_with_trace"] else "incomplete",
                     accepted_runs=[str(p) for p in accepted], errors=result["errors"])
        write_json(state_path, state)
        return 0 if state["status"] == "coverage_complete" else 1
    except BaseException as error:
        state.update(status="requires_diagnosis", error=f"{type(error).__name__}: {error}")
        write_json(state_path, state)
        print(json.dumps({"status": state["status"], "error": state["error"]}), flush=True)
        raise


if __name__ == "__main__":
    def interrupted(_signal, _frame):
        raise KeyboardInterrupt("Acceptance cycle cancelled; cleaning up its owned collector")
    signal.signal(signal.SIGTERM, interrupted)
    raise SystemExit(main())
