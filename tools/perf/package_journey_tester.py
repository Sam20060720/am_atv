#!/usr/bin/env python3
"""Package a physical ARM32 UI-only tester without unused tracing executables.

This changes the independent tester, never the app under test. Use the original
Gradle tester APK for Perfetto/Macrobenchmark; this package is for UI journeys.
"""
import argparse
from pathlib import Path
import subprocess
import tempfile
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    build_tools = Path.home() / 'Library/Android/sdk/build-tools/35.0.0'
    with tempfile.TemporaryDirectory(prefix='amatv-ui-tester-') as directory:
        raw, aligned = Path(directory) / 'raw.apk', Path(directory) / 'aligned.apk'
        with zipfile.ZipFile(args.source) as source, zipfile.ZipFile(raw, 'w') as target:
            for info in source.infolist():
                name = info.filename
                signature = name.startswith('META-INF/') and (name.upper().endswith(('.SF', '.RSA', '.DSA', '.EC')) or name.upper() == 'META-INF/MANIFEST.MF')
                if signature or name.startswith(('assets/trace_processor_shell_', 'assets/tracebox_', 'lib/arm64-v8a/')):
                    continue
                data = source.read(name)
                if name.endswith('.dex'):
                    info.compress_type = zipfile.ZIP_DEFLATED
                target.writestr(info, data)
        subprocess.run([str(build_tools / 'zipalign'), '-P', '16', '4', str(raw), str(aligned)], check=True)
        subprocess.run([str(build_tools / 'apksigner'), 'sign', '--ks', str(Path.home() / '.android/debug.keystore'),
                        '--ks-key-alias', 'androiddebugkey', '--ks-pass', 'pass:android', '--key-pass', 'pass:android',
                        '--out', str(args.output), str(aligned)], check=True)
    subprocess.run([str(build_tools / 'apksigner'), 'verify', str(args.output)], check=True)
    print(f'UI-only ARM32 tester: {args.output.stat().st_size} bytes (original {args.source.stat().st_size})')


if __name__ == '__main__':
    main()
