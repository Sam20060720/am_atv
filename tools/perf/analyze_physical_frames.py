#!/usr/bin/env python3
"""Summarize actual DPAD windows, excluding warmup, idle tails and menu checks.

These are app-window frame latency distributions, not an FPS counter. GPU-after-
swap remains a completion proxy and is not presented as GPU execution time.
"""
import argparse
import csv
import io
import subprocess
import sys
from collections import Counter, defaultdict
import json
from pathlib import Path
from analyze_tv_perf import distribution, frame_phases, parse_gfxinfo
from physical_frame_windows import merge_windows, presentation_intervals_ns

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('run', type=Path)
parser.add_argument('--scroll-trace', type=Path)
parser.add_argument('--trace-processor', type=Path, help='Python Perfetto query wrapper')
args = parser.parse_args()
result = json.loads((args.run / 'result.json').read_text())
assert result['status'] == 'passed', result
files = sorted(args.run.glob('trial-*-gfxinfo.txt')) or [args.run / 'gfxinfo.txt']
unique = {}
raw_summaries = []
for path in files:
    rows, summary = parse_gfxinfo(path)
    raw_summaries.append({'file': path.name, **summary})
    for row in rows:
        key = (row['window'], row['raw']['IntendedVsync'])
        unique[key] = row
inputs = result['inputs']
windows = [(i['start_ms'] * 1_000_000,
            (i['start_ms'] + max(300, i['dispatch_ms'] + 260)) * 1_000_000) for i in inputs]
windows = merge_windows(windows)
selected = [r for r in unique.values() if any(a <= r['raw']['IntendedVsync'] < b for a, b in windows)]
eligible = [r['raw'] for r in selected if r['raw']['Flags'] == 0]
phase_values = defaultdict(list)
for raw in eligible:
    phases, _ = frame_phases(raw)
    for name, value in phases.items():
        phase_values[name].append(value)
metrics = {name: distribution(values) for name, values in phase_values.items()}
report = {'run_id': result['run_id'], 'sample_unit': 'app_window_frame_latency_not_fps',
          'excluded_warmup': result.get('excluded_warmup'), 'inputs': inputs,
          'active_windows_monotonic_ns': windows,
          'raw_sources': raw_summaries,
          'selected_flags': dict(Counter(str(r['raw']['Flags']) for r in selected)),
          'metrics_ms': metrics,
          'warning': 'GPU completion after swap is a latency proxy, not measured GPU execution. '
                     'Server content and actual key dispatch cadence can differ across runs.'}
# SurfaceFlinger Android 11 FrameTracker::dumpStats writes desired, actual,
# ready timestamps. Dedupe its ring-buffer snapshots before taking intervals.
present = set()
for path in args.run.glob('trial-*-surface-latency.txt'):
    for line in path.read_text().splitlines():
        cells = line.split()
        if len(cells) != 3 or not all(c.isdigit() for c in cells):
            continue
        actual = int(cells[1])
        if 0 < actual < 9_000_000_000_000_000_000:
            present.add(actual)
intervals = [ns / 1_000_000 for ns in presentation_intervals_ns(present, windows)]
report['actual_present_interval_ms'] = distribution(intervals)
report['actual_present_gaps_over_45ms'] = sum(v > 45 for v in intervals)
report['presentation_scope_note'] = ('Key windows include focus animation and short settled tails; '
    '30fps artwork can intentionally present every 33ms. Large gaps identify stalls; '
    'this mixed workload is not a continuous-scroll FPS guarantee.')
if args.scroll_trace:
    assert args.trace_processor, '--trace-processor is required for exact scroll intervals'
    sql = """WITH delta AS (
      SELECT ts-clock_value n FROM clock_snapshot WHERE clock_name='MONOTONIC' ORDER BY id LIMIT 1
    ) SELECT s.ts-delta.n start_ns,s.ts+s.dur-delta.n end_ns
      FROM slice s,delta WHERE s.name='AMATV:home-scroll' AND s.dur>0 ORDER BY s.ts;"""
    query = subprocess.run([sys.executable,str(args.trace_processor),'query',str(args.scroll_trace),
                            '--format','csv',sql],capture_output=True,text=True,check=True,timeout=30)
    scroll = [(int(x['start_ns']),int(x['end_ns'])) for x in csv.DictReader(io.StringIO(query.stdout))]
    assert scroll, 'Trace has no benchmark Home-scroll intervals'
    scrolling = [ns / 1_000_000 for ns in presentation_intervals_ns(present, scroll)]
    report['scroll_windows_monotonic_ns'] = scroll
    report['scroll_actual_present_interval_ms'] = distribution(scrolling)
    report['scroll_present_gaps_over_45ms'] = sum(v > 45 for v in scrolling)
(args.run / 'active-frames.json').write_text(json.dumps(report, indent=2) + '\n')
for name in ('frame_total_wall', 'ui_wall', 'rt_command_issue_wall', 'gpu_completion_after_swap_proxy'):
    d = metrics.get(name, {})
    print(name, {k: d.get(k) for k in ('n', 'p50', 'p90', 'p95')})

print('actual_present_interval_ms', report['actual_present_interval_ms'])
print('actual_present_gaps_over_45ms', report['actual_present_gaps_over_45ms'])

if args.scroll_trace:
    print('scroll_actual_present_interval_ms', report['scroll_actual_present_interval_ms'])
    print('scroll_present_gaps_over_45ms', report['scroll_present_gaps_over_45ms'])
