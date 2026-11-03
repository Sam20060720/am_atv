#!/usr/bin/env python3
"""Validate and install one APK + AGP-generated, SDK-matched DexMetadata.

Also copied as install.py inside the standalone distribution kit. Uses only the
Python standard library. No compiler override, profile reset or app-data clear.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import shlex
import struct
import subprocess
import sys
import uuid
import xml.etree.ElementTree as ET
import zipfile
import zlib

FORMAT = "amatv-install-kit-v1"
ANDROID_NS = "http://schemas.android.com/apk/res/android"


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def contained(base: Path, relative: str) -> Path:
    path = (base / relative).resolve()
    if not path.is_relative_to(base.resolve()):
        raise ValueError(f"Artifact escapes its directory: {relative}")
    return path


def apk_identity(binary: bytes) -> dict:
    """Read package/version directly from Android's binary XML manifest."""
    if binary.lstrip().startswith(b"<"):
        root = ET.fromstring(binary)
        return {"package_name": root.attrib["package"],
                "version_code": int(root.attrib[f"{{{ANDROID_NS}}}versionCode"]),
                "version_name": root.attrib.get(f"{{{ANDROID_NS}}}versionName", "")}
    if len(binary) < 8 or struct.unpack_from("<H", binary)[0] != 3:
        raise ValueError("APK manifest is not Android XML")
    strings = []
    at = struct.unpack_from("<H", binary, 2)[0]
    while at + 8 <= len(binary):
        kind, header, size = struct.unpack_from("<HHI", binary, at)
        if size < header or header < 8 or at + size > len(binary):
            raise ValueError("Invalid manifest XML chunk bounds")
        if kind == 1:
            count, _, flags, start, _ = struct.unpack_from("<IIIII", binary, at + 8)
            if count > 100_000 or header + count * 4 > size:
                raise ValueError("Invalid manifest string pool")
            def length(pos: int, wide: bool) -> tuple[int, int]:
                unit = 2 if wide else 1
                value = int.from_bytes(binary[pos:pos + unit], "little")
                pos += unit
                high = 0x8000 if wide else 0x80
                if value & high:
                    value = ((value & ~high) << (16 if wide else 8)) | int.from_bytes(binary[pos:pos + unit], "little")
                    pos += unit
                return value, pos
            for index in range(count):
                pos = at + start + struct.unpack_from("<I", binary, at + header + index * 4)[0]
                if flags & 0x100:
                    _, pos = length(pos, False)
                    byte_count, pos = length(pos, False)
                    value = binary[pos:pos + byte_count].decode("utf-8")
                else:
                    chars, pos = length(pos, True)
                    value = binary[pos:pos + chars * 2].decode("utf-16-le")
                strings.append(value)
        elif kind == 0x102:
            _, name = struct.unpack_from("<II", binary, at + 16)
            attr_start, attr_size, attr_count = struct.unpack_from("<HHH", binary, at + 24)
            if strings[name] == "manifest":
                values = {}
                if attr_size < 20 or 16 + attr_start + attr_size * attr_count > size:
                    raise ValueError("Invalid manifest attributes")
                for index in range(attr_count):
                    pos = at + 16 + attr_start + index * attr_size
                    namespace, attribute, raw = struct.unpack_from("<III", binary, pos)
                    value_type, value = binary[pos + 15], struct.unpack_from("<I", binary, pos + 16)[0]
                    key = strings[attribute]
                    ns = None if namespace == 0xFFFFFFFF else strings[namespace]
                    if (key == "package" and ns is None) or (key in {"versionCode", "versionCodeMajor", "versionName"} and ns == ANDROID_NS):
                        values[key] = strings[raw] if raw != 0xFFFFFFFF else strings[value] if value_type == 3 else value
                return {"package_name": values["package"],
                        "version_code": (int(values.get("versionCodeMajor", 0)) << 32) | int(values["versionCode"]),
                        "version_name": str(values.get("versionName", ""))}
        at += size
    raise ValueError("APK manifest root identity missing")


def profile_entries(binary: bytes) -> tuple[str, list[dict]]:
    if binary[:4] != b"pro\0":
        raise ValueError("Profile magic mismatch")
    version = binary[4:8]
    entries = []
    if version == b"010\0":
        count = binary[8]
        inflated, compressed = struct.unpack_from("<II", binary, 9)
        body = zlib.decompress(binary[17:17 + compressed])
        if len(body) != inflated:
            raise ValueError("Profile 010 inflated size mismatch")
        at = 0
        for _ in range(count):
            size, classes, hot, checksum, methods = struct.unpack_from("<HHIII", body, at)
            at += 16
            key = body[at:at + size].decode("utf-8")
            at += size
            entries.append({"key": key, "checksum": checksum, "methods": methods})
    elif version == b"015\0":
        count = struct.unpack_from("<I", binary, 8)[0]
        if not 1 <= count <= 32:
            raise ValueError("Invalid profile section count")
        sections = {}
        for index in range(count):
            kind, start, size, inflated = struct.unpack_from("<IIII", binary, 12 + index * 16)
            if start < 12 + 16 * count or start + size > len(binary):
                raise ValueError("Invalid profile section bounds")
            body = binary[start:start + size]
            if inflated:
                body = zlib.decompress(body)
                if len(body) != inflated:
                    raise ValueError("Profile 015 inflated size mismatch")
            if kind in sections:
                raise ValueError("Repeated profile section")
            sections[kind] = body
        body = sections[0]
        count = struct.unpack_from("<H", body)[0]
        at = 2
        for _ in range(count):
            checksum, types, methods, size = struct.unpack_from("<IIIH", body, at)
            at += 14
            key = body[at:at + size].decode("utf-8")
            at += size
            entries.append({"key": key, "checksum": checksum, "methods": methods, "types": types})
        if at != len(body):
            raise ValueError("Trailing profile DEX section bytes")
    else:
        raise ValueError(f"Unsupported profile version {version!r}; do not blindly repackage it")
    if not entries or len(entries) > 1000:
        raise ValueError("Empty/invalid profile DEX list")
    return version.rstrip(b"\0").decode("ascii"), entries


def dex_name(profile_key: str) -> str:
    tail = re.split(r"[!:]", profile_key)[-1]
    if re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", tail):
        return tail
    if tail.endswith(".apk"):
        return "classes.dex"
    raise ValueError(f"Unknown profile DEX key: {profile_key}")


def validate_pair(apk: Path, dm: Path, min_api: int, max_api: int) -> dict:
    expected = "010" if 28 <= min_api <= max_api <= 30 else "015" if min_api >= 31 else None
    if expected is None:
        raise ValueError("Unsupported profile SDK range")
    with zipfile.ZipFile(apk) as package, zipfile.ZipFile(dm) as metadata:
        identity = apk_identity(package.read("AndroidManifest.xml"))
        names = metadata.namelist()
        if len(names) != len(set(names)) or set(names) != {"primary.prof", "primary.profm"}:
            raise ValueError("Expected the two unchanged AGP DexMetadata profile entries")
        profile = metadata.read("primary.prof")
        profile_version, entries = profile_entries(profile)
        if profile_version != expected:
            raise ValueError(f"SDK {min_api}..{max_api} needs {expected}, got {profile_version}")
        baseline = package.read("assets/dexopt/baseline.prof")
        asset_version, asset_entries = profile_entries(baseline)
        if asset_version != "010" or (expected == "010" and profile != baseline):
            raise ValueError("DM profile is not this APK's unchanged AGP baseline")
        meta = metadata.read("primary.profm")
        if meta[:8] != b"prm\x00002\x00" or meta != package.read("assets/dexopt/baseline.profm"):
            raise ValueError("DM metadata version/content does not match APK baseline metadata")
        canonical = {}
        for entry in entries:
            name = dex_name(entry["key"])
            if name in canonical:
                raise ValueError("Duplicate profile DEX key")
            dex = package.read(name)
            if dex[:4] != b"dex\n" or len(dex) < 112:
                raise ValueError("Expected a regular APK DEX header")
            # ART's location checksum for an APK DEX is the ZIP entry CRC32,
            # NOT the DEX header's Adler32 checksum at offset 8.
            methods = struct.unpack_from("<I", dex, 88)[0]
            if entry["checksum"] != package.getinfo(name).CRC or entry["methods"] != methods:
                raise ValueError(f"Profile/DEX checksum or method-count mismatch: {name}")
            if "types" in entry and entry["types"] != struct.unpack_from("<I", dex, 64)[0]:
                raise ValueError("Profile/DEX type-count mismatch")
            canonical[name] = (entry["checksum"], entry["methods"])
        if canonical != {dex_name(row["key"]): (row["checksum"], row["methods"]) for row in asset_entries}:
            raise ValueError("DM and APK baseline refer to different DEX files")
    return dict(identity, profile_version=profile_version, metadata_version="002", dex_entries=entries)


def validate_kit(directory: Path) -> tuple[dict, Path]:
    manifest = json.loads((directory / "manifest.json").read_text())
    if manifest.get("format") != FORMAT:
        raise ValueError("Not an AMATV installation kit")
    apk = contained(directory, manifest["apk"]["path"])
    if digest(apk) != manifest["apk"]["sha256"]:
        raise ValueError("APK SHA-256 mismatch")
    intervals = []
    for profile in manifest["profiles"]:
        dm = contained(directory, profile["path"])
        if digest(dm) != profile["sha256"]:
            raise ValueError("DM SHA-256 mismatch")
        proof = validate_pair(apk, dm, profile["min_api"], profile["max_api"])
        for field in ("package_name", "version_code", "version_name"):
            if proof[field] != manifest[field]:
                raise ValueError(f"Kit/APK identity mismatch: {field}")
        intervals.append((profile["min_api"], profile["max_api"]))
    intervals.sort()
    if not intervals or any(intervals[i][0] <= intervals[i - 1][1] for i in range(1, len(intervals))):
        raise ValueError("Missing or overlapping SDK profile ranges")
    return manifest, apk


def find_adb(explicit: str | None) -> str:
    if explicit:
        return explicit
    found = shutil.which("adb")
    if found:
        return found
    for root in [os.environ.get("ANDROID_SDK_ROOT"), os.environ.get("ANDROID_HOME"), str(Path.home() / "Library/Android/sdk")]:
        if root:
            path = Path(root) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
            if path.is_file():
                return str(path)
    raise ValueError("adb is missing; install Android SDK Platform-Tools or pass --adb")


# Each ADB child has a deadline, including failure cleanup. Never restart a
# transport here: another process may be using the same TV or ADB server.
ADB_TIMEOUT = 30
TRANSFER_TIMEOUT = 45
CLEANUP_TIMEOUT = 15


def run_adb(command: list[str], args: list[str], timeout: int = ADB_TIMEOUT) -> str:
    result = subprocess.run(command + args, check=True, capture_output=True,
                            text=True, stdin=subprocess.DEVNULL, timeout=timeout)
    return result.stdout.strip()


def device_shell(command: list[str], *args: str, timeout: int = ADB_TIMEOUT) -> str:
    # adb shell joins arguments before remote execution; quote for that shell.
    return run_adb(command, ["shell", shlex.join(args)], timeout)


def cleanup_shell(command: list[str], *args: str) -> None:
    try:
        device_shell(command, *args, timeout=CLEANUP_TIMEOUT)
    except (OSError, subprocess.SubprocessError) as error:
        print(f"Cleanup could not finish ({shlex.join(args)}): {error}", file=sys.stderr)


@contextmanager
def remote_temporary(command: list[str]):
    remote = "/data/local/tmp/amatv-install-" + uuid.uuid4().hex
    # Do not adopt an existing directory, even in the unlikely case of a name
    # collision. Only a successful exclusive mkdir authorizes later removal.
    device_shell(command, "mkdir", remote)
    try:
        yield remote
    finally:
        cleanup_shell(command, "rm", "-rf", remote)


def verify_remote(command: list[str], remote: str, expected: str) -> None:
    output = device_shell(command, "sha256sum", remote)
    actual = output.split(maxsplit=1)[0] if output else ""
    if not re.fullmatch(r"[0-9a-fA-F]{64}", actual) or actual.lower() != expected:
        raise ValueError(f"Remote SHA-256 mismatch: {remote}")


def push_verified(command: list[str], source: Path, remote: str) -> str:
    expected = digest(source)
    run_adb(command, ["push", "-Z", str(source.resolve()), remote], TRANSFER_TIMEOUT)
    verify_remote(command, remote, expected)
    return expected


def require_success(output: str) -> None:
    # Some Android package-manager versions print Failure while exiting zero.
    if not re.fullmatch(r"Success(?:[: ][^\n]*)?", output):
        raise ValueError(f"Package manager did not confirm success: {output}")


def session_id(output: str | bytes | None) -> str | None:
    if isinstance(output, bytes):
        output = output.decode("utf-8", errors="replace")
    match = re.fullmatch(r"Success: created install session \[([0-9]+)\]", (output or "").strip())
    return match.group(1) if match else None


def install_pair(command: list[str], apk: Path, dm: Path, package_name: str) -> None:
    with remote_temporary(command) as remote:
        apk_hash = push_verified(command, apk, remote + "/base.apk")
        dm_hash = push_verified(command, dm, remote + "/base.dm")
        session = None
        try:
            try:
                output = device_shell(command, "pm", "install-create", "-r", "-S",
                                      str(apk.stat().st_size + dm.stat().st_size))
            except (subprocess.TimeoutExpired, subprocess.CalledProcessError) as error:
                # A timed-out client can still have received its own session ID.
                # Never infer ownership from unrelated IDs or session listings.
                session = session_id(error.stdout)
                raise
            session = session_id(output)
            if session is None:
                raise ValueError(f"Package manager returned no unambiguous session ID: {output}")
            for source, name in [(apk, "base.apk"), (dm, "base.dm")]:
                require_success(device_shell(command, "pm", "install-write", "-S",
                                              str(source.stat().st_size), session, name,
                                              remote + "/" + name))
            require_success(device_shell(command, "pm", "install-commit", session,
                                          timeout=TRANSFER_TIMEOUT))
            session = None  # The confirmed commit consumes this session.
        finally:
            if session is not None:
                cleanup_shell(command, "pm", "install-abandon", session)
        paths = device_shell(command, "pm", "path", package_name).splitlines()
        bases = [line.removeprefix("package:") for line in paths
                 if line.startswith("package:/") and line.endswith("/base.apk")]
        if len(bases) != 1:
            raise ValueError("Installation committed, but installed base APK path is ambiguous")
        verify_remote(command, bases[0], apk_hash)
        verify_remote(command, bases[0][:-4] + ".dm", dm_hash)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kit", type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument("--serial", help="Explicit adb device serial (connect the TV first)")
    parser.add_argument("--adb")
    parser.add_argument("--validate-only", action="store_true")
    args = parser.parse_args()
    manifest, apk = validate_kit(args.kit)
    if args.validate_only:
        print(f"Validated {manifest['package_name']} {manifest['version_name']} and all SDK profile pairs")
        return
    if not args.serial:
        parser.error("--serial is required for installation")
    adb = find_adb(args.adb)
    command = [adb, "-s", args.serial]
    sdk = int(device_shell(command, "getprop", "ro.build.version.sdk"))
    matches = [p for p in manifest["profiles"] if p["min_api"] <= sdk <= p["max_api"]]
    if len(matches) != 1:
        raise ValueError(f"No single compatible DexMetadata for device SDK {sdk}")
    selected = matches[0]
    dm = contained(args.kit, selected["path"])
    print(f"Installing {manifest['version_name']} with {selected['profile_version']} profile for SDK {sdk}", flush=True)
    install_pair(command, apk, dm, manifest["package_name"])
    print("APK + matching baseline profile installed; existing app data retained.")


if __name__ == "__main__":
    main()
