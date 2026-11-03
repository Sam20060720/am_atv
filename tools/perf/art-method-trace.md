# Offline ART method trace attribution

`analyze_art_method_trace.py` reads an already collected non-streaming Android
method trace. It never invokes ADB, installs an APK, starts playback, or changes
app settings. Python's standard library is sufficient.

```sh
python3 tools/perf/analyze_art_method_trace.py capture.methodtrace \
  --mapping app/build/outputs/mapping/benchmark/mapping.txt \
  --thread main --start-us 958200.923 --end-us 1062445.172 \
  --output worst-draw-methods.json

# Union of exact, non-overlapping Perfetto windows, already converted to MONOTONIC.
python3 tools/perf/analyze_art_method_trace.py capture.methodtrace \
  --mapping app/build/outputs/mapping/benchmark/mapping.txt \
  --thread 23350 --windows-csv sampled-slow-draw.csv \
  --top 250 --output six-draw-methods.json
```

Window CSV columns are `start_mono_ns,end_mono_ns`; extra columns are ignored.
Use the exact mapping file from the sampled APK. The report records both hashes.
The default thread is exactly `main`; a numeric tid or `'*'` can be supplied.

## Format and clock evidence

The implementation follows Android 11 AOSP's
[`runtime/trace.cc`](https://android.googlesource.com/platform/art/+/refs/heads/android11-release/runtime/trace.cc):

- Text `*version`, key/value properties, `*threads`, `*methods`, `*end`, followed by
  a binary `SLOW` header. Method rows contain ID, class, method, JVM descriptor,
  source filename. Low two event bits are enter=0, exit=1, unwind=2.
- Header at binary offset 4 contains little-endian u16 version, u16 data offset,
  u64 start timestamp; v3 has u16 record size at offset 16. Normal header is 32 bytes.
- Dual-clock v3 records are 14 bytes: u16 tid, u32 method/action, u32 thread CPU
  microseconds, u32 wall microseconds. v2 uses 10-byte single-clock records.
- `Trace` initializes `start_time_(MicroTime())` (line 560), writes it at binary
  offset 8 (line 574), and wall events store `MicroTime()-start_time_` (line 808).
  [`libartbase/base/time_utils.cc:159–163`](https://android.googlesource.com/platform/art/+/refs/heads/android11-release/libartbase/base/time_utils.cc)
  establishes that Linux `MicroTime()` uses **CLOCK_MONOTONIC**, not BOOTTIME or
  epoch. Thus relative event microseconds are
  `(absolute_monotonic_ns - header_start_us*1000)/1000`. Convert Perfetto's trace
  clock through its clock snapshots first; do not subtract BOOTTIME directly.

Streaming/newer-format records are rejected rather than guessed. Truncation,
overflow, unknown methods and stack inconsistencies are explicitly reported.

## What the numbers mean

ART sampling emits **differences between sampled stacks**, not one record per
sample (`CompareAndUpdateStackTrace`, lines 232–270). All enters/exits belonging
to one change share the same clocks. The parser reconstructs each thread's
stack, attributes the interval preceding a change to the previous stack's leaf,
and attributes that interval to each distinct ancestor. Recursive ancestor
appearances are deduplicated. The first unknown prefix and final unchanged
stack tail are not extrapolated.

- `thread_cpu_ms` uses the trace's per-thread CPU-clock delta, weighted to the
  sampled stack. It is an approximate method attribution, **not exact execution
  time of that method**. Boundary fragments use proportional CPU interpolation.
- `wall_ms` also includes blocking, scheduler delays and preemption. It must not
  be presented as running CPU time. A wall-only trace has null CPU fields.
- Enters are synthetic stack-difference events, **not actual call counts**.
- JNI leaves such as `nDrawTextRun` do not expose their native C/C++ children.
- R8 class/method/descriptor matches are used where available. Mapping line/PC
  information is absent from this format, so inlining, moved methods and merged
  overloads can remain ambiguous. The report retains every candidate instead
  of assigning time to an invented single original method.

Sampling itself perturbs execution. On Android 11 it suspends threads and walks
all thread stacks each sampling cycle, and disables JIT code-cache garbage
collection for that process. The sampling branch does **not** call the full
instrumentation branch's `EnableMethodTracing(...needs_interpreter...)`; do not
claim sampling necessarily deoptimized every method. Build/JIT/inlining state,
stack walking and sparse observations still make tiny getter buckets unsuitable
as production cost measurements. Use sampled phase/call-path evidence to choose
a change, then verify it in a fresh production process with method profiling off.

Six synthetic tests cover stack-diff timing, dual/single clocks, disjoint-window
scoping, overflow/corruption and descriptor-aware ambiguous R8 labels:

```sh
python3 -m unittest discover -s tools/perf/tests -p test_analyze_art_method_trace.py
```
