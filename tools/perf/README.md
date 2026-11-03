Automated survey of the installed TV app
======================================

Run from the repository root. This builds and installs only the self-instrumenting
`dev.amatv.benchmark` APK and exercises the already installed `dev.amatv` app.
The runner verifies the emulator identity and target APK digest before and after.
It never uses `connectedAndroidTest`, reinstalls the target, clears its data,
selects a new song or activates account/library mutation actions. The default
browse suite keeps playback paused. The separate playing suite briefly resumes
only the existing paused song, verifies its identity, uses the public scrubber
to repeat the same time window, then pauses and restores the original cursor
to the nearest five-second remote seek step. It preserves the account and lyrics design.

```sh
# Calibration: one excluded warmup and one recorded trial per chosen scene.
python3 tools/perf/run_tv_perf.py --iterations 1 \
  --scenes home_idle,home_horizontal,home_square_horizontal,library_grid,context_menu_open_close \
  --run-id calibration-unique

# Browse: excluded warmup and three recorded trials for all 37 scenes.
python3 tools/perf/run_tv_perf.py --run-id full-browse-unique

# Actual playback: lyrics rendering, controls, existing queue cursor (3 scenes).
python3 tools/perf/run_tv_perf.py --suite playing --run-id full-playing-unique
```

The separate `tools/run-tv-ui-tests.sh DEVICE_SERIAL [all|functional|performance|TEST_CLASS]`
runner uses the connected macrobenchmark target variant. Its generic setup never
selects a song. Player UI cases require an existing paused song and skip when
that fixture is unavailable. Actual playback and the player rendering benchmarks
additionally require `AMATV_ALLOW_PLAYER_PLAYBACK=1` (instrumentation argument
`allow_player_playback=1`), checked before their launch/setup actions.

Only `TvProductJourneyTest#nativePlaybackCanStartAndPauseFromRemote` may select
a public song when no retained player exists, and only with both
`AMATV_ALLOW_PLAYER_PLAYBACK=1` and `AMATV_ALLOW_PLAYBACK_SEED=1`
(`allow_playback_seed=1`). The rendering benchmarks always require a retained
paused song, including the cold lyrics case; the seed flag does not change that
precondition. Playback cleanup only pauses audio started by that benchmark.

For a complete repair-cycle verification, build the frozen tester once, then run:

```sh
./gradlew :benchmark:assembleBenchmark
python3 tools/perf/run_acceptance_cycle.py --run-id acceptance-unique \
  --expected-sha256 <installed-base.apk-sha256>
```

The cycle waits for an unlocked macOS desktop, runs both functional pilots,
then disjoint 12/14/11-scene browse captures and the three playing captures,
with three trials each. Only foreground/lock interruptions can retry automatically;
an app or harness failure stops that attempt for diagnosis and repair. No missing
scene is accepted. `cycle.json` retains every attempt, and `acceptance.json`
checks the complete 40-scene union and one target candidate. Render/performance
review remains a separate required step; the coverage ledger is not a claim of
perfect animation.

Optional `--expected-sha256 <installed-base.apk-sha256>` pins the candidate.
Only `emulator-5554` / `AMATV_Promo_TV` is allowed by this runner. Results go to
`work/performance/<run-id>/`: host/device configuration, visibility metadata,
per-scene semantic evidence, actual input scheduling and post-END verification
latency, raw gfx framestats,
streamed Perfetto trace, JSON, CSV and Markdown reports. No video capture runs
alongside the measurement. Analysis starts only after collection; do not run
CPU-intensive trace parsing in parallel with a survey on the same host. Do not
interact with the emulator during a survey.

Headless host-GPU runs use `--render-mode headless` on both collectors and
the acceptance-cycle runner. Start the same retained AVD explicitly with
`-no-window -gpu host -no-snapshot-load -no-snapshot-save` after a graceful
shutdown and complete AVD backup. The collector verifies actual process
arguments and the guest GLES renderer, and records headless mode explicitly.
An absent host foreground is expected in this mode; it is never recorded as
controlled foreground. Guest UI rendering, captured PNGs, input cadence,
scheduler states, and FrameTimeline must still be validated in a calibration.
Native host-window posting is omitted, so fence, dequeue/backpressure and
presentation metrics cannot be pooled with visible-window baselines. Mixed-mode
acceptance summaries are rejected. This mode does not change account data or
macOS lock preferences.

The local calibration on the retained AVD confirmed host GPU identity, real
guest rendering and stable input while the desktop was locked; this is an
observed condition, not a universal guarantee for every Mac/emulator. See the
[official command-line options](https://developer.android.com/studio/run/emulator-commandline)
and [graphics acceleration modes](https://developer.android.com/studio/run/emulator-acceleration).

The 37 browse scenarios cover Home, Browse, square cards, sidebar focus and page
changes, Search, Library, album/playlist right-pane scrolling, NewJeans artist
sections, context menus through shortcut/long-press/ellipsis entry, Settings button focus, the existing paused player/queue,
and precise source-card/side-panel/player Back contracts. The separate playing
suite covers actual lyric rendering, transport focus, and queue cursor movement.
Scenes requiring unavailable content or an existing paused session retain their
skip reason and make the run incomplete. A route must prove its starting container and expected endpoint;
failures remain in the ledger and never become successful timing samples.
Repeated trials preserve their actual input injection durations and scheduling
lateness; a planned 110 ms cadence is not reported as achieved when dispatch
itself takes longer. Explicit menu holds use minimum post-dispatch gaps:
raw planned-schedule lateness includes those intentional holds and dispatch
durations. The acceptance summary also reports lateness relative to the actual
`effective_not_before_ns` boundary and keeps `intentional_hold_ms`, so a
650ms long press is not mislabeled as 650ms of scheduler delay.

Warmup and setup are outside measurement. The measured body sends a recorded
DPAD schedule without hierarchy polling, then a fixed animation tail. Endpoint
inspection and raw artifact capture occur after END. A bounded read-only
endpoint observation (up to five seconds) retains both initial and final states;
its elapsed time is reported separately and cannot extend the measurement. Idle windows can produce
no frames; this is separately checked against trace health. Host window
activation is verified against the actual frontmost PID. The collector samples
foreground state every five seconds and records any recovery. Recovery during a
run makes its measurement environment unstable and requires a clean repeat.
The existing macOS Accessibility grant, when present, allows foreground recovery
without changing host scheduling or power preferences. Each collector holds
scoped display/idle-sleep assertions until its process exits. It never unlocks
macOS or changes lock preferences; an explicitly locked desktop waits for the user. `--keep-host-focus` records
an uncontrolled run and cannot satisfy the final controlled-environment gate.
An emulator GPU completion/fence proxy must not be labeled physical GPU
execution time. See metric definitions below.

The collector uses compact scheduler encoding, a 64 MiB scheduler buffer and
a separate 8 MiB buffer for FrameTimeline/process metadata, draining to disk
every second with producer flushes every ten seconds. The trace cap is at most
4 GiB and preserves at least 512 MiB of the emulator /data free space observed
at preflight;
traced runs require at least 5 GiB free. These settings do not guarantee loss-free
capture: the report still checks actual overwrite/drop/error counters and bounds.

The runner analyzes long recordings with explicit full sorting to avoid importer
window reordering loss. This may need additional RAM.

The runner stops only its owned tester/Perfetto session and removes only its
own remote artifacts after pulling them. An interrupted or failed run retains
partial local evidence. Playing-suite cancellation first requests cooperative
exit, allowing the current measured body to finish and the tester to pause/restore
its owned track. Failure to verify cleanup remains an error. A 12-second
unmeasured trace tail provides after-window service-stat observations. `complete` requires exactly the requested scene IDs, no
duplicates, all requested trials passing, an unchanged target APK, and stable
controlled foreground state. A skipped scene, missing trial, or foreground
recovery is incomplete and exits nonzero. Legacy `complete_with_skips` reports
are partial evidence only. A full acceptance cycle requires all 37 browse and
3 playing scenes, each with three measured trials on the same target candidate.
Large surveys may be split into disjoint scene groups; all groups must satisfy
these gates. Functional completion and trace/performance quality are reported
separately and both require review before accepting a candidate.

Offline TV performance reports
==============================

`analyze_tv_perf.py` reads saved artifacts only. It does not run ADB, install an
APK, change settings, start playback, make account requests, or download tools.
It needs Python 3.10+ and, for traces, a previously cached current Perfetto
`trace_processor_shell` or the repository's already initialized official wrapper.

```sh
python3 tools/perf/analyze_tv_perf.py \
  --ledger work/perf/run/manifest.json \
  --trace-processor work/apple-tv-ui/render-audit/perfetto/trace_processor \
  --output work/perf/run/report.json \
  --csv work/perf/run/metrics.csv \
  --frames-csv work/perf/run/retained-frames.csv \
  --markdown work/perf/run/report.md
python3 -m unittest discover -s tools/perf/tests
```

Input contract
--------------

Artifact paths are relative to the ledger, unless absolute. Each trial's UI
status is `ok`, `skip`, or `failed`; analysis quality is reported separately.
Both nested scenes and flat `scenes:[{scene_id,trial,...}]` are accepted.
Only `phase: "measure"` (the default) enters timing reports; warmup/setup records
remain visible as not measured.

Idle trials may set `workload: "idle"`. An idle trial with no normal gfx frames
and no completed app surface frames is `quiescent` only when its recognizable
gfx capture is complete, its explicit trace clock window is covered, the trace
has observed FrameTimeline data elsewhere, and no capture loss or unfinished
surface frame is present. This means no sustained redraw was observed in that
window. Missing artifacts, out-of-range traces, and failed interactive trials
cannot become quiescent. Empty distributions, including GPU proxies, have null
quantiles and null sums, never a fabricated 0 ms GPU time.

```json
{
  "schema_version": 1,
  "run_id": "unique-run-id",
  "candidate_sha256": "actual-installed-apk-sha256",
  "package": "dev.amatv",
  "trace": "run.pftrace",
  "scenes": [
    {
      "scene_id": "browse",
      "status": "ok",
      "trials": [
        {
          "trial": 1,
          "status": "ok",
          "gfxinfo": "browse-1-framestats.txt",
          "clock": "monotonic",
          "start_ns": 100000000000,
          "end_ns": 103000000000,
          "trace_start_ns": 110000000000,
          "trace_end_ns": 113000000000
        }
      ]
    },
    {"scene_id": "radio", "status": "skip", "skip_reason": "unsupported", "trials": []}
  ]
}
```

`start_ns/end_ns` are device `System.nanoTime()` MONOTONIC boundaries, never host
wall time. `trace_start_ns/trace_end_ns` are device `elapsedRealtimeNanos()`
BOOTTIME boundaries. The analyzer reads Perfetto's `trace_time_clock_id`: ID 6
uses the BOOTTIME bounds; ID 3 uses MONOTONIC. Unknown clocks or missing bounds
make trace attribution unavailable. No offset is inferred from similar numbers.
All selection windows are half open. Gfx rows belong by `IntendedVsync`, timeline
surface frames by actual start. A per-trial `trace` can override the run trace.

App trace ownership is evaluated separately for every trial. New survey ledgers
save `app_pids` from `pidof dev.amatv` immediately before BEGIN. These PID claims
are scoped to that trial's explicit clock window and intersected with trace
process lifetimes, then applied to FrameTimeline, slices and thread states.
A PID observed in another trial is never an ownership claim for this one. An
explicit empty `app_pids: []` fails closed; it cannot fall back to a matching
process name. This also allows attribution when a freshly started app's trace
process row has a missing name. The report retains requested PIDs, matched trace
process identities, attribution mode, observed row counts and UI scheduler
coverage. The ledger is the ownership evidence, not a claim inferred from an
arbitrary unnamed trace process.

Legacy ledgers without `app_pids` use exact process-name matching, with that
weaker attribution mode explicitly reported. Zero identified app threads emits
a warning; global FrameTimeline events from other apps cannot certify coverage.
Idle quiescence additionally requires an identified app UI thread with at least
99% scheduler-state coverage of the trial. Missing app attribution never becomes
zero GPU time or a successful idle-quiescence verdict.

JSONL may contain one metadata header followed by flat trial records. An
interrupted final JSONL record is ignored and explicitly reported. Earlier
malformed records fail parsing. A skipped scene with no trials is retained.

What is measured
----------------

The JSON and metric CSV contain P50/P90/P95/P99, max, mean, count and sum, in ms.
Quantiles use linear interpolation at `(n-1)*p`. Scene summaries use medians of
**trial quantiles**, not one pooled frame population. Failed/skipped trials do
not enter timing distributions. The largest median trial P90 is an observed
phase classification, not a root-cause verdict.

Gfx fields are matched by their raw column labels, not historical positions:

| Metric | Raw calculation |
|---|---|
| UI wall | `SyncQueued - HandleInputStart` |
| Draw recording wall | `SyncQueued - DrawStart` |
| RT queue wait | `SyncStart - SyncQueued` |
| RT sync | `IssueDrawCommandsStart - SyncStart` |
| RT command issue wall | `SwapBuffers - IssueDrawCommandsStart` |
| GPU completion after submission **proxy** | `GpuCompleted - CommandSubmissionCompleted` |
| GPU completion after swap **proxy** | `GpuCompleted - SwapBuffers` |
| Buffer waits | raw `DequeueBufferDuration`, `QueueBufferDuration` |
| Total frame wall | `FrameCompleted - IntendedVsync` |
| Declared deadline miss | `FrameCompleted > FrameDeadline` |

Zero/missing/sentinel timestamps and negative deltas are excluded per metric;
the number rejected is retained. `Flags != 0` frames remain in raw output but
are excluded from these quantiles/deadline counts. `GpuTaskCompleted` is retained
as a distinct unknown raw field; it is **not** an alias. Android 34 files can
have absolute timestamps under `FrameInterval` and interval values under
`FrameStartTime`; this produces a warning, not a positional rewrite.

Activity and Dialog windows are kept distinct. Combined gfx samples are **app
window frames**, not display FPS. Multi-window JSON also contains separate
window reports. FrameTimeline is split by PID/layer; its actual duration includes
CPU work, GPU completion and posting, and `gpu_composition` is only a boolean.
The Markdown surface count uses the explicitly identified most-sampled layer.

Raw `janky_surface_frames`, `jank_pct` and `jank_type_counts` are retained as raw
FrameTimeline flag summaries. A `Prediction Error` flag alone is not a dropped
frame or evidence of perceived stutter. In particular, a layer may have 100%
raw flags while every surface frame is `Early Present` and the app finishes on
time. Each layer separately derives `late_present_surface_frames` from present
types, `app_late_finish_frames` from app finish timing,
`prediction_error_only_surface_frames` from the isolated flag, and
`app_deadline_missed_surface_frames` from the named deadline flag. These counts
are separate observations and must not be added together. Missing presentation
or app-finish counters yield null timing counts. The Markdown table displays
`n / late present / app late`, rather than presenting all flags as missed frames.

Existing report JSON can receive these semantic fields without reopening a trace:

```python
import json
from pathlib import Path
from tools.perf.analyze_tv_perf import add_timeline_semantics, markdown_report

path = Path("work/performance/<run-id>/report.json")
report = add_timeline_semantics(json.loads(path.read_text()))
path.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
path.with_suffix(".md").write_text(markdown_report(report))
```

Trace UI/RT frame/dequeue/swap/fence spans are intersected with scheduling states
on their exact thread. Running, Runnable, Sleeping and uninterruptible durations
are separate. Nested same-thread wrappers of the same metric are removed;
different metrics are inclusive and must not be added together. A span crossing
a scene boundary is excluded from duration quantiles. CPU state metrics require
at least 99% scheduling coverage. Thread-state totals are clipped to the scene. The SQL materializes each trial's
app scheduler states once and reuses those bounded rows for frame intersections
and totals; it does not rescan the full recording for every frame. Classified
slices are likewise materialized after time clipping, preserving nested-wrapper
removal and explicit exclusion of boundary-crossing slices from quantiles.

Android 14's HWUI GPU completion fields derive from completion fences and command
submission timing. Neither delta above isolates physical GPU shader execution,
especially through emulator graphics translation. Without correlated hardware
render stages the report returns `pure_gpu_execution_ms: null`, even if GPU
completion/fence proxy values exist. Nonempty global `gpu_slice` data alone also
does not establish per-app submission ownership.

Coverage and long recordings
----------------------------

Gfx framestats retain only a recent ring, commonly 120 rows per window. The
printed aggregate jank percentage belongs to its entire reset interval and is
never substituted for a scene percentage. If aggregate frame count exceeds
retained rows, gfx phase results are explicitly partial. Zero rows can mean
an idle scene, missing capture or ring eviction; it does not establish zero work.

For a 5–15 minute suite, stream Perfetto to a file (`write_into_file: true`,
periodic flushing, sufficient file-size budget) and stop/flush the owned session
before pulling. A large in-memory ring alone cannot preserve early scenes.
The old 121 configs have a 10-second duration and are not long-suite configs.
The parser checks trace bounds, clock metadata and nonzero error/loss/overwrite/
discard/drop/overrun stats. Bounds alone cannot prove every producer retained
events; per-scene rows, scheduling coverage and capture health remain visible.

`file_write_period_ms` drains the central buffer; `flush_period_ms` separately
asks producers to commit partially full shared-memory pages. For long captures,
use a periodic producer flush (for example 10,000 ms); `flush_timeout_ms` only
limits how long a flush may wait and does not schedule periodic flushes.
`config_write_into_file_no_flush` is a configuration/sorting risk, not a measured
packet-loss count. It remains in `configuration_warnings`. A windowed import
keeps `import_sorting_risk: true`; re-analyzing the same saved trace with
`--full-sort` records that mitigation in `import_options` and clears only that
sorting risk. This buffers the trace during import and can require substantially
more RAM. Real loss/drop/out-of-order errors remain blocking health stats;
full-sort never repairs packets that were not captured.

Historical artifacts such as 121 may validate parsing. Their percentages are
never represented as a new device run or a new candidate's acceptance result.

Primary sources
---------------

- [Android 14 FrameInfo GPU timing approximation](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/libs/hwui/FrameInfo.h)
- [Android 14 FrameMetrics mapping](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/core/java/android/view/FrameMetrics.java)
- [Android 14 CanvasContext fence/completion assignment](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/libs/hwui/renderthread/CanvasContext.cpp)
- [Perfetto FrameTimeline meaning and surface tokens](https://perfetto.dev/docs/data-sources/frametimeline)
- [Perfetto CPU scheduling states](https://perfetto.dev/docs/data-sources/cpu-scheduling)
- [Perfetto GPU producers and correlation](https://perfetto.dev/docs/data-sources/gpu)
- [Perfetto trace configuration](https://perfetto.dev/docs/concepts/config)

Trace health scope
------------------

The raw health-stat list remains intact. The two info-level service-global
`traced_chunks_discarded` / `traced_patches_discarded` values are cumulative
service counters, distinct from per-session buffer loss. The offline helper
scans bounded protobuf fields and brackets each BOOTTIME measurement with
same-service observations. Only a proven unchanged counter across that window
can be separated from session loss; missing bounds, overlapping increments,
unknown clocks, unsupported compression, and malformed input fail closed.
Per-buffer loss, ftrace overruns, invalid packets, and error/data_loss severity
remain blockers. This does not declare any capture loss-free. The official
[service implementation](https://github.com/google/perfetto/blob/v34.0/src/tracing/core/tracing_service_impl.cc)
and [counter definitions](https://github.com/google/perfetto/blob/v34.0/src/tracing/core/tracing_service_impl.h)
support that distinction. `trace_service_health` preserves auditable observations.

Physical TV short captures
==========================

The separate `run_physical_frames.py` runner opts into a known physical TV using
its explicit serial, hardware identity and installed APK digest. Install the
candidate separately with the APK+DM kit; the collector only updates the tester
when its digest changes. This does not relax the emulator-only guards of the
larger survey above.

```sh
./gradlew :benchmark:assembleBenchmark
python3 tools/perf/run_physical_frames.py \
  --serial 192.168.1.116:5555 --hardware m7632 \
  --apk-sha256 <installed-APK-sha256> --run-id <unique-id> \
  --output work/tv-performance/<date> --check-menu
# Actual horizontal reveal, not just focus among already visible cards:
python3 tools/perf/run_physical_frames.py \
  --serial 192.168.1.116:5555 --hardware m7632 \
  --apk-sha256 <installed-APK-sha256> --run-id <another-unique-id> \
  --output work/tv-performance/<date> --axis horizontal \
  --start-shelf 1 --start-item 4 --check-menu
python3 tools/perf/analyze_physical_frames.py work/tv-performance/<date>/<unique-id>
```

Warmup is excluded; each short trial exports/reset gfxinfo before its ring buffer
can lose a long route's early frames. SurfaceFlinger actual-present timestamps
are collected separately. Actual dispatch durations, compiler status, endpoint
focus, loaded cover/caption geometry and optional MENU/Back checks are retained.
Local run IDs cannot be reused after remote cleanup. Key-window intervals can
include intentional30fps motion artwork after scrolling stops; they are not an
all-screen FPS guarantee. Optional benchmark-only `AMATV:home-scroll` async trace
intervals permit stricter scrolling attribution with the analyzer's
`--scroll-trace` and `--trace-processor` options.

`--compose-trace` requires the profileable benchmark target APK and adds named
composition slices. `--java-sampling` captures ART method samples and adds timing
overhead; neither run replaces ordinary standalone performance verification.
Restart the target before normal measurements after ART sampling; its sampling
session changes runtime bookkeeping. See `art-method-trace.md` and
`docs/compose-performance-tracing.md` for clocks, interpretation and setup.
