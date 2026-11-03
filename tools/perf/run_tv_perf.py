#!/usr/bin/env python3
"""Run automated rendering surveys against the already installed TV APK.

Only the self-instrumenting test APK is installed. No connectedAndroidTest,
target reinstall, compilation reset or app-data clear. Browse is read-only;
the explicit playing suite resumes/seeks the existing track and restores pause.
"""
from __future__ import annotations

import argparse
import datetime as dt
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import signal
import subprocess
import sys
import time
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[2]
TARGET = "dev.amatv"
TEST = "dev.amatv.benchmark"
COMPONENT = TEST + "/androidx.test.runner.AndroidJUnitRunner"
CASES = {"browse": TEST + ".RenderingSurveyTest#surveyInstalledStandalone",
         "playing": TEST + ".PlayingRenderingSurveyTest#surveyInstalledPlayback"}
SCENE_IDS = {
    "browse": set("home_idle home_horizontal home_vertical browse_idle browse_horizontal browse_vertical home_square_horizontal sidebar_focus sidebar_open_close sidebar_page_browse sidebar_page_library sidebar_page_search search_type_switch search_results_songs search_results_albums library_categories library_grid album_right_down album_right_up playlist_right_down playlist_right_up artist_newjeans_sections artist_newjeans_horizontal context_menu_open_close context_menu_focus paused_player_controls paused_existing_queue paused_player_idle settings_focus album_back_to_source playlist_back_to_source artist_back_to_search artist_left_sidebar sidebar_back_home_focus paused_player_back_to_home context_menu_long_press context_menu_ellipsis".split()),
    "playing": {"playing_lyrics", "playing_controls", "playing_queue_cursor"},
}


def write_json(path: Path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def requested_scenes_passed(scenes, expected_ids, iterations):
    actual_ids = [scene.get("scene_id") for scene in scenes]
    return (set(actual_ids) == set(expected_ids) and len(actual_ids) == len(expected_ids)
            and all(scene.get("status") == "ok" and len(scene.get("trials", [])) == iterations
                    and {trial.get("trial") for trial in scene.get("trials", [])} == set(range(1, iterations + 1))
                    and all(trial.get("status") == "ok" for trial in scene.get("trials", []))
                    for scene in scenes))


def command(args, *, timeout=30, data=None, check=True):
    argv = [str(v) for v in args]
    process = subprocess.Popen(argv, stdin=subprocess.PIPE if data is not None else subprocess.DEVNULL,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE, cwd=ROOT,
                               start_new_session=True)
    try:
        stdout, stderr = process.communicate(input=data, timeout=timeout)
    except (subprocess.TimeoutExpired, KeyboardInterrupt):
        # The offline analyzer launches a native child. Stop the owned process
        # group too, so a timeout cannot leave heavy parsing beside a new survey.
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        try:
            process.communicate(timeout=5)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.communicate()
        raise
    result = subprocess.CompletedProcess(argv, process.returncode, stdout, stderr)
    if check and result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}): {args[0]} "
                           + result.stderr.decode(errors="replace")[-1600:])
    return result


class Device:
    def __init__(self, adb: Path, serial: str):
        self.prefix = [str(adb), "-s", serial]

    def adb(self, *args, **kwargs):
        return command(self.prefix + list(args), **kwargs)

    def shell(self, *args, **kwargs):
        # adb shell joins arguments; quote once for the remote shell.
        return self.adb("shell", " ".join(shlex.quote(str(a)) for a in args), **kwargs).stdout.decode(errors="replace").strip()

    def installed_hash(self):
        paths = self.shell("pm", "path", TARGET).splitlines()
        base = next((p.removeprefix("package:") for p in paths if p.endswith("/base.apk")), None)
        if not base or not base.startswith("/data/app/"):
            raise RuntimeError("Installed AMATV base.apk was not found")
        digest = self.shell("sha256sum", base).split()[0]
        if not re.fullmatch(r"[a-f0-9]{64}", digest):
            raise RuntimeError("Cannot verify the installed APK identity")
        return digest


def trace_config(seconds: int, sources: set[str], max_bytes: int = 4 * 1024 ** 3):
    blocks = [f"""duration_ms: {seconds * 1000}
buffers {{ size_kb: 65536 fill_policy: RING_BUFFER }}
buffers {{ size_kb: 8192 fill_policy: RING_BUFFER }}
write_into_file: true
file_write_period_ms: 1000
flush_period_ms: 10000
max_file_size_bytes: {max_bytes}
flush_timeout_ms: 10000
data_sources {{ config {{ name: "linux.ftrace" ftrace_config {{
  compact_sched {{ enabled: true }}
  ftrace_events: "sched/sched_switch"
  ftrace_events: "sched/sched_waking"
  ftrace_events: "sched/sched_process_exit"
  ftrace_events: "sched/sched_process_free"
  ftrace_events: "task/task_newtask"
  ftrace_events: "task/task_rename"
  ftrace_events: "ftrace/print"
  atrace_categories: "gfx"
  atrace_categories: "view"
  atrace_categories: "input"
  atrace_categories: "binder_driver"
  atrace_categories: "hal"
  atrace_apps: "{TARGET}"
  atrace_apps: "{TEST}"
}} }} }}
data_sources {{ config {{ name: "linux.process_stats" target_buffer: 1 process_stats_config {{
  scan_all_processes_on_start: true
  proc_stats_poll_ms: 1000
}} }} }}
"""]
    for source in ("android.surfaceflinger.frametimeline", "android.gpu.memory"):
        if source in sources:
            blocks.append('data_sources { config { name: "' + source + '" target_buffer: 1 } }\n')
    return "\n".join(blocks)


def verify_self_instrumenting(sdk: Path, apk: Path):
    candidates = sorted((sdk / "build-tools").glob("*/aapt2"), reverse=True)
    if not candidates:
        raise RuntimeError("aapt2 is required to verify test-APK target ownership")
    xml = command([candidates[0], "dump", "xmltree", apk, "--file", "AndroidManifest.xml"]).stdout.decode()
    packages = re.findall(r'targetPackage[^\n]*?="([^"]+)"', xml)
    if packages != [TEST] or not re.search(r'\bpackage="' + re.escape(TEST) + '"', xml):
        raise RuntimeError("Refusing a test APK that instruments AMATV instead of itself")


def stop_owned_trace(device: Device, pid: int | None, remote: str):
    if pid is None:
        return
    cmdline = device.shell("cat", f"/proc/{pid}/cmdline", check=False)
    if "perfetto" not in cmdline or remote not in cmdline:
        return
    # Android's installed perfetto --help specifies TERM for graceful flush.
    device.shell("kill", "-TERM", str(pid), check=False)
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        stat = device.shell("cat", f"/proc/{pid}/stat", check=False)
        if not stat or ") Z " in stat:
            return
        time.sleep(.3)
    raise RuntimeError("Owned Perfetto did not finish; refusing an incomplete pull")


def host_sample(helper: Path | None = None, qemu_pid: int | None = None):
    ps = command(["ps", "-axo", "pid,pri,nice,pcpu,state,comm"], check=False).stdout.decode()
    rows = [line.strip() for line in ps.splitlines() if "qemu-system-aarch64" in line]
    sample = {"wall_time": time.time(), "monotonic_ns": time.monotonic_ns(), "qemu_processes": rows}
    if helper is not None and qemu_pid is not None:
        result = command([helper, str(qemu_pid)], timeout=5, check=False)
        if result.returncode == 0:
            sample["visibility"] = json.loads(result.stdout)
    return sample


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--avd", default="AMATV_Promo_TV")
    parser.add_argument("--iterations", type=int, default=3)
    parser.add_argument("--suite", choices=tuple(CASES), default="browse",
                        help="Browse is read-only; playing temporarily resumes the existing paused track and pauses in cleanup")
    parser.add_argument("--render-mode", choices=("visible", "headless"), default="visible",
                        help="Headless requires explicit -no-window -gpu host; host presentation metrics cannot be pooled with visible runs")
    parser.add_argument("--restore-position-ms", type=int,
                        help="Playing cleanup only: restore a previously observed task cursor instead of this run's initial cursor")
    parser.add_argument("--scenes", default="")
    parser.add_argument("--timeout", type=int, default=1800)
    parser.add_argument("--run-id")
    parser.add_argument("--expected-sha256")
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--no-trace", action="store_true")
    parser.add_argument("--keep-host-focus", action="store_true", help="Do not activate the emulator before measuring")
    parser.add_argument("--trace-processor", type=Path, default=ROOT / "work/apple-tv-ui/render-audit/perfetto/trace_processor")
    args = parser.parse_args()
    if args.restore_position_ms is not None and (args.suite != "playing" or args.restore_position_ms < 0):
        parser.error("A nonnegative restore position applies only to the playing suite")
    if args.serial != "emulator-5554" or args.avd != "AMATV_Promo_TV":
        parser.error("This survey is scoped to the retained AMATV_Promo_TV emulator only")
    if args.iterations not in (1, 3) or not 60 <= args.timeout <= 3600:
        parser.error("iterations must be 1 (pilot) or 3; timeout must be 60..3600")
    if args.scenes and not set(args.scenes.split(",")) <= SCENE_IDS[args.suite]:
        parser.error("Unknown scene for the selected suite")
    if args.scenes and not re.fullmatch(r"[a-z0-9_,]+", args.scenes):
        parser.error("scene IDs may contain lowercase letters, digits, underscores and commas")
    stamp = dt.datetime.now(ZoneInfo("Asia/Taipei")).strftime("%Y%m%d-%H%M%S")
    run_id = args.run_id or f"{stamp}-{os.getpid()}"
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", run_id):
        parser.error("Invalid run ID")
    run_dir = ROOT / "work/performance" / run_id
    run_dir.mkdir(parents=True, exist_ok=False)
    sdk = Path(os.environ.get("ANDROID_SDK_ROOT", str(Path.home() / "Library/Android/sdk")))
    device = Device(sdk / "platform-tools/adb", args.serial)
    remote_trace = f"/data/misc/perfetto-traces/amatv-survey-{run_id}.pftrace"
    remote_results = f"/sdcard/Android/data/{TEST}/files/rendering-survey/{run_id}"
    metadata = {"schema_version": 1, "run_id": run_id, "serial": args.serial,
                "avd": args.avd, "package": TARGET, "test_package": TEST,
                "runner_pid": os.getpid(), "remote_trace": remote_trace,
                "remote_results": remote_results, "status": "preflight",
                "trace_enabled": not args.no_trace, "iterations": args.iterations, "suite": args.suite,
                "render_mode": args.render_mode,
                "cleanup_position_ms": args.restore_position_ms,
                "tool_source_sha256": {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in
                    [Path(__file__).resolve(), ROOT / "tools/perf/analyze_tv_perf.py", ROOT / "tools/perf/host_state.swift",
                     ROOT / "benchmark/src/main/java/dev/amatv/benchmark/RenderingSurveyTest.kt",
                     ROOT / "benchmark/src/main/java/dev/amatv/benchmark/RenderingSurveyUi.kt",
                     ROOT / "benchmark/src/main/java/dev/amatv/benchmark/PlayingRenderingSurveyTest.kt",
                     ROOT / "tools/perf/trace_health_scope.py"]}}
    lock = (ROOT / "work/performance/.device.lock").open("a+")
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    metadata_path = run_dir / "host.json"
    write_json(metadata_path, metadata)
    trace_pid = None
    test_process = None
    test_started = False
    test_output = None
    helper = None
    qemu_pid = None
    failure = None
    print(json.dumps({"run_dir": str(run_dir), "status": "preflight"}), flush=True)
    try:
        avd = device.adb("emu", "avd", "name").stdout.decode().splitlines()[0].strip()
        if avd != args.avd:
            raise RuntimeError(f"Unexpected AVD identity: {avd}")
        metadata["candidate_sha256"] = device.installed_hash()
        metadata["host_free_bytes"] = shutil.disk_usage(run_dir).free
        if metadata["host_free_bytes"] < (1 if args.no_trace else 5) * 1024 ** 3:
            raise RuntimeError("Insufficient space: traced runs need at least 5 GiB free (1 GiB without trace); nothing is deleted automatically")
        if args.expected_sha256 and args.expected_sha256 != metadata["candidate_sha256"]:
            raise RuntimeError("Installed APK differs from the requested candidate")
        if device.shell("pidof", "screenrecord", check=False):
            raise RuntimeError("Screen recording is active; stop it before measuring")
        metadata["animations"] = {key: device.shell("settings", "get", "global", key) for key in
                                  ("window_animation_scale", "transition_animation_scale", "animator_duration_scale")}
        metadata["device"] = {key: device.shell("getprop", key) for key in
                              ("ro.build.version.sdk", "ro.build.fingerprint", "ro.kernel.qemu.avd_name", "ro.hardware.egl")}
        metadata["display_size"] = device.shell("wm", "size")
        metadata["display_density"] = device.shell("wm", "density")
        surfaceflinger = device.shell("dumpsys", "SurfaceFlinger")
        (run_dir / "surfaceflinger.txt").write_text(surfaceflinger)
        renderer = [line.strip() for line in surfaceflinger.splitlines() if re.match(r"\s*(GLES|GL_RENDERER):", line)]
        metadata["renderer_identity"] = renderer
        metadata["host_gpu_renderer_verified"] = bool(renderer) and not re.search(
            r"swiftshader|llvmpipe|lavapipe|software rasterizer", " ".join(renderer), re.I)
        (run_dir / "gfxinfo-before.txt").write_text(device.shell("dumpsys", "gfxinfo", TARGET))
        metadata["device_df_data"] = device.shell("df", "-k", "/data")
        free_fields = metadata["device_df_data"].splitlines()[-1].split()
        metadata["device_data_free_bytes"] = int(free_fields[3]) * 1024
        metadata["trace_file_cap_bytes"] = min(4 * 1024 ** 3, metadata["device_data_free_bytes"] - 512 * 1024 ** 2)
        if not args.no_trace and metadata["trace_file_cap_bytes"] < 512 * 1024 ** 2:
            raise RuntimeError("Need at least 1 GiB available on emulator /data; no app data is deleted")
        metadata["boot_completed"] = device.shell("getprop", "sys.boot_completed")
        if metadata["boot_completed"] != "1":
            raise RuntimeError("Emulator is not fully booted")
        query = device.shell("perfetto", "--query")
        (run_dir / "perfetto-sources.txt").write_text(query)
        sources = {line.split()[0] for line in query.splitlines() if line and
                   re.match(r"^(?:android\.|linux\.|gpu\.)", line)}
        metadata["trace_sources"] = sorted(sources)
        metadata["hardware_gpu_timing_available"] = bool(sources & {"gpu.renderstages", "gpu.counters"})
        if not args.no_trace and not {"linux.ftrace", "linux.process_stats", "android.surfaceflinger.frametimeline"} <= sources:
            raise RuntimeError("Required trace sources unavailable; use an explicitly labelled --no-trace run")
        if not args.skip_build:
            with (run_dir / "build.log").open("wb") as output:
                result = subprocess.run(["./gradlew", ":benchmark:assembleBenchmark"], cwd=ROOT,
                                        stdout=output, stderr=subprocess.STDOUT, timeout=600)
            if result.returncode:
                raise RuntimeError("Survey APK build failed; see build.log")
        test_apk = ROOT / "benchmark/build/outputs/apk/benchmark/benchmark-benchmark.apk"
        verify_self_instrumenting(sdk, test_apk)
        metadata["test_apk_sha256"] = hashlib.sha256(test_apk.read_bytes()).hexdigest()
        device.adb("install", "-r", str(test_apk), timeout=90)
        instrumentation = device.shell("pm", "list", "instrumentation")
        if f"instrumentation:{COMPONENT} (target={TEST})" not in instrumentation:
            raise RuntimeError("Installed test runner does not target itself")
        helper = None
        qemu_pid = None
        processes = command(["ps", "-axo", "pid,args"]).stdout.decode()
        matches = [line for line in processes.splitlines() if "qemu-system-aarch64" in line and
                   re.search(r"-avd\s+" + re.escape(args.avd) + r"(?:\s|$)", line)]
        headless_verified = (len(matches) == 1 and bool(re.search(r"(?:^|\s)-no-window(?:\s|$)", matches[0]))
                             and bool(re.search(r"(?:^|\s)-gpu\s+host(?:\s|$)", matches[0])))
        metadata["headless_arguments_verified"] = headless_verified
        if args.render_mode == "headless" and not (headless_verified and metadata["host_gpu_renderer_verified"]):
            raise RuntimeError("Headless measurement requires one -no-window -gpu host emulator and a verified non-software renderer")
        if args.render_mode == "visible" and headless_verified:
            raise RuntimeError("Headless process cannot be measured as a visible-window run")
        if sys.platform == "darwin" and len(matches) == 1:
            # Scoped to this collector PID, including offline analysis. These
            # assertions end automatically; no sleep/lock preference changes.
            awake_flags = ["-i"] + (["-d"] if args.render_mode == "visible" else [])
            awake = subprocess.Popen(["/usr/bin/caffeinate", *awake_flags, "-w", str(os.getpid())],
                                     stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            metadata["host_sleep_assertion"] = {"pid": awake.pid, "lifetime_pid": os.getpid(),
                                                "prevent_display_sleep": args.render_mode == "visible", "prevent_idle_sleep": True}
            qemu_pid = int(matches[0].split()[0])
            binary = run_dir / "host_state"
            built = command(["xcrun", "swiftc", "-O", ROOT / "tools/perf/host_state.swift", "-o", binary],
                            timeout=90, check=False)
            (run_dir / "host-helper-build.log").write_bytes(built.stdout + built.stderr)
            if built.returncode == 0:
                helper = binary
                activation = [binary, str(qemu_pid)] + (["--activate"] if args.render_mode == "visible" and not args.keep_host_focus else [])
                metadata["host_visibility_before"] = json.loads(command(activation).stdout)
                time.sleep(.5)
                metadata["host_visibility_after"] = host_sample(helper, qemu_pid).get("visibility")
                metadata["host_focus_recoveries"] = []
                if args.render_mode == "visible" and metadata["host_visibility_after"].get("frontmost_is_loginwindow"):
                    raise RuntimeError("macOS session is locked; foreground rendering measurement cannot start")
                if args.render_mode == "visible" and not args.keep_host_focus and not metadata["host_visibility_after"].get("target_frontmost"):
                    raise RuntimeError("Cannot establish emulator foreground; no measurement started")
        if not args.no_trace:
            config = trace_config(args.timeout, sources, metadata["trace_file_cap_bytes"])
            (run_dir / "trace-config.pbtxt").write_text(config)
            started = device.adb("shell", "perfetto --background-wait --txt -c - -o " + shlex.quote(remote_trace),
                                 data=config.encode(), timeout=40)
            text = started.stdout.decode(errors="replace")
            (run_dir / "trace-start.txt").write_text(text + started.stderr.decode(errors="replace"))
            pids = re.findall(r"(?m)^\s*(\d+)\s*$", text)
            if not pids:
                raise RuntimeError("Perfetto did not acknowledge an owned process ID")
            trace_pid = int(pids[-1])
            metadata["trace_pid"] = trace_pid
        metadata["status"] = "running"
        metadata["started_wall"] = time.time()
        write_json(metadata_path, metadata)
        params = ["am", "instrument", "-w", "-r", "-e", "class", CASES[args.suite],
                  "-e", "run_id", run_id, "-e", "run_dir", "rendering-survey/" + run_id,
                  "-e", "candidate_sha256", metadata["candidate_sha256"]]
        if args.iterations == 1: params += ["-e", "initial_pilot", "1"]
        if args.scenes: params += ["-e", "scenes", args.scenes]
        if args.restore_position_ms is not None:
            params += ["-e", "cleanup_position_ms", str(args.restore_position_ms)]
        params += [COMPONENT]
        test_output = (run_dir / "instrumentation.log").open("wb")
        test_process = subprocess.Popen(device.prefix + ["shell", " ".join(shlex.quote(v) for v in params)],
                                        stdout=test_output, stderr=subprocess.STDOUT, cwd=ROOT)
        test_started = True
        deadline = time.monotonic() + args.timeout
        log_offset = 0
        with (run_dir / "host-samples.jsonl").open("w", buffering=1) as monitor:
            while test_process.poll() is None:
                if time.monotonic() >= deadline:
                    raise TimeoutError("Survey exceeded its bounded run time")
                sample = host_sample(helper, qemu_pid)
                monitor.write(json.dumps(sample) + "\n")
                if args.render_mode == "visible" and sample.get("visibility", {}).get("frontmost_is_loginwindow"):
                    raise RuntimeError("macOS session locked during collection; repeat after unlock")
                if args.render_mode == "visible" and helper is not None and qemu_pid is not None and not args.keep_host_focus and not sample.get("visibility", {}).get("target_frontmost"):
                    recovery = json.loads(command([helper, str(qemu_pid), "--activate"], timeout=5).stdout)
                    recovery["interrupted_sample_wall_time"] = sample["wall_time"]
                    metadata["host_focus_recoveries"].append(recovery)
                    write_json(metadata_path, metadata)
                    if not recovery.get("target_frontmost"):
                        raise RuntimeError("Emulator foreground ownership lost during collection")
                with (run_dir / "instrumentation.log").open() as progress:
                    progress.seek(log_offset)
                    for line in progress:
                        if "SURVEY_" in line: print(line.strip(), flush=True)
                    log_offset = progress.tell()
                time.sleep(5)
        metadata["instrumentation_exit"] = test_process.returncode
        if not args.no_trace:
            # Stats snapshots are periodic. Keep an unmeasured tail so even
            # the last trial can be bracketed by an after-window service sample.
            metadata["trace_postroll_seconds"] = 12
            write_json(metadata_path, metadata)
            time.sleep(12)
    except BaseException as error:
        failure = f"{type(error).__name__}: {error}"
    finally:
        metadata["cleanup_errors"] = []
        if test_started and (test_process is None or test_process.poll() is None):
            if args.suite == "playing" and test_process is not None:
                try:
                    # Let the current <=6s body finish, then the tester pauses
                    # and restores its owned track before exiting. Force-stop
                    # alone would kill instrumentation without running finally.
                    device.shell("touch", remote_results + "/cancel-request", check=False)
                    cancel_deadline = time.monotonic() + 45
                    while test_process.poll() is None and time.monotonic() < cancel_deadline:
                        time.sleep(.25)
                    metadata["cooperative_playback_cleanup_exited"] = test_process.poll() is not None
                    if test_process.poll() is None:
                        metadata["cleanup_errors"].append("Playing tester did not exit after cooperative cancellation; playback cleanup requires verification")
                except Exception as error:
                    metadata["cleanup_errors"].append("playback cancellation: " + str(error))
        if test_started and (test_process is None or test_process.poll() is None):
            try:
                # Stop only an unfinished owned tester; a completed instrumentation
                # has already exited and needs no force-stop during trace teardown.
                # Never stop the target package.
                device.shell("am", "force-stop", TEST, check=False)
            except Exception as error:
                metadata["cleanup_errors"].append("test stop: " + str(error))
        if test_process is not None and test_process.poll() is None:
            test_process.terminate()
            try: test_process.wait(timeout=10)
            except subprocess.TimeoutExpired: test_process.kill()
        if test_output is not None:
            test_output.close()
        if trace_pid is None and not args.no_trace:
            # A timed-out startup acknowledgement may still have created the
            # unique owned trace. Locate by its exact output argument only.
            try:
                processes = device.shell("ps", "-A", "-o", "PID,ARGS", check=False)
                owned = [line for line in processes.splitlines() if remote_trace in line and "perfetto" in line]
                if len(owned) == 1: trace_pid = int(owned[0].split()[0])
            except Exception as error:
                metadata["cleanup_errors"].append("trace ownership lookup: " + str(error))
        if trace_pid is not None:
            try:
                stop_owned_trace(device, trace_pid, remote_trace)
                device.adb("pull", remote_trace, str(run_dir / "survey.pftrace"), timeout=120)
                device.shell("rm", "-f", remote_trace, check=False)
            except Exception as error:
                metadata["trace_error"] = str(error)
        if test_started:
            try:
                pulled = device.adb("pull", remote_results + "/.", str(run_dir), check=False, timeout=90)
                metadata["results_pull_exit"] = pulled.returncode
                if pulled.returncode == 0:
                    device.shell("rm", "-rf", remote_results, check=False)
            except Exception as error:
                metadata["cleanup_errors"].append("result pull: " + str(error))
        try:
            metadata["final_candidate_sha256"] = device.installed_hash()
            metadata["candidate_unchanged"] = metadata["final_candidate_sha256"] == metadata.get("candidate_sha256")
            metadata["foreground"] = device.shell("dumpsys", "activity", "activities").split("topResumedActivity=")[-1].splitlines()[0]
            crash = device.shell("logcat", "-b", "crash", "-d")
            (run_dir / "crash-buffer.txt").write_text(crash)
            metadata["target_fatal_in_crash_buffer"] = TARGET in crash and "FATAL EXCEPTION" in crash
        except Exception as error:
            metadata["final_read_error"] = str(error)
        metadata["finished_wall"] = time.time()
        metadata["error"] = failure
        metadata["status"] = "failed" if failure else "collected"
        write_json(metadata_path, metadata)
        lock.close()
    manifest = run_dir / "manifest.json"
    if manifest.exists():
        value = json.loads(manifest.read_text())
        value["host_metadata"] = "host.json"
        if (run_dir / "survey.pftrace").exists(): value["trace"] = "survey.pftrace"
        write_json(manifest, value)
        analyzer = ROOT / "tools/perf/analyze_tv_perf.py"
        if analyzer.exists() and not (failure or "").startswith("KeyboardInterrupt:"):
            analysis = [sys.executable, str(analyzer), "--ledger", str(manifest),
                        "--output", str(run_dir / "report.json"), "--csv", str(run_dir / "metrics.csv"),
                        "--frames-csv", str(run_dir / "frames.csv"), "--markdown", str(run_dir / "report.md"), "--full-sort"]
            if args.trace_processor.exists(): analysis += ["--trace-processor", str(args.trace_processor)]
            parsed = command(analysis, timeout=900, check=False)
            (run_dir / "analysis.log").write_bytes(parsed.stdout + parsed.stderr)
            metadata["analysis_exit"] = parsed.returncode
        elif failure:
            metadata["analysis_skipped_reason"] = "interrupted; raw artifacts retained"
    log = (run_dir / "instrumentation.log").read_text(errors="replace") if (run_dir / "instrumentation.log").exists() else ""
    scene_statuses = [s.get("status") for s in value.get("scenes", [])] if manifest.exists() else []
    expected_ids = set(args.scenes.split(",")) if args.scenes else SCENE_IDS[args.suite]
    measured_scenes = value.get("scenes", []) if manifest.exists() else []
    actual_ids = [s.get("scene_id") for s in measured_scenes]
    metadata["expected_scene_ids"] = sorted(expected_ids)
    metadata["missing_scene_ids"] = sorted(expected_ids - set(actual_ids))
    metadata["all_requested_scenes_passed"] = requested_scenes_passed(measured_scenes, expected_ids, args.iterations)
    metadata["scene_status_counts"] = {status: scene_statuses.count(status) for status in set(scene_statuses)}
    completed = ("OK (1 test)" in log and manifest.exists() and metadata.get("candidate_unchanged") is True
                 and "failed" not in scene_statuses and "ok" in scene_statuses
                 and not metadata.get("trace_error") and not metadata.get("cleanup_errors")
                 and metadata.get("analysis_exit") in (None, 0))
    metadata["host_focus_controlled"] = not args.keep_host_focus and bool(metadata.get("host_visibility_after", {}).get("target_frontmost"))
    metadata["measurement_environment_stable"] = (
        bool(metadata.get("headless_arguments_verified") and metadata.get("host_gpu_renderer_verified"))
        if args.render_mode == "headless" else
        metadata["host_focus_controlled"] and not metadata.get("host_focus_recoveries"))
    metadata["status"] = ("complete" if metadata["all_requested_scenes_passed"] and metadata["measurement_environment_stable"] else "incomplete") if completed and not failure else "failed"
    write_json(metadata_path, metadata)
    print(json.dumps({"status": metadata["status"], "run_dir": str(run_dir), "error": failure}), flush=True)
    return 0 if metadata["status"] == "complete" else 1


if __name__ == "__main__":
    def interrupted(_signal, _frame):
        raise KeyboardInterrupt("Survey interrupted; stopping only owned test/trace processes")
    signal.signal(signal.SIGTERM, interrupted)
    raise SystemExit(main())
