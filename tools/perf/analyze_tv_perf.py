#!/usr/bin/env python3
"""Offline, clock-scoped Android TV frame analysis; never controls a device."""
from __future__ import annotations

import argparse
import csv
import hashlib
import importlib.util
import io
import json
import math
from pathlib import Path
import re
import shutil
import subprocess
import sys
from collections import Counter, defaultdict

_HEALTH_SPEC = importlib.util.spec_from_file_location("tv_trace_health_scope", Path(__file__).with_name("trace_health_scope.py"))
trace_health_scope = importlib.util.module_from_spec(_HEALTH_SPEC)
_HEALTH_SPEC.loader.exec_module(trace_health_scope)

NS_PER_MS = 1_000_000
MAX_TS = (1 << 63) - 1
QUANTILES = (50, 90, 95, 99)
PHASES = {
    "frame_total_wall": ("FrameCompleted", "IntendedVsync"),
    "ui_wall": ("SyncQueued", "HandleInputStart"),
    "input_wall": ("AnimationStart", "HandleInputStart"),
    "animation_wall": ("PerformTraversalsStart", "AnimationStart"),
    "layout_measure_wall": ("DrawStart", "PerformTraversalsStart"),
    "draw_record_wall": ("SyncQueued", "DrawStart"),
    "rt_queue_wait_wall": ("SyncStart", "SyncQueued"),
    "rt_sync_wall": ("IssueDrawCommandsStart", "SyncStart"),
    "rt_command_issue_wall": ("SwapBuffers", "IssueDrawCommandsStart"),
    "swap_wall": ("SwapBuffersCompleted", "SwapBuffers"),
    "completion_after_submit_wall": ("FrameCompleted", "CommandSubmissionCompleted"),
    "gpu_completion_after_submit_proxy": ("GpuCompleted", "CommandSubmissionCompleted"),
    "gpu_completion_after_swap_proxy": ("GpuCompleted", "SwapBuffers"),
}
DIRECT_DURATIONS = {"surface_dequeue_wall": "DequeueBufferDuration",
                    "surface_queue_buffer_wall": "QueueBufferDuration"}
FRAME_TIMELINE_FLAG_LIMITATION = "Raw FrameTimeline jank flags include Prediction Error. Prediction Error alone is not a dropped-frame or perceived-stutter count; late presentation and app finish timing are reported separately."
LIMITATIONS = [
    "Wall intervals overlap and must not be summed as mutually exclusive CPU/GPU costs.",
    "GpuCompleted is a completion/fence timestamp. Both GPU completion deltas are proxies, not isolated GPU execution or shader time.",
    "GpuTaskCompleted, when present, remains a raw unknown field; it is never silently aliased to GpuCompleted.",
    "FrameTimeline actual duration includes app CPU, GPU completion and posting; gpu_composition is a boolean, not a GPU duration.",
    "dequeue/swap/fence wall time can be buffer backpressure, sleeping, scheduling or driver work; it is not pure GPU drawing.",
    "No GPU render-stage samples means unavailable/unobserved GPU execution data, not zero GPU work.",
    "gfxinfo excludes nonzero Flags from phase/deadline statistics. Its printed aggregate jank percentage is not reused as a scene percentage.",
    "FrameTimeline is reported per surface layer. A process can produce multiple surface frames for one display token.",
    FRAME_TIMELINE_FLAG_LIMITATION,
    "Repeated trials are summarized as distributions of trial quantiles, never pooled into one frame population.",
]


def distribution(values):
    values = sorted(x for x in values if x is not None and math.isfinite(x))
    if not values:
        return {"n": 0, **{f"p{p}": None for p in QUANTILES}, "max": None,
                "mean": None, "sum": None}
    def percentile(p):
        at = (len(values) - 1) * p / 100
        low = math.floor(at)
        return values[low] + (values[math.ceil(at)] - values[low]) * (at - low)
    return {"n": len(values), **{f"p{p}": round(percentile(p), 6) for p in QUANTILES},
            "max": round(values[-1], 6), "mean": round(sum(values) / len(values), 6),
            "sum": round(sum(values), 6)}


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def flatten_ledger(document):
    trials = []
    for scene in document.get("scenes", []):
        if "trials" in scene:
            base = {key: value for key, value in scene.items() if key != "trials"}
            if not scene["trials"]:
                trials.append({**base, "trial": None})
            for index, trial in enumerate(scene["trials"], 1):
                trials.append({**base, "trial": index, **trial})
        else:
            trials.append({"trial": None if scene.get("status") == "skip" else 1, **scene})
    return trials


def read_ledger(path):
    text = Path(path).read_text()
    try:
        document = json.loads(text)
    except json.JSONDecodeError:
        # A header followed by flat trial records supports interrupted runs.
        document = {"scenes": []}
        lines = [line for line in text.splitlines() if line.strip()]
        for index, line in enumerate(lines):
            if not line.strip():
                continue
            try:
                entry = json.loads(line)
            except json.JSONDecodeError:
                if index != len(lines) - 1:
                    raise
                document["incomplete_last_jsonl_record"] = True
                break
            if "scene_id" in entry:
                document["scenes"].append(entry)
            else:
                document.update(entry)
    if not isinstance(document, dict) or document.get("schema_version") != 1:
        raise ValueError("Expected schema_version=1 JSON/JSONL ledger")
    return document, flatten_ledger(document)


def parse_gfxinfo(path):
    text = Path(path).read_text(errors="replace")
    rows, malformed = [], 0
    window, headers, in_block = "unknown", None, False
    summary = {}
    for line in text.splitlines():
        if line.startswith("Window:"):
            window = line.split(":", 1)[1].strip()
        match = re.match(r"Stats since: (\d+)ns", line)
        if match and "stats_since_ns" not in summary:
            summary["stats_since_ns"] = int(match[1])
        match = re.match(r"Total frames rendered: (\d+)", line)
        if match and "aggregate_total_frames" not in summary:
            summary["aggregate_total_frames"] = int(match[1])
        match = re.match(r"Janky frames: (\d+) \(([\d.]+)%\)", line)
        if match and "aggregate_janky_frames" not in summary:
            summary.update(aggregate_janky_frames=int(match[1]), aggregate_jank_pct=float(match[2]))
        if line.strip() == "---PROFILEDATA---":
            in_block = not in_block
            headers = None
            continue
        if not in_block or not line.strip():
            continue
        cells = next(csv.reader([line]))
        if headers is None:
            headers = [cell.strip() for cell in cells]
            if "IntendedVsync" not in headers or "Flags" not in headers:
                raise ValueError("PROFILEDATA needs raw Flags and IntendedVsync labels")
            continue
        try:
            row = {key: int(value) for key, value in zip(headers, cells) if key and value.strip()}
            if not (0 < row["IntendedVsync"] < MAX_TS):
                raise ValueError("invalid IntendedVsync")
        except (ValueError, KeyError):
            malformed += 1
            continue
        rows.append({"window": window, "raw": row})
    seen, unique = set(), []
    for row in rows:
        identity = (row["window"], tuple(sorted(row["raw"].items())))
        if identity not in seen:
            seen.add(identity)
            unique.append(row)
    summary.update(raw_rows=len(unique), duplicate_rows=len(rows) - len(unique), malformed_rows=malformed)
    return unique, summary


def valid_timestamp(value):
    return isinstance(value, int) and 0 < value < MAX_TS


def frame_phases(raw):
    values, rejected = {}, []
    for metric, (end, start) in PHASES.items():
        a, b = raw.get(end), raw.get(start)
        if valid_timestamp(a) and valid_timestamp(b) and a >= b:
            values[metric] = (a - b) / NS_PER_MS
        else:
            rejected.append(metric)
    for metric, name in DIRECT_DURATIONS.items():
        value = raw.get(name)
        if isinstance(value, int) and 0 <= value < MAX_TS:
            values[metric] = value / NS_PER_MS
        else:
            rejected.append(metric)
    complete, deadline = raw.get("FrameCompleted"), raw.get("FrameDeadline")
    if valid_timestamp(complete) and valid_timestamp(deadline):
        values["deadline_lateness_wall"] = max(0, complete - deadline) / NS_PER_MS
    return values, rejected


def summarize_gfx(rows, source_summary, scene):
    start, end = scene.get("start_ns"), scene.get("end_ns")
    if (start is None) != (end is None) or (start is not None and start >= end):
        raise ValueError("start_ns/end_ns must form a positive MONOTONIC interval")
    if scene.get("clock", "monotonic").lower() != "monotonic":
        raise ValueError("gfxinfo bounds must use MONOTONIC, not host wall or BOOTTIME")
    pattern = re.compile(scene["window_regex"]) if scene.get("window_regex") else None
    selected = [row for row in rows if (start is None or start <= row["raw"]["IntendedVsync"] < end)
                and (pattern is None or pattern.search(row["window"]))]
    collected, rejected, normalized, warnings = defaultdict(list), Counter(), [], []
    flags = Counter(str(row["raw"].get("Flags")) for row in selected)
    swapped = False
    for row in selected:
        raw = row["raw"]
        phases, invalid = frame_phases(raw)
        eligible = raw.get("Flags") == 0
        if eligible:
            rejected.update(invalid)
            for metric, value in phases.items():
                collected[metric].append(value)
        interval = raw.get("FrameInterval", 0)
        if interval > 1_000_000_000 and 500_000 <= raw.get("FrameStartTime", 0) <= 1_000_000_000:
            swapped = True
        normalized.append({"window": row["window"], "eligible": eligible,
                           "raw": raw, "phases_ms": phases})
    retained_truncated = source_summary.get("aggregate_total_frames", 0) > source_summary["raw_rows"]
    if retained_truncated:
        warnings.append("Printed aggregate has more frames than retained PROFILEDATA: phase/deadline quantiles are a partial ring-buffer sample.")
    if swapped:
        warnings.append("Raw FrameInterval contains absolute timestamps while FrameStartTime contains interval-like values. Raw labels retained; no positional remapping or deadline inference used.")
    if len({row["window"] for row in selected}) > 1:
        warnings.append("Multiple windows: combined samples are window frames, not unique display vsyncs.")
    if not selected:
        warnings.append("No retained gfxinfo rows in this requested interval; absence is not proof of zero rendering.")
    deadlines = collected.get("deadline_lateness_wall", [])
    missed = sum(value > 0 for value in deadlines)
    report = {"source_summary_unscoped": source_summary, "flags": dict(flags),
              "sample_unit": "app_window_frame_not_display_vsync",
              "selected_rows": len(selected), "valid_flag_zero_rows": flags.get("0", 0),
              "metrics_ms": {key: distribution(collected[key]) for key in list(PHASES) + list(DIRECT_DURATIONS) + ["deadline_lateness_wall"]},
              "deadline": {"known_frames": len(deadlines), "missed_frames": missed,
                           "missed_pct": round(missed * 100 / len(deadlines), 4) if deadlines else None},
              "rejected_metric_samples": dict(rejected), "warnings": warnings,
              "coverage": {"raw_retention_truncated": retained_truncated,
                           "scope": "clock_window" if start is not None else "retained_rows_only",
                           "sample_start_ns": min((r["raw"]["IntendedVsync"] for r in selected), default=None),
                           "sample_end_ns": max((r["raw"].get("FrameCompleted", 0) for r in selected), default=None)}}
    windows = sorted({row["window"] for row in selected})
    if len(windows) > 1:
        report["windows"] = {window: summarize_gfx(rows, source_summary, {
            **scene, "window_regex": "^" + re.escape(window) + "$"})[0] for window in windows}
    return report, normalized


def sql_literal(value):
    return "'" + str(value).replace("'", "''") + "'"


def manifest_pids(scene):
    """None means a legacy ledger; an explicit empty list must fail closed."""
    if "app_pids" not in scene:
        return None
    values = scene["app_pids"]
    if not isinstance(values, list) or any(type(pid) is not int or not 0 < pid < 2 ** 31 for pid in values):
        raise ValueError("app_pids must be a list of positive integer device PIDs")
    return sorted(set(values))


def trace_query(scenes, package):
    rows, pids = [], []
    for index, scene in scenes:
        numbers = [scene.get(key) for key in ("start_ns", "end_ns", "trace_start_ns", "trace_end_ns")]
        owned = manifest_pids(scene)
        rows.append("(" + ",".join([str(index)] + [str(int(n)) if n is not None else "NULL" for n in numbers]
                                  + [str(int(owned is not None))]) + ")")
        pids.extend("(" + str(index) + "," + str(pid) + ")" for pid in (owned or []))
    pid_values = ",".join(pids) or "(NULL,NULL)"
    package = sql_literal(package)
    return f"""
WITH input_windows(scene_index,mono_start,mono_end,boot_start,boot_end,has_manifest_pids) AS (VALUES {','.join(rows)}),
input_pids(scene_index,pid) AS (VALUES {pid_values}),
windows AS (
 SELECT scene_index,has_manifest_pids,
  CASE (SELECT int_value FROM metadata WHERE name='trace_time_clock_id' LIMIT 1)
   WHEN 6 THEN boot_start WHEN 3 THEN mono_start END AS lo,
  CASE (SELECT int_value FROM metadata WHERE name='trace_time_clock_id' LIMIT 1)
   WHEN 6 THEN boot_end WHEN 3 THEN mono_end END AS hi
 FROM input_windows
), scoped_processes AS MATERIALIZED (
 SELECT w.scene_index,p.upid,p.pid,p.name
 FROM windows w JOIN process p ON (
  (w.has_manifest_pids=1 AND EXISTS(SELECT 1 FROM input_pids owned
    WHERE owned.scene_index=w.scene_index AND owned.pid=p.pid))
  OR (w.has_manifest_pids=0 AND p.name={package}))
 WHERE (p.start_ts IS NULL OR p.start_ts<w.hi) AND (p.end_ts IS NULL OR p.end_ts>w.lo)
), app_threads AS MATERIALIZED (
 SELECT p.scene_index,t.utid,t.tid,t.name AS thread,p.pid,
  CASE WHEN t.tid=p.pid THEN 'ui' WHEN t.name GLOB 'RenderThread*' THEN 'rt'
   WHEN t.name LIKE '%GPU completion%' THEN 'gpu_completion' ELSE 'other' END AS role
 FROM thread t JOIN scoped_processes p USING(upid)
), app_states AS MATERIALIZED (
 SELECT w.scene_index,h.utid,MAX(h.ts,w.lo) AS ts,
  MIN(h.ts+h.dur,w.hi)-MAX(h.ts,w.lo) AS dur,h.state
 FROM thread_state h JOIN app_threads t USING(utid)
 JOIN windows w ON t.scene_index=w.scene_index AND h.ts<w.hi AND h.ts+h.dur>w.lo
 WHERE h.dur>0
), classified AS MATERIALIZED (
 SELECT s.id,s.ts,s.dur,s.depth,t.*,s.name,
  CASE WHEN t.role='ui' AND s.name LIKE 'Choreographer#doFrame%' THEN 'ui_frame_wall'
   WHEN t.role='rt' AND s.name LIKE 'DrawFrame%' THEN 'rt_frame_wall'
   WHEN t.role='rt' AND LOWER(s.name) LIKE '%dequeue%' THEN 'rt_dequeue_wall'
   WHEN t.role='rt' AND LOWER(s.name) LIKE '%queuebuffer%' THEN 'rt_queue_buffer_wall'
   WHEN t.role='rt' AND LOWER(s.name) LIKE '%swapbuffer%' THEN 'rt_swap_wall'
   WHEN t.role IN ('rt','gpu_completion') AND (LOWER(s.name) LIKE '%fence%'
    OR LOWER(s.name) LIKE '%waitforever%' OR LOWER(s.name) LIKE '%sync_wait%'
    OR LOWER(s.name) LIKE '%waitforgpu%') THEN t.role || '_fence_wait_wall'
   WHEN t.role='rt' AND LOWER(s.name) LIKE '%preparetree%' THEN 'rt_prepare_tree_wall' END AS metric
 FROM slice s JOIN thread_track tt ON s.track_id=tt.id JOIN app_threads t USING(utid)
 JOIN windows w ON t.scene_index=w.scene_index AND s.ts<w.hi AND s.ts+s.dur>w.lo WHERE s.dur>0
), spans AS (
 SELECT c.*,w.lo,w.hi,MAX(c.ts,w.lo) AS begin_ns,MIN(c.ts+c.dur,w.hi) AS end_ns
 FROM classified c JOIN windows w ON c.scene_index=w.scene_index
  AND c.ts<w.hi AND c.ts+c.dur>w.lo WHERE c.metric IS NOT NULL
 AND NOT EXISTS (SELECT 1 FROM classified parent WHERE parent.scene_index=c.scene_index
  AND parent.utid=c.utid AND parent.metric=c.metric
  AND parent.depth<c.depth AND parent.ts<=c.ts AND parent.ts+parent.dur>=c.ts+c.dur)
), slice_states AS (
 SELECT s.*,SUM(CASE WHEN h.state='Running' THEN MIN(s.end_ns,h.ts+h.dur)-MAX(s.begin_ns,h.ts) ELSE 0 END) AS running,
 SUM(CASE WHEN h.state GLOB 'R*' AND h.state!='Running' THEN MIN(s.end_ns,h.ts+h.dur)-MAX(s.begin_ns,h.ts) ELSE 0 END) AS runnable,
 SUM(CASE WHEN h.state='S' THEN MIN(s.end_ns,h.ts+h.dur)-MAX(s.begin_ns,h.ts) ELSE 0 END) AS sleeping,
 SUM(CASE WHEN h.state GLOB 'D*' THEN MIN(s.end_ns,h.ts+h.dur)-MAX(s.begin_ns,h.ts) ELSE 0 END) AS uninterruptible,
 SUM(CASE WHEN h.utid IS NOT NULL THEN MIN(s.end_ns,h.ts+h.dur)-MAX(s.begin_ns,h.ts) ELSE 0 END) AS covered
 FROM spans s LEFT JOIN app_states h ON h.scene_index=s.scene_index AND h.utid=s.utid
  AND h.ts<s.end_ns AND h.ts+h.dur>s.begin_ns
 GROUP BY s.scene_index,s.id
)
SELECT 'capabilities' AS record_type,-1 AS scene_index,hex(json_object(
 'trace_start_ns',(SELECT start_ts FROM trace_bounds),'trace_end_ns',(SELECT end_ts FROM trace_bounds),
 'clock_id',(SELECT int_value FROM metadata WHERE name='trace_time_clock_id' LIMIT 1),
 'gpu_slice_rows',(SELECT COUNT(*) FROM gpu_slice),
 'frame_timeline_rows',(SELECT COUNT(*) FROM actual_frame_timeline_slice),
 'thread_state_rows',(SELECT COUNT(*) FROM thread_state))) AS payload_hex
UNION ALL SELECT 'stat',-1,hex(json_object('name',name,'severity',severity,'value',value)) FROM stats
 WHERE value!=0 AND (severity IN ('error','data_loss') OR name GLOB '*overwritten*'
  OR name GLOB '*discarded*' OR name GLOB '*overrun*' OR name GLOB '*dropped*')
UNION ALL SELECT 'attribution',w.scene_index,hex(json_object(
 'mode',CASE WHEN w.has_manifest_pids=1 THEN 'manifest_pid_scoped' ELSE 'legacy_process_name' END,
 'identified_processes',(SELECT COUNT(*) FROM scoped_processes p WHERE p.scene_index=w.scene_index),
 'identified_threads',(SELECT COUNT(*) FROM app_threads t WHERE t.scene_index=w.scene_index),
 'identified_ui_threads',(SELECT COUNT(*) FROM app_threads t WHERE t.scene_index=w.scene_index AND t.role='ui')))
 FROM windows w
UNION ALL SELECT 'attributed_process',scene_index,hex(json_object('upid',upid,'pid',pid,'name',name)) FROM scoped_processes
UNION ALL SELECT 'timeline',w.scene_index,hex(json_object('ts',f.ts,'dur',f.dur,'pid',p.pid,
 'layer',f.layer_name,'surface_token',f.surface_frame_token,'display_token',f.display_frame_token,
 'jank_type',f.jank_type,'on_time_finish',f.on_time_finish,'present_type',f.present_type,'gpu_composition',f.gpu_composition))
 FROM actual_frame_timeline_slice f JOIN scoped_processes p USING(upid)
 JOIN windows w ON p.scene_index=w.scene_index AND f.ts>=w.lo AND f.ts<w.hi
UNION ALL SELECT 'slice',scene_index,hex(json_object('id',id,'ts',ts,'dur',dur,'thread',thread,'pid',pid,'role',role,
 'name',name,'metric',metric,'clipped',ts<lo OR ts+dur>hi,'running_ns',running,'runnable_ns',runnable,
 'sleeping_ns',sleeping,'uninterruptible_ns',uninterruptible,'covered_ns',covered)) FROM slice_states
UNION ALL SELECT 'thread_state',h.scene_index,hex(json_object('thread',t.thread,'role',t.role,'pid',t.pid,
 'tid',t.tid,'utid',t.utid,'state',h.state,'dur',SUM(h.dur))) FROM app_states h JOIN app_threads t
 ON t.scene_index=h.scene_index AND t.utid=h.utid
 GROUP BY h.scene_index,t.utid,t.pid,t.tid,t.thread,t.role,h.state
"""


def find_processor(supplied):
    if supplied:
        return Path(supplied).resolve()
    system = shutil.which("trace_processor_shell")
    if system:
        return Path(system)
    cached = sorted((Path.home() / ".local/share/perfetto/prebuilts").glob("trace_processor_shell-*"),
                    key=lambda path: path.stat().st_mtime)
    if cached:
        return cached[-1]
    raise ValueError("No cached trace_processor found; pass --trace-processor. This offline tool does not install/download it.")


def analyze_trace(path, scenes, package, processor, full_sort=False):
    command = [str(processor)]
    with processor.open("rb") as stream:
        prefix = stream.read(128)
    if prefix.startswith(b"#!/usr/bin/env python"):
        command.insert(0, sys.executable)
    command += ["query", "--format", "csv"]
    if full_sort:
        command.append("--full-sort")
    command.append(str(path))
    completed = subprocess.run(command, input=trace_query(scenes, package), capture_output=True, text=True)
    if completed.returncode:
        raise ValueError(f"trace_processor failed: {completed.stderr[-1500:]}")
    # Hex encodes JSON because some tp CSV versions do not escape embedded quotes.
    results = defaultdict(list)
    for row in csv.DictReader(io.StringIO(completed.stdout)):
        payload = json.loads(bytes.fromhex(row["payload_hex"]).decode())
        results[(row["record_type"], int(row["scene_index"]))].append(payload)
    results[("import_options", -1)] = [{"full_sort": full_sort}]
    return results


def summarize_trace(results, index, scene):
    caps = results[("capabilities", -1)][0]
    warnings, metrics, cpu = [], defaultdict(list), defaultdict(Counter)
    attribution_rows = results[("attribution", index)]
    attribution = dict(attribution_rows[0]) if attribution_rows else {
        "mode": "unavailable", "identified_processes": 0,
        "identified_threads": 0, "identified_ui_threads": 0}
    attribution["requested_pids"] = manifest_pids(scene)
    attribution["processes"] = results[("attributed_process", index)]
    attribution["observed_row_counts"] = {kind: len(results[(kind, index)])
                                            for kind in ("timeline", "slice", "thread_state")}
    attribution["identified_app_threads"] = attribution["identified_threads"] > 0
    attribution["confidence"] = (attribution["mode"] + "_with_trace_threads"
                                  if attribution["identified_app_threads"] else "missing_app_threads")
    if not attribution["identified_app_threads"]:
        warnings.append("No identified app threads for this scene. Global trace activity cannot prove app coverage or idle quiescence.")
    elif attribution["mode"] == "legacy_process_name":
        warnings.append("Legacy ledger has no per-scene app_pids: app ownership uses exact trace process name only.")
    valid_clock = caps["clock_id"] in (3, 6)
    start = scene.get("trace_start_ns" if caps["clock_id"] == 6 else "start_ns") if valid_clock else None
    end = scene.get("trace_end_ns" if caps["clock_id"] == 6 else "end_ns") if valid_clock else None
    scope_valid = start is not None and end is not None and end > start
    if not scope_valid:
        warnings.append("No explicit positive bounds for the trace's clock; trace scene attribution unavailable. No clock alias inferred.")
    bounds_covered = scope_valid and caps["trace_start_ns"] <= start and caps["trace_end_ns"] >= end
    if scope_valid and not bounds_covered:
        warnings.append("Requested scene extends beyond trace bounds; capture is incomplete.")
    imports = results[("import_options", -1)]
    import_options = imports[0] if imports else {"full_sort": False}
    configuration = [stat for stat in results[("stat", -1)] if stat["name"] == "config_write_into_file_no_flush"]
    problems = [stat for stat in results[("stat", -1)] if stat["name"] != "config_write_into_file_no_flush"]
    sorting_risk = bool(configuration and not import_options["full_sort"])
    if configuration:
        warnings.append("Streaming capture omitted periodic producer flush. This is a configuration/sorting risk, not a measured packet-loss count; " +
                        ("explicit full-sort import mitigated windowed-sorting risk." if import_options["full_sort"] else
                         "windowed import is unmitigated; re-analyze with --full-sort or capture with flush_period_ms."))
    if problems:
        warnings.append("Nonzero trace error/loss/drop/overwrite stats: inspect capture health before accepting coverage.")
    if not caps["gpu_slice_rows"]:
        warnings.append("No hardware GPU slice samples; pure GPU execution time is unavailable.")
    layers = defaultdict(list)
    incomplete = 0
    for event in results[("timeline", index)]:
        if event["dur"] < 0:
            incomplete += 1
            continue
        layers[(event["pid"], event["layer"])].append(event)
    layer_reports = []
    for (pid, layer), frames in layers.items():
        jank = Counter(str(f["jank_type"]) for f in frames)
        known_jank = [f for f in frames if f["jank_type"] is not None]
        janky = sum(f["jank_type"] != "None" for f in known_jank)
        layer_reports.append({"pid": pid, "layer": layer, "surface_frames": len(frames),
            "actual_duration_ms": distribution([f["dur"] / NS_PER_MS for f in frames]),
            "jank_type_counts": dict(jank), "jank_known_frames": len(known_jank),
            "janky_surface_frames": janky, "jank_pct": 100 * janky / len(known_jank) if known_jank else None,
            "app_on_time_finish_counts": dict(Counter(str(f["on_time_finish"]) for f in frames)),
            "present_type_counts": dict(Counter(str(f["present_type"]) for f in frames)),
            "gpu_composition_counts": dict(Counter(str(f["gpu_composition"]) for f in frames)),
            "frames_completing_after_scene_end": sum(f["ts"] + f["dur"] > end for f in frames) if end else None})
    layer_reports.sort(key=lambda layer: layer["surface_frames"], reverse=True)
    clipped, uncovered = 0, 0
    for sample in results[("slice", index)]:
        if sample["clipped"]:
            clipped += 1
            continue
        name = sample["metric"]
        metrics[name].append(sample["dur"] / NS_PER_MS)
        covered = sample["covered_ns"] or 0
        if covered < sample["dur"] * 0.99:
            uncovered += 1
            continue
        for field in ("running", "runnable", "sleeping", "uninterruptible"):
            metrics[name.removesuffix("_wall") + "_" + field].append(sample[field + "_ns"] / NS_PER_MS)
    for row in results[("thread_state", index)]:
        cpu[(row["pid"], row["tid"], row["utid"], row["role"], row["thread"])][row["state"]] += row["dur"]
    ui_coverage = [sum(states.values()) / (end - start)
                   for (_, _, _, role, _), states in cpu.items() if role == "ui"] if scope_valid else []
    attribution["ui_scheduler_coverage_fraction"] = min(1.0, max(ui_coverage, default=0.0))
    return {"capabilities": caps, "scope_valid": scope_valid, "bounds_covered": bool(bounds_covered),
            "app_attribution": attribution,
            "capture_health_stats": problems, "per_session_capture_health_stats": list(problems),
            "configuration_warnings": configuration,
            "import_options": import_options, "import_sorting_risk": sorting_risk, "warnings": warnings,
            "frame_timeline": {"layers": layer_reports, "incomplete_surface_frames": incomplete,
                               "dominant_layer": layer_reports[0]["layer"] if layer_reports else None},
            "metrics_ms": {name: distribution(values) for name, values in metrics.items()},
            "boundary_crossing_slices_excluded_from_quantiles": clipped,
            "slice_samples_without_99pct_scheduler_coverage": uncovered,
            "thread_state_totals_ms": [{"pid": pid, "tid": tid, "utid": utid, "role": role, "thread": thread,
                "states": {state: round(ns / NS_PER_MS, 6) for state, ns in states.items()}}
                for (pid, tid, utid, role, thread), states in cpu.items()],
            "pure_gpu_execution_ms": None,
            "pure_gpu_execution_reason": "unobserved" if not caps["gpu_slice_rows"] else "GPU slices exist but per-app submission/context correlation is not established"}


PREDICTION_ONLY_WARNING = ("Prediction Error-only FrameTimeline flags are not dropped-frame or perceived-stutter counts. "
                           "Check late_present_surface_frames, app_late_finish_frames and app_deadline_missed_surface_frames separately.")


def derive_timeline_layer_counts(layer):
    """Derive explicit timing/flag counts without changing any saved raw flags."""
    present = layer.get("present_type_counts", {})
    finish = layer.get("app_on_time_finish_counts", {})
    jank = layer.get("jank_type_counts", {})
    known_present = {str(key).strip().casefold(): count for key, count in present.items()
                     if str(key).strip().casefold() in ("late present", "early present", "on-time present", "dropped frame")}
    known_finish = {str(key).strip().casefold(): count for key, count in finish.items()
                    if str(key).strip().casefold() in ("0", "1", "false", "true")}
    layer["late_present_surface_frames"] = (sum(count for key, count in known_present.items() if key == "late present")
                                             if known_present else None)
    layer["present_type_known_frames"] = sum(known_present.values())
    layer["app_late_finish_frames"] = (sum(count for key, count in known_finish.items() if key in ("0", "false"))
                                        if known_finish else None)
    layer["app_finish_known_frames"] = sum(known_finish.values())
    flag_sets = [(set(part.strip().casefold() for part in str(key).split(",")), count)
                 for key, count in jank.items() if key is not None and str(key).strip().casefold() not in ("none", "null", "")]
    known_jank = layer.get("jank_known_frames", sum(jank.values())) > 0
    layer["prediction_error_only_surface_frames"] = (sum(count for flags, count in flag_sets if flags == {"prediction error"})
                                                     if known_jank else None)
    layer["app_deadline_missed_surface_frames"] = (sum(count for flags, count in flag_sets if "app deadline missed" in flags)
                                                   if known_jank else None)
    return layer


def add_timeline_semantics(report):
    """Lightweight upgrade of saved report JSON; does not reopen any trace."""
    limitation = FRAME_TIMELINE_FLAG_LIMITATION
    if limitation not in report.setdefault("limitations", []):
        report["limitations"].append(limitation)
    for trial in report.get("trials", []):
        trace = trial.get("trace", {})
        layers = trace.get("frame_timeline", {}).get("layers", [])
        for layer in layers:
            derive_timeline_layer_counts(layer)
        if any(layer["prediction_error_only_surface_frames"] for layer in layers):
            warnings = trace.setdefault("warnings", [])
            if PREDICTION_ONLY_WARNING not in warnings:
                warnings.append(PREDICTION_ONLY_WARNING)
    return report


def trial_group_summary(trials):
    groups = defaultdict(list)
    for trial in trials:
        groups[trial["scene_id"]].append(trial)
    output = []
    for scene, records in groups.items():
        metrics, quantiles = defaultdict(list), defaultdict(lambda: defaultdict(list))
        for record in records:
            if record.get("status", "ok") != "ok":
                continue
            for source in ("gfxinfo", "trace"):
                for name, values in record.get(source, {}).get("metrics_ms", {}).items():
                    if values.get("p90") is not None:
                        metrics[source + "." + name].append(values["p90"])
                    for percentile in QUANTILES:
                        if values.get(f"p{percentile}") is not None:
                            quantiles[source + "." + name][f"p{percentile}"].append(values[f"p{percentile}"])
        medians = {name: distribution(values) for name, values in metrics.items()}
        candidates = {"GPU completion proxy (not pure GPU)": "gfxinfo.gpu_completion_after_submit_proxy"}
        for alternatives in (
            [("UI CPU Running", "trace.ui_frame_running"), ("CPU/UI wall (not CPU running)", "gfxinfo.ui_wall")],
            [("RT scheduling Runnable", "trace.rt_frame_runnable"), ("RT queue wait", "gfxinfo.rt_queue_wait_wall")],
            [("RT CPU Running", "trace.rt_frame_running"), ("RT command issue wall", "gfxinfo.rt_command_issue_wall")],
            [("buffer dequeue wait", "trace.rt_dequeue_wall"), ("buffer dequeue wait", "gfxinfo.surface_dequeue_wall")],
        ):
            for label, key in alternatives:
                if key in medians:
                    candidates[label] = key
                    break
        available = [(medians[key]["p50"], label) for label, key in candidates.items() if key in medians]
        measured = [record for record in records if record.get("status", "ok") == "ok" and record.get("phase", "measure") == "measure"]
        quiet = measured and all(record["analysis_status"] == "quiescent" for record in measured)
        output.append({"scene_id": scene, "trial_status_counts": dict(Counter(r.get("status", "ok") for r in records)),
                       "analysis_status_counts": dict(Counter(r["analysis_status"] for r in records)),
                       "trial_p90_distributions_ms": medians,
                       "median_trial_quantiles_ms": {name: {q: distribution(v)["p50"] for q, v in qs.items()}
                                                     for name, qs in quantiles.items()},
                       "dominant_observed_phase_by_median_trial_p90": max(available)[1] if available else
                           "quiescent (no sustained redraw observed)" if quiet else "unavailable",
                       "classification_is_root_cause": False})
    return output


def resolve_artifact(base, value):
    if not value:
        return None
    path = Path(value)
    return path.resolve() if path.is_absolute() else (base / path).resolve()


def classify_analysis_status(record):
    """Re-evaluable gates; raw global counters stay visible in the report."""
    if record["status"] != "ok" or record["phase"] != "measure":
        record["analysis_status"] = "not_measured"
        return
    gfx, trace = record.get("gfxinfo", {}), record.get("trace", {})
    blockers = trace.get("per_session_capture_health_stats", trace.get("capture_health_stats", []))
    no_samples = not gfx.get("valid_flag_zero_rows") and not trace.get("frame_timeline", {}).get("layers")
    idle_covered = (record["workload"] == "idle" and not record["errors"] and gfx and trace and
        gfx["coverage"]["scope"] == "clock_window" and not gfx["coverage"]["raw_retention_truncated"] and
        "aggregate_total_frames" in gfx["source_summary_unscoped"] and trace["bounds_covered"] and
        trace["capabilities"].get("frame_timeline_rows", 0) > 0 and not blockers and
        not trace["import_sorting_risk"] and
        trace["app_attribution"]["identified_app_threads"] and
        trace["app_attribution"]["ui_scheduler_coverage_fraction"] >= 0.99 and
        not trace["frame_timeline"]["incomplete_surface_frames"])
    record.pop("observation", None)
    if no_samples and idle_covered:
        record["analysis_status"] = "quiescent"
        record["observation"] = "Covered idle window: no eligible gfx or completed app surface frames observed; no sustained redraw observed. GPU duration remains unavailable, not zero."
    elif no_samples:
        record["analysis_status"] = "unavailable"
    elif record["errors"] or gfx.get("coverage", {}).get("raw_retention_truncated") or (trace and (
            not trace["bounds_covered"] or blockers or trace["import_sorting_risk"] or
            not trace["app_attribution"]["identified_app_threads"])):
        record["analysis_status"] = "partial"
    else:
        record["analysis_status"] = "ok"


def apply_service_health_scope(report, scenes, base, evidence_by_path=None):
    """Postprocess an existing report without importing its native trace again.

    Evidence is inspected once per trace and retained once at report top level.
    Only exact info-level counters with a sound, unchanged BOOTTIME bracket are
    separated from blockers. No conversion between MONOTONIC and BOOTTIME is
    inferred, and raw capture_health_stats are never changed.
    """
    if len(report["trials"]) != len(scenes):
        raise ValueError("Report/ledger trial count mismatch")
    evidence_cache = dict(evidence_by_path or {})
    audited = {}
    for record, scene in zip(report["trials"], scenes):
        if (record.get("scene_id"), record.get("trial")) != (scene.get("scene_id", "unnamed"), scene.get("trial")):
            raise ValueError("Report/ledger trial identity mismatch")
        trace = record.get("trace")
        if not trace:
            classify_analysis_status(record)
            continue
        raw = trace.get("capture_health_stats", [])
        eligible = [row for row in raw if row.get("severity") == "info" and row.get("name") in trace_health_scope.COUNTER_FIELDS]
        trace["per_session_capture_health_stats"] = list(raw)
        if eligible:
            path = resolve_artifact(base, trace.get("path") or scene.get("trace"))
            key = str(path) if path else None
            if key not in evidence_cache:
                evidence_cache[key] = (trace_health_scope.inspect_service_global_counters(path) if path else
                    {"status": "unknown", "errors": ["missing_trace_path"], "observations": []})
            evidence = evidence_cache[key]
            audited[key] = {"path": key, "sha256": trace.get("sha256"), "evidence": evidence}
            verified_clock = trace.get("capabilities", {}).get("clock_id") == trace_health_scope.BOOTTIME
            scoped = trace_health_scope.classify_service_global_health(evidence,
                scene.get("trace_start_ns") if verified_clock else None,
                scene.get("trace_end_ns") if verified_clock else None, raw)
            if not verified_clock:
                scoped["reason"] = "trace_primary_clock_not_verified_boottime"
            trace["service_global_health"] = scoped
            if scoped["safe_to_separate_from_session_loss"]:
                trace["per_session_capture_health_stats"] = [row for row in raw if row not in scoped["eligible_stats"]]
            for warning in scoped["warnings"]:
                if warning not in trace.setdefault("warnings", []):
                    trace["warnings"].append(warning)
        classify_analysis_status(record)
    report["trace_service_health"] = list(audited.values())
    report["scenes"] = trial_group_summary(report["trials"])
    return report


def analyze(document, scenes, base, processor=None, full_sort=False):
    reports, frames, trace_groups, gfx_cache = [], [], defaultdict(list), {}
    for index, scene in enumerate(scenes):
        report = {"scene_id": scene.get("scene_id", "unnamed"), "trial": scene.get("trial"),
                  "status": scene.get("status", "ok"), "phase": scene.get("phase", "measure"),
                  "workload": scene.get("workload", "interactive"), "errors": []}
        reports.append(report)
        if report["status"] != "ok" or report["phase"] != "measure":
            report["skip_reason"] = scene.get("skip_reason") or scene.get("error")
            if report["phase"] != "measure" and not report["skip_reason"]:
                report["skip_reason"] = "Non-measurement phase: " + report["phase"]
            continue
        path = resolve_artifact(base, scene.get("gfxinfo"))
        if path:
            try:
                if path not in gfx_cache:
                    gfx_cache[path] = (*parse_gfxinfo(path), sha256(path))
                rows, source, digest = gfx_cache[path]
                report["gfxinfo"], normalized = summarize_gfx(rows, source, scene)
                report["gfxinfo"].update(path=str(path), sha256=digest)
                frames.extend({"scene_id": report["scene_id"], "trial": report["trial"], **row} for row in normalized)
            except (OSError, ValueError) as error:
                report["errors"].append("gfxinfo: " + str(error))
        else:
            report["errors"].append("No gfxinfo artifact")
        trace = resolve_artifact(base, scene.get("trace") or document.get("trace"))
        if trace:
            trace_groups[trace].append((index, scene))
    if trace_groups:
        try:
            processor = find_processor(processor)
        except ValueError as error:
            for indexed in trace_groups.values():
                for index, _ in indexed:
                    reports[index]["errors"].append(str(error))
        else:
            for path, indexed in trace_groups.items():
                try:
                    results = analyze_trace(path, indexed, document.get("package", "dev.amatv"), processor, full_sort)
                    digest = sha256(path)
                    for index, scene in indexed:
                        reports[index]["trace"] = summarize_trace(results, index, scene)
                        reports[index]["trace"].update(path=str(path), sha256=digest)
                except (OSError, ValueError, KeyError) as error:
                    for index, _ in indexed:
                        reports[index]["errors"].append("trace: " + str(error))
    report = {"schema_version": 1, "report_type": "offline_tv_frame_analysis",
            "run_id": document.get("run_id"), "candidate_sha256": document.get("candidate_sha256"),
            "package": document.get("package", "dev.amatv"), "quantile_method": "linear interpolation at (n-1)*p",
            "incomplete_last_jsonl_record": document.get("incomplete_last_jsonl_record", False),
            "limitations": list(LIMITATIONS), "trials": reports}
    apply_service_health_scope(report, scenes, base)
    return add_timeline_semantics(report), frames


def summary_rows(report):
    for trial in report["trials"]:
        if trial["status"] != "ok" or not any(trial.get(source, {}).get("metrics_ms") for source in ("gfxinfo", "trace")):
            yield {"run_id": report["run_id"], "scene_id": trial["scene_id"], "trial": trial["trial"],
                   "status": trial["status"], "analysis_status": trial["analysis_status"], "metric": "unavailable",
                   "reason": trial.get("skip_reason") or "; ".join(trial["errors"])}
        for source in ("gfxinfo", "trace"):
            for metric, values in trial.get(source, {}).get("metrics_ms", {}).items():
                yield {"run_id": report["run_id"], "scene_id": trial["scene_id"], "trial": trial["trial"],
                       "status": trial["status"], "analysis_status": trial["analysis_status"],
                       "source": source, "metric": metric, "unit": "ms", **values}


def write_csv(path, rows):
    rows = list(rows)
    fields = list(dict.fromkeys(key for row in rows for key in row))
    with Path(path).open("w", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def markdown_report(report):
    lines = [f"Run: `{report['run_id']}`; candidate: `{report['candidate_sha256']}`.",
             "Offline artifact analysis. GPU completion proxies and waits are **not pure GPU execution time**.", ""]
    warnings = defaultdict(list)
    def number(value):
        return "—" if value is None else f"{value:.3f}"
    lines += ["Scene overview, sorted by median trial P90 total frame wall time.",
              "Each timing is the median of independent trial P90s; phase quantiles must not be added together. Partial capture warnings still apply.", "",
              "| Scene | Trial status counts | Analysis counts | Total frame P90 ms | Worst trial P90 ms | UI P90 ms | Dequeue P90 ms | GPU proxy P90 ms | Gfx trials contributing |",
              "|---|---|---|---:|---:|---:|---:|---:|---:|"]
    def scene_metric(scene, name):
        return scene.get("median_trial_quantiles_ms", {}).get("gfxinfo." + name, {}).get("p90")
    for scene in sorted(report["scenes"], key=lambda s: (scene_metric(s, "frame_total_wall") is None,
                        -(scene_metric(s, "frame_total_wall") or 0), s["scene_id"])):
        counts = lambda value: ", ".join(f"{key}:{count}" for key, count in value.items())
        timings = [number(scene_metric(scene, name)) for name in
                   ("frame_total_wall", "ui_wall", "surface_dequeue_wall", "gpu_completion_after_submit_proxy")]
        timings.insert(1, number(scene.get("trial_p90_distributions_ms", {}).get("gfxinfo.frame_total_wall", {}).get("max")))
        contributing = scene.get("trial_p90_distributions_ms", {}).get("gfxinfo.frame_total_wall", {}).get("n", 0)
        lines.append(f"| {scene['scene_id']} | {counts(scene['trial_status_counts'])} | {counts(scene['analysis_status_counts'])} | "
                     + " | ".join(timings) + f" | {contributing} |")
    lines += ["", "Per-trial evidence:", "",
              "| Scene | Trial | Status / analysis | Gfx frames | Gfx deadline misses | FT dominant n / late present / app late | UI P90 ms | Dequeue P90 ms | GPU proxy P90 ms |",
              "|---|---:|---|---:|---:|---:|---:|---:|---:|"]
    for trial in report["trials"]:
        gfx = trial.get("gfxinfo", {})
        def p90(name):
            return number(gfx.get("metrics_ms", {}).get(name, {}).get("p90"))
        layers = trial.get("trace", {}).get("frame_timeline", {}).get("layers", [])
        if layers:
            layer = derive_timeline_layer_counts(layers[0])
            count = lambda value: "—" if value is None else str(value)
            timeline = f"{layer['surface_frames']} / {count(layer['late_present_surface_frames'])} / {count(layer['app_late_finish_frames'])}"
        else:
            timeline = "—"
        lines.append(f"| {trial['scene_id']} | {trial['trial']} | {trial['status']} / {trial['analysis_status']} | {gfx.get('valid_flag_zero_rows', '—')} | "
                     f"{gfx.get('deadline', {}).get('missed_frames', '—')} | {timeline} | {p90('ui_wall')} | {p90('surface_dequeue_wall')} | "
                     f"{p90('gpu_completion_after_submit_proxy')} |")
        for problem in trial.get("errors", []) + gfx.get("warnings", []) + trial.get("trace", {}).get("warnings", []):
            warnings[problem].append(f"{trial['scene_id']}/{trial['trial']}")
        if trial.get("skip_reason"):
            warnings[str(trial["skip_reason"])].append(f"{trial['scene_id']}/{trial['trial']}")
        if trial.get("observation"):
            warnings[trial["observation"]].append(f"{trial['scene_id']}/{trial['trial']}")
    def trace_p90(trial, name):
        return trial.get("trace", {}).get("metrics_ms", {}).get(name, {}).get("p90")
    ranked = sorted((t for t in report["trials"] if t.get("status") == "ok" and
                     trace_p90(t, "rt_dequeue_wall") is not None),
                    key=lambda t: trace_p90(t, "rt_dequeue_wall"), reverse=True)[:8]
    if ranked:
        lines += ["", "Largest individual-trial RT buffer waits (not an additive phase breakdown):", "",
                  "| Scene | Trial | Dequeue P90 ms | Sleeping P90 ms | Running P90 ms | GPU completion fence wait P90 ms |",
                  "|---|---:|---:|---:|---:|---:|"]
        for trial in ranked:
            values = [number(trace_p90(trial, name)) for name in
                      ("rt_dequeue_wall", "rt_dequeue_sleeping", "rt_dequeue_running", "gpu_completion_fence_wait_wall")]
            lines.append(f"| {trial['scene_id']} | {trial['trial']} | " + " | ".join(values) + " |")
    late_layers = [(trial, layer) for trial in report["trials"]
                   if trial.get("status") == "ok" for layer in map(derive_timeline_layer_counts, trial.get("trace", {}).get("frame_timeline", {}).get("layers", []))
                   if (layer.get("late_present_surface_frames") or 0) > 0]
    if late_layers:
        lines += ["", "Delayed presentation by surface; rows are separate surfaces, never display-frame totals:", "",
                  "| Scene | Trial | Layer | Surface frames | Late present | App late finish | App deadline flag |",
                  "|---|---:|---|---:|---:|---:|---:|"]
        for trial, layer in late_layers:
            values = [str(layer.get(name)) if layer.get(name) is not None else "—" for name in
                      ("surface_frames", "late_present_surface_frames", "app_late_finish_frames", "app_deadline_missed_surface_frames")]
            lines.append(f"| {trial['scene_id']} | {trial['trial']} | {layer['layer']} | " + " | ".join(values) + " |")
    lines += ["", "Coverage / availability:", ""]
    lines += [f"- {problem} ({', '.join(where[:3])}{'; '+str(len(where))+' trials' if len(where)>3 else ''})"
              for problem, where in warnings.items()]
    lines += ["", "Repeated-trial classification (largest median of trial P90; an observation, not a root-cause verdict):", ""]
    lines += [f"- {scene['scene_id']}: {scene['dominant_observed_phase_by_median_trial_p90']}." for scene in report["scenes"]]
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ledger", required=True, type=Path)
    parser.add_argument("--trace-processor", type=Path)
    parser.add_argument("--full-sort", action="store_true",
                        help="Buffer/sort the whole trace during import; mitigates old streaming traces without periodic flush, at higher RAM cost")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--csv", type=Path)
    parser.add_argument("--markdown", type=Path)
    parser.add_argument("--frames-csv", type=Path)
    args = parser.parse_args()
    try:
        document, scenes = read_ledger(args.ledger)
        report, frames = analyze(document, scenes, args.ledger.resolve().parent, args.trace_processor, args.full_sort)
    except (OSError, ValueError) as error:
        parser.error(str(error))
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    if args.csv:
        write_csv(args.csv, summary_rows(report))
    if args.markdown:
        args.markdown.write_text(markdown_report(report))
    if args.frames_csv:
        write_csv(args.frames_csv, ({"scene_id": row["scene_id"], "trial": row["trial"], "window": row["window"],
            "eligible": row["eligible"], **{"raw_" + key: value for key, value in row["raw"].items()},
            **{key + "_ms": value for key, value in row["phases_ms"].items()}} for row in frames))
    print(f"Analyzed {len(report['trials'])} trial records into {args.output}")


if __name__ == "__main__":
    main()
