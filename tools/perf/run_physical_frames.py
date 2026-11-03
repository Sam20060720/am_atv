#!/usr/bin/env python3
"""Bounded, warmed DPAD sample of the installed APK. Never installs/clears the target.

Requires an explicit device hardware and installed APK hash. The installed independent
navigation tester is reused by default; --update-tester explicitly permits its update. Perfetto runs from the host after a ready/GO
handshake, so its interval includes the measured inputs rather than setup.
"""
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
TARGET = 'dev.amatv'
TEST = 'dev.amatv.benchmark'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default=str(Path.home() / 'Library/Android/sdk/platform-tools/adb'))
    parser.add_argument('--serial', required=True)
    parser.add_argument('--hardware', required=True)
    parser.add_argument('--apk-sha256', required=True)
    parser.add_argument('--run-id', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--axis', choices=('vertical', 'horizontal'), default='vertical')
    parser.add_argument('--start-shelf', choices=('0', '1'), default='0')
    parser.add_argument('--start-item', type=int, choices=range(5), default=0)
    parser.add_argument('--check-menu', action='store_true')
    parser.add_argument('--installed-tester-sha256', help='Require this installed tester digest; otherwise record and reuse its actual installed digest')
    parser.add_argument('--update-tester', action='store_true', help='Explicitly permit updating only the independent tester from the local build')
    parser.add_argument('--reconnect-transport', action='store_true', help='Explicitly reopen only this serial transport; never restarts the global ADB server')
    parser.add_argument('--compose-trace', action='store_true')
    parser.add_argument('--java-sampling', action='store_true', help='Attribution only; adds ART sampling overhead')
    args = parser.parse_args()
    assert re.fullmatch(r'[A-Za-z0-9_.-]+', args.run_id)
    assert re.fullmatch(r'[a-f0-9]{64}', args.apk_sha256)
    args.output.mkdir(parents=True, exist_ok=True)
    assert not (args.output / args.run_id).exists(), 'Local run already exists; choose a new run ID'
    assert not (args.output / (args.run_id + '-host.json')).exists(), 'Local metadata already exists; choose a new run ID'
    prefix = [args.adb, '-s', args.serial]

    def command(*argv, timeout=10, data=None):
        result = subprocess.run(prefix + list(argv), input=data, capture_output=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError(result.stderr.decode(errors='replace')[-1500:])
        return result.stdout.decode(errors='replace')

    def fresh_transport():
        if not args.reconnect_transport:
            return
        # This vendor's sync channel can remain stale after `adb reconnect`
        # even while shell works. Replace only this device's transport.
        subprocess.run([args.adb, 'disconnect', args.serial], check=True, capture_output=True, timeout=10)
        time.sleep(.4)
        subprocess.run([args.adb, 'connect', args.serial], check=True, capture_output=True, timeout=10)

    fresh_transport()
    assert command('shell', 'getprop ro.hardware').strip() == args.hardware
    base = next(x[8:] for x in command('shell', 'pm path ' + TARGET).splitlines()
                if x.startswith('package:') and x.endswith('/base.apk'))
    assert command('shell', 'sha256sum ' + base).split()[0] == args.apk_sha256, 'Installed APK mismatch'
    remote = f'/sdcard/Android/data/{TEST}/files/physical-frames/{args.run_id}'
    assert 'EXISTS' not in command('shell', f'if test -e {remote}; then echo EXISTS; fi'), 'Run ID already exists'
    test_apk = ROOT / 'benchmark/build/outputs/apk/benchmark/benchmark-benchmark.apk'
    def installed_tester_digest():
        paths = command('shell', 'pm path ' + TEST).splitlines()
        installed = next((p[8:] for p in paths if p.startswith('package:') and p.endswith('/base.apk')), None)
        return command('shell', 'sha256sum ' + installed).split()[0] if installed else None
    installed_digest = installed_tester_digest()
    if args.update_tester:
        assert not args.installed_tester_sha256, '--update-tester cannot change an explicitly pinned installed tester'
        tester_digest = hashlib.sha256(test_apk.read_bytes()).hexdigest()
        tester_updated = installed_digest != tester_digest
        if tester_updated:
            fresh_transport()
            command('install', '--no-streaming', '-r', str(test_apk), timeout=40)
    else:
        tester_digest = args.installed_tester_sha256 or installed_digest
        tester_updated = False
        assert tester_digest, 'No installed tester; install it explicitly or opt into --update-tester'
    assert re.fullmatch(r'[a-f0-9]{64}', tester_digest)
    assert installed_tester_digest() == tester_digest, 'Installed tester APK mismatch'
    compilation = re.findall(r'\[status=([^\]]+)\]\s*\[reason=([^\]]+)\]',
                             command('shell', 'dumpsys package ' + TARGET))
    options = {'class': TEST + '.PhysicalTvFrameTest', 'allow_physical_frames': '1',
               'expected_hardware': args.hardware, 'axis': args.axis, 'start_shelf': args.start_shelf,
               'start_item': str(args.start_item),
               'warm_trace': '1', 'external_trace': '1', 'run_id': args.run_id}
    if args.check_menu:
        options['check_context_menu'] = '1'
    argv = ['shell', 'am', 'instrument', '-w', '-r']
    for key, value in options.items():
        argv += ['-e', key, value]
    argv += [TEST + '/androidx.test.runner.AndroidJUnitRunner']
    trace_path = f'/data/misc/perfetto-traces/amatv-{args.run_id}.pftrace'
    config = '''buffers { size_kb: 32768 fill_policy: RING_BUFFER }
    buffers { size_kb: 4096 fill_policy: RING_BUFFER }
    duration_ms: 8000
    data_sources { config { name: "linux.ftrace" ftrace_config {
      ftrace_events: "sched/sched_switch" ftrace_events: "sched/sched_waking"
      atrace_categories: "gfx" atrace_categories: "view" atrace_apps: "dev.amatv"
    } } }
    data_sources { config { name: "linux.process_stats" target_buffer: 1 process_stats_config { scan_all_processes_on_start: true } } }
    data_sources { config { name: "android.surfaceflinger.frametimeline" target_buffer: 1 } }
    '''
    if args.compose_trace:
        config += '''data_sources { config { name: "track_event" track_event_config {
          disabled_categories: "*" enabled_categories: "rendering"
        } } }'''
    metadata = dict(vars(args));metadata.update(test_apk_sha256=tester_digest, tester_updated=tester_updated,
                                               compilation_state=compilation,
                                               trace_clock='boottime', gfx_clock='monotonic',
                                               trace_completion_guard='owned_foreground_consumer_exit')
    (args.output / (args.run_id + '-host.json')).write_text(json.dumps(metadata, default=str, indent=2))
    sampling_active = False
    sampling_path = '/data/local/tmp/amatv-' + args.run_id + '.methodtrace'
    with (args.output / (args.run_id + '.log')).open('w') as log, \
            (args.output / (args.run_id + '-perfetto.log')).open('wb') as trace_log:
        process = subprocess.Popen(prefix + argv, stdout=log, stderr=subprocess.STDOUT)
        capture = None
        try:
            ready_deadline = time.monotonic() + 75
            while 'READY' not in command('shell', f'if test -f {remote}/trace-ready; then echo READY; fi'):
                if process.poll() is not None or time.monotonic() > ready_deadline:
                    raise RuntimeError('Test did not reach its warm measurement barrier; see log')
                time.sleep(.5)
            if args.compose_trace:
                enabled = command('shell', 'am broadcast -a androidx.tracing.perfetto.action.ENABLE_TRACING '
                                  '-n dev.amatv/androidx.tracing.perfetto.TracingReceiver')
                assert re.search(r'result=[12]\b', enabled), enabled
                print(enabled, flush=True)
            if args.java_sampling:
                print(command('shell', f'am profile start --sampling 1000 {TARGET} {sampling_path}'), flush=True)
                sampling_active = True
            # Keep the owned consumer in the foreground. Its process exit closes
            # the final trace file; a duration-based sleep cannot prove that a
            # background consumer has finished draining/flushing its buffers.
            capture = subprocess.Popen(prefix + ['shell', shlex.join(
                ['perfetto', '--txt', '-c', '-', '-o', trace_path])], stdin=subprocess.PIPE,
                stdout=trace_log, stderr=subprocess.STDOUT)
            capture.stdin.write(config.encode())
            capture.stdin.close()
            trace_started = time.monotonic()
            # Android 11 lacks --background-wait. The vendor can dispatch this
            # shell later than the GO shell, so a host sleep is not readiness.
            # Wait for this owned consumer's service connection before GO.
            start_deadline = time.monotonic() + 10
            while b'Connected to the Perfetto traced service' not in (args.output / (args.run_id + '-perfetto.log')).read_bytes():
                if capture.poll() is not None or time.monotonic() >= start_deadline:
                    raise RuntimeError('Owned Perfetto did not confirm service startup; see capture log')
                time.sleep(.05)
            command('shell', f'touch {remote}/trace-go')
            process.wait(timeout=45)
            if sampling_active:
                command('shell', 'am profile stop ' + TARGET)
                sampling_active = False
            capture.wait(timeout=max(.1, trace_started + 28 - time.monotonic()))
            assert capture.returncode == 0, 'Perfetto failed; see the owned capture log'
            fresh_transport()
            download_result_directory(prefix, remote, args.output, args.run_id, timeout=35)
            trace_file = args.output / (args.run_id + '.pftrace')
            fresh_transport()
            download_exact_file(prefix, trace_path, trace_file, timeout=35)
            if args.java_sampling:
                fresh_transport()
                download_exact_file(prefix, sampling_path, args.output / (args.run_id + '.methodtrace'), timeout=35)
            assert trace_file.stat().st_size > 1024, 'Incomplete Perfetto capture'
            report = json.loads((args.output / args.run_id / 'result.json').read_text())
            assert report['status'] == 'passed', report
            print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)
        finally:
            if sampling_active:
                command('shell', 'am profile stop ' + TARGET)
            if process.poll() is None:
                command('shell', 'am force-stop ' + TEST)
                process.terminate()
            if capture is not None and capture.poll() is None:
                try:
                    capture.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    capture.terminate()
                    capture.wait(timeout=5)


if __name__ == '__main__':
    main()
