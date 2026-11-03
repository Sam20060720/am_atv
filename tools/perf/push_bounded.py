#!/usr/bin/env python3
"""Bounded, uncompressed, hash-verified ADB transfer with atomic destination replacement."""
import argparse
from pathlib import Path
import re
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from standalone_install import device_shell, find_adb, push_verified, remote_temporary


def transfer(command: list[str], source: Path, dest: str) -> str:
    if not re.fullmatch(r"/data/local/tmp/amatv-[A-Za-z0-9_.-]+", dest):
        raise ValueError("Destination must be an AMATV file under /data/local/tmp")
    with remote_temporary(command) as remote:
        staged = remote + "/payload"
        expected = push_verified(command, source, staged)
        # Keep any existing destination until the complete replacement verifies.
        device_shell(command, "mv", "-f", staged, dest)
    return expected


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--dest', required=True)
    parser.add_argument('--adb')
    args = parser.parse_args()
    command = [find_adb(args.adb), '-s', args.serial]
    expected = transfer(command, args.source, args.dest)
    print('SHA-256 verified: ' + expected)


if __name__ == '__main__':
    main()
