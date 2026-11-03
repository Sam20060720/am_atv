#!/usr/bin/env python3
"""One six-second real-TV lyric window on the existing paused song; exact cursor cleanup."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import time

from physical_artifacts import download_exact_file, download_result_directory

ROOT = Path(__file__).resolve().parents[2]
TEST = 'dev.amatv.benchmark'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--hardware', required=True)
    parser.add_argument('--apk-sha256', required=True)
    parser.add_argument('--run-id', required=True)
    parser.add_argument('--expected-current-title', help='Authorize cold preparation of this exact retained song through its Play button')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--installed-tester-sha256', help='Require this installed tester digest; otherwise record and reuse its actual installed digest')
    parser.add_argument('--update-tester', action='store_true', help='Explicitly permit updating only the independent tester')
    parser.add_argument('--reconnect-transport', action='store_true', help='Explicitly reopen only this serial transport; never restarts the global ADB server')
    parser.add_argument('--tester', type=Path, default=ROOT / 'benchmark/build/outputs/apk/benchmark/benchmark-benchmark.apk')
    args = parser.parse_args()
    assert re.fullmatch(r'[A-Za-z0-9_.-]+', args.run_id)
    assert re.fullmatch(r'[a-f0-9]{64}', args.apk_sha256)
    args.output.mkdir(parents=True, exist_ok=True)
    assert not (args.output / args.run_id).exists()
    adb = str(Path.home() / 'Library/Android/sdk/platform-tools/adb')
    prefix = [adb, '-s', args.serial]

    def command(*argv, timeout=15, data=None):
        result = subprocess.run(prefix + list(argv), input=data, capture_output=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError(result.stderr.decode(errors='replace')[-1500:])
        return result.stdout.decode(errors='replace')

    def fresh():
        if not args.reconnect_transport:
            return
        subprocess.run([adb, 'disconnect', args.serial], capture_output=True, check=True, timeout=10)
        time.sleep(.4)
        subprocess.run([adb, 'connect', args.serial], capture_output=True, check=True, timeout=10)

    fresh()
    assert command('shell', 'getprop ro.hardware').strip() == args.hardware
    def digest(package):
        paths = command('shell', 'pm path ' + package).splitlines()
        path = next((p[8:] for p in paths if p.startswith('package:') and p.endswith('/base.apk')), None)
        return command('shell', 'sha256sum ' + path).split()[0] if path else None
    assert digest('dev.amatv') == args.apk_sha256, 'Installed target mismatch'
    tester = args.tester
    installed_hash = digest(TEST)
    if args.update_tester:
        assert not args.installed_tester_sha256, '--update-tester cannot change an explicitly pinned installed tester'
        tester_hash = hashlib.sha256(tester.read_bytes()).hexdigest()
        tester_updated = installed_hash != tester_hash
        if tester_updated:
            fresh()
            command('install', '--no-streaming', '-r', str(tester), timeout=60)
    else:
        tester_hash = args.installed_tester_sha256 or installed_hash
        tester_updated = False
        assert tester_hash, 'No installed tester; install it explicitly or opt into --update-tester'
    assert re.fullmatch(r'[a-f0-9]{64}', tester_hash)
    assert digest(TEST) == tester_hash, 'Installed tester APK mismatch'
    remote = f'/sdcard/Android/data/{TEST}/files/physical-lyrics/{args.run_id}'
    assert 'EXISTS' not in command('shell', f'if test -e {remote}; then echo EXISTS; fi')
    params = {'class': TEST + '.PhysicalLyricsFrameTest', 'allow_physical_lyrics': '1',
              'expected_hardware': args.hardware, 'external_trace': '1', 'run_id': args.run_id}
    if args.expected_current_title:
        params['expected_current_title'] = args.expected_current_title
    argv = ['shell', 'am', 'instrument', '-w', '-r']
    for key, value in params.items():
        argv += ['-e', key, value]
    argv += [TEST + '/androidx.test.runner.AndroidJUnitRunner']
    trace = f'/data/misc/perfetto-traces/amatv-{args.run_id}.pftrace'
    config = '''buffers { size_kb: 32768 fill_policy: RING_BUFFER }
    buffers { size_kb: 4096 fill_policy: RING_BUFFER }
    duration_ms: 10000
    data_sources { config { name: "linux.ftrace" ftrace_config {
      ftrace_events: "sched/sched_switch" ftrace_events: "sched/sched_waking"
      ftrace_events: "power/cpu_frequency" ftrace_events: "power/cpu_idle"
      atrace_categories: "gfx" atrace_categories: "view" atrace_categories: "dalvik"
      atrace_apps: "dev.amatv"
    } } }
    data_sources { config { name: "linux.process_stats" target_buffer: 1 process_stats_config { scan_all_processes_on_start: true } } }
    data_sources { config { name: "android.surfaceflinger.frametimeline" target_buffer: 1 } }
    '''
    metadata = dict(vars(args), tester_sha256=tester_hash, tester_updated=tester_updated,
                    target_installed_or_cleared=False, trace_completion_guard='owned_foreground_consumer_exit')
    (args.output / (args.run_id + '-host.json')).write_text(json.dumps(metadata, default=str, indent=2))
    with (args.output / (args.run_id + '.log')).open('w') as log, \
            (args.output / (args.run_id + '-perfetto.log')).open('wb') as trace_log:
        process = subprocess.Popen(prefix + ['shell', shlex.join(argv[1:])], stdout=log, stderr=subprocess.STDOUT)
        started = None
        capture = None
        try:
            deadline = time.monotonic() + 90
            while 'READY' not in command('shell', f'if test -f {remote}/trace-ready; then echo READY; fi'):
                if process.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError('Lyrics setup did not reach measurement barrier; see log')
                time.sleep(.5)
            # A foreground consumer exits only after the final output is closed.
            # Waiting for that owned process avoids the background file-size=0
            # race without guessing a longer post-measurement sleep.
            capture = subprocess.Popen(prefix + ['shell', shlex.join(
                ['perfetto', '--txt', '-c', '-', '-o', trace])], stdin=subprocess.PIPE,
                stdout=trace_log, stderr=subprocess.STDOUT)
            capture.stdin.write(config.encode())
            capture.stdin.close()
            started = time.monotonic()
            time.sleep(1.25)  # Android 11 has no --background-wait.
            command('shell', f'touch {remote}/trace-go')
            process.wait(timeout=45)
        finally:
            # Let the tester's own finally restore the exact existing cursor.
            if process.poll() is None:
                process.wait(timeout=30)
            if started is not None:
                try:
                    capture.wait(timeout=max(.1, started + 30 - time.monotonic()))
                except subprocess.TimeoutExpired:
                    capture.terminate()
                    capture.wait(timeout=5)
                    raise
                assert capture.returncode == 0, 'Perfetto failed; see the owned capture log'
            fresh()
            download_result_directory(prefix, remote, args.output, args.run_id, timeout=35)
            if started is not None:
                download_exact_file(prefix, trace, args.output / (args.run_id + '.pftrace'), timeout=35)
            report = json.loads((args.output / args.run_id / 'result.json').read_text())
            print(json.dumps(report, ensure_ascii=False, indent=2))
            assert report['status'] == 'passed', report.get('error', report)
            assert report['cleanup']['status'] == 'passed', report['cleanup']


if __name__ == '__main__':
    main()
