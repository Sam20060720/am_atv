import importlib.util
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch
import subprocess
import io
import hashlib
import shlex
from contextlib import redirect_stdout
import zipfile
import zlib

TOOLS = Path(__file__).parents[1]
sys.path.insert(0, str(TOOLS))
import standalone_install as installer
from perf import push_bounded
SPEC = importlib.util.spec_from_file_location("package_standalone", TOOLS / "package-standalone.py")
packager = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(packager)


def profile010(checksum, methods=10):
    key = b"classes.dex"
    body = struct.pack("<HHIII", len(key), 0, 0, checksum, methods) + key + bytes((methods * 2 + 7) // 8)
    compressed = zlib.compress(body)
    return b"pro\x00010\0" + bytes([1]) + struct.pack("<II", len(body), len(compressed)) + compressed


def profile015(checksum, methods=10, types=12):
    key = b"classes.dex"
    section = struct.pack("<HIIIH", 1, checksum, types, methods, len(key)) + key
    return b"pro\x00015\0" + struct.pack("<I", 1) + struct.pack("<IIII", 0, 28, len(section), 0) + section


def artifacts(directory):
    apk = directory / "app-standalone.apk"
    dex = bytearray(112)
    dex[:8] = b"dex\n039\0"
    struct.pack_into("<I", dex, 64, 12)
    struct.pack_into("<I", dex, 88, 10)
    checksum = zlib.crc32(dex)
    metadata = b"prm\x00002\0fixture"
    p010 = profile010(checksum)
    with zipfile.ZipFile(apk, "w") as zipped:
        zipped.writestr("classes.dex", dex)
        zipped.writestr("AndroidManifest.xml", '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.amatv" android:versionCode="15" android:versionName="0.6.2"/>')
        zipped.writestr("assets/dexopt/baseline.prof", p010)
        zipped.writestr("assets/dexopt/baseline.profm", metadata)
    for name, profile in [("api30.dm", p010), ("api31.dm", profile015(checksum))]:
        with zipfile.ZipFile(directory / name, "w") as zipped:
            zipped.writestr("primary.prof", profile)
            zipped.writestr("primary.profm", metadata)
    output = {"applicationId": "dev.amatv", "variantName": "standalone",
              "elements": [{"filters": [], "versionCode": 15, "versionName": "0.6.2", "outputFile": apk.name}],
              "baselineProfiles": [{"minApi": 28, "maxApi": 30, "baselineProfiles": ["api30.dm"]},
                                   {"minApi": 31, "maxApi": 2147483647, "baselineProfiles": ["api31.dm"]}]}
    path = directory / "output-metadata.json"
    path.write_text(json.dumps(output))
    return apk, path


class FakeDevice:
    """Stateful transport model: only this installer's permitted operations exist."""
    def __init__(self, sdk="30", fail=None):
        self.sdk = sdk
        self.fail = fail
        self.files = {}
        self.directories = set()
        self.sessions = {}
        self.abandoned = []
        self.committed = None
        self.replace = False
        self.created = []
        self.bounded = []

    def run(self, command, **kwargs):
        self.bounded.append(kwargs)
        if command[3] == "push":
            assert command[4] == "-Z"
            source, remote = command[5:]
            self.files[remote] = Path(source).read_bytes()
            if self.fail == "push-timeout":
                self.files[remote] = b"partial"
                raise subprocess.TimeoutExpired(command, kwargs["timeout"])
            output = "pushed"
        else:
            assert command[3] == "shell"
            args = shlex.split(command[4])
            if args[0] == "mkdir":
                if args[1] in self.directories:
                    raise subprocess.CalledProcessError(1, command)
                self.directories.add(args[1])
                self.created.append(args[1])
                output = ""
            elif args[:2] == ["rm", "-rf"]:
                if self.fail == "cleanup-timeout":
                    raise subprocess.TimeoutExpired(command, kwargs["timeout"])
                self.directories.remove(args[2])
                self.files = {p: b for p, b in self.files.items() if not p.startswith(args[2] + "/")}
                output = ""
            elif args[0] == "sha256sum":
                value = "0" * 64 if self.fail == "bad-hash" else hashlib.sha256(self.files[args[1]]).hexdigest()
                output = value + "  " + args[1]
            elif args[0] == "mv":
                self.files[args[3]] = self.files.pop(args[2])
                output = ""
            elif args[:2] == ["getprop", "ro.build.version.sdk"]:
                output = self.sdk
            elif args[:2] == ["pm", "install-create"]:
                self.replace = "-r" in args
                if self.fail == "bad-session":
                    output = "unrelated [77] Failure"
                else:
                    self.sessions["42"] = {}
                    output = "Success: created install session [42]"
                    if self.fail == "create-timeout-with-id":
                        raise subprocess.TimeoutExpired(command, kwargs["timeout"], output=output.encode())
            elif args[:2] == ["pm", "install-write"]:
                session, name, path = args[4:]
                if self.fail in {"write-timeout", "cleanup-timeout"}:
                    raise subprocess.TimeoutExpired(command, kwargs["timeout"])
                self.sessions[session][name] = self.files[path]
                output = "Success: streamed " + str(len(self.files[path])) + " bytes"
            elif args[:2] == ["pm", "install-commit"]:
                if self.fail == "commit-failure":
                    output = "Failure [INSTALL_FAILED_TEST]"  # Android may still exit zero.
                else:
                    self.committed = self.sessions.pop(args[2])
                    self.files.update({"/data/app/fixture/" + n: b for n, b in self.committed.items()})
                    output = "Success"
            elif args[:2] == ["pm", "install-abandon"]:
                self.abandoned.append(args[2])
                del self.sessions[args[2]]
                output = "Success"
            elif args[:2] == ["pm", "path"]:
                output = "package:/data/app/fixture/base.apk"
            else:
                raise AssertionError("Unexpected device operation: " + str(args))
        return subprocess.CompletedProcess(command, 0, output + "\n", "")



class StandaloneKitTests(unittest.TestCase):
    def test_matching_api_profiles_and_dated_kit_validate_offline(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            apk, metadata = artifacts(root)
            kit = packager.package(metadata, root / "dist", "AMATV-20261102", archive=True)
            manifest, packaged = installer.validate_kit(kit)
            self.assertEqual(packaged.name, "AMATV-20261102.apk")
            self.assertEqual(manifest["version_code"], 15)
            self.assertEqual([p["profile_version"] for p in manifest["profiles"]], ["010", "015"])
            self.assertTrue((root / "dist/AMATV-20261102.apk").is_file())
            with zipfile.ZipFile(root / "dist/AMATV-20261102-install-kit.zip") as zipped:
                self.assertEqual(len(zipped.namelist()), 6)

    def test_wrong_sdk_profile_and_stale_checksum_are_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            apk, _ = artifacts(root)
            with self.assertRaisesRegex(ValueError, "needs 010"):
                installer.validate_pair(apk, root / "api31.dm", 28, 30)
            with zipfile.ZipFile(root / "stale.dm", "w") as zipped:
                zipped.writestr("primary.prof", profile015(123))
                zipped.writestr("primary.profm", b"prm\x00002\0fixture")
            with self.assertRaisesRegex(ValueError, "checksum or method-count"):
                installer.validate_pair(apk, root / "stale.dm", 31, 2147483647)

    def test_agp_identity_mismatch_and_postpackage_tamper_are_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            _, metadata = artifacts(root)
            data = json.loads(metadata.read_text())
            data["elements"][0]["versionCode"] = 16
            metadata.write_text(json.dumps(data))
            with self.assertRaisesRegex(ValueError, "identity"):
                packager.package(metadata, root / "dist")
            data["elements"][0]["versionCode"] = 15
            metadata.write_text(json.dumps(data))
            kit = packager.package(metadata, root / "dist")
            (kit / "AMATV-0.6.2.apk").write_bytes(b"tampered")
            with self.assertRaisesRegex(ValueError, "SHA-256"):
                installer.validate_kit(kit)

    def test_installer_selects_exact_pair_and_replaces_without_launching(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            _, metadata = artifacts(root)
            kit = packager.package(metadata, root / "dist")
            for sdk, expected in [("30", "010"), ("31", "015")]:
                with self.subTest(sdk=sdk):
                    device = FakeDevice(sdk)
                    with patch.object(sys, "argv", ["install.py", "--kit", str(kit), "--serial", "tv-fixture"]), \
                         patch.object(installer, "find_adb", return_value="adb-fixture"), \
                         patch.object(installer.subprocess, "run", side_effect=device.run), redirect_stdout(io.StringIO()):
                        installer.main()
                    self.assertTrue(device.replace)
                    self.assertEqual(set(device.committed), {"base.apk", "base.dm"})
                    with zipfile.ZipFile(io.BytesIO(device.committed["base.dm"])) as dm:
                        version, _ = installer.profile_entries(dm.read("primary.prof"))
                        self.assertEqual(version, expected)
                    self.assertEqual(device.committed["base.apk"], (kit / "AMATV-0.6.2.apk").read_bytes())
                    self.assertEqual(device.directories, set())
                    self.assertEqual(device.sessions, {})
                    self.assertEqual(device.abandoned, [])
                    self.assertTrue(all(0 < call["timeout"] <= 45 for call in device.bounded))
                    self.assertTrue(all(call["stdin"] == subprocess.DEVNULL for call in device.bounded))

    def test_failed_transfer_or_hash_never_creates_install_session(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            apk, _ = artifacts(root)
            for failure, error in [("push-timeout", subprocess.TimeoutExpired), ("bad-hash", ValueError)]:
                with self.subTest(failure=failure):
                    device = FakeDevice(fail=failure)
                    device.files["/data/local/tmp/amatv-recovery.apk"] = b"keep"
                    with patch.object(installer.subprocess, "run", side_effect=device.run), self.assertRaises(error):
                        installer.install_pair(["adb", "-s", "tv"], apk, root / "api30.dm", "dev.amatv")
                    self.assertEqual(device.sessions, {})
                    self.assertEqual(device.abandoned, [])
                    self.assertEqual(device.directories, set())
                    self.assertEqual(device.files, {"/data/local/tmp/amatv-recovery.apk": b"keep"})

    def test_write_timeout_or_zero_exit_commit_failure_abandons_only_own_session(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            apk, _ = artifacts(root)
            for failure, error in [("create-timeout-with-id", subprocess.TimeoutExpired),
                                   ("write-timeout", subprocess.TimeoutExpired), ("commit-failure", ValueError)]:
                with self.subTest(failure=failure):
                    device = FakeDevice(fail=failure)
                    device.sessions["other"] = {"keep": b"other owner"}
                    with patch.object(installer.subprocess, "run", side_effect=device.run), self.assertRaises(error):
                        installer.install_pair(["adb", "-s", "tv"], apk, root / "api30.dm", "dev.amatv")
                    self.assertEqual(device.abandoned, ["42"])
                    self.assertEqual(device.sessions, {"other": {"keep": b"other owner"}})
                    self.assertEqual(device.directories, set())
                    self.assertIsNone(device.committed)

    def test_ambiguous_session_output_is_not_used_for_abandon(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            apk, _ = artifacts(root)
            device = FakeDevice(fail="bad-session")
            device.sessions["77"] = {"keep": b"other owner"}
            with patch.object(installer.subprocess, "run", side_effect=device.run), self.assertRaisesRegex(ValueError, "unambiguous"):
                installer.install_pair(["adb", "-s", "tv"], apk, root / "api30.dm", "dev.amatv")
            self.assertEqual(device.abandoned, [])
            self.assertIn("77", device.sessions)
            self.assertEqual(device.directories, set())

    def test_cleanup_timeout_keeps_original_failure_and_collision_is_not_removed(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            apk, _ = artifacts(root)
            device = FakeDevice(fail="cleanup-timeout")
            with patch.object(installer.subprocess, "run", side_effect=device.run), \
                 patch.object(sys, "stderr", new_callable=io.StringIO) as errors, \
                 self.assertRaises(subprocess.TimeoutExpired) as raised:
                installer.install_pair(["adb", "-s", "tv"], apk, root / "api30.dm", "dev.amatv")
            self.assertIn("install-write", str(raised.exception))
            self.assertIn("Cleanup could not finish", errors.getvalue())
            self.assertEqual(device.abandoned, ["42"])
            device = FakeDevice()
            collision = "/data/local/tmp/amatv-install-" + "a" * 32
            device.directories.add(collision)
            with patch.object(installer.subprocess, "run", side_effect=device.run), \
                 patch.object(installer.uuid, "uuid4") as token, \
                 self.assertRaises(subprocess.CalledProcessError):
                token.return_value.hex = "a" * 32
                installer.install_pair(["adb", "-s", "tv"], apk, root / "api30.dm", "dev.amatv")
            self.assertEqual(device.directories, {collision})

    def test_bounded_push_keeps_previous_destination_until_hash_verifies(self):
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp) / "file.apk"
            source.write_bytes(b"replacement")
            destination = "/data/local/tmp/amatv-target.apk"
            for fail in ["bad-hash", "push-timeout", None]:
                device = FakeDevice(fail=fail)
                device.files[destination] = b"previous"
                with patch.object(installer.subprocess, "run", side_effect=device.run):
                    if fail:
                        with self.assertRaises((ValueError, subprocess.TimeoutExpired)):
                            push_bounded.transfer(["adb", "-s", "tv"], source, destination)
                        self.assertEqual(device.files[destination], b"previous")
                    else:
                        push_bounded.transfer(["adb", "-s", "tv"], source, destination)
                        self.assertEqual(device.files[destination], b"replacement")
                self.assertEqual(device.directories, set())

    def test_dex_keys_and_containment(self):
        self.assertEqual(installer.dex_name("base.apk!classes10.dex"), "classes10.dex")
        self.assertEqual(installer.dex_name("app.apk"), "classes.dex")
        with self.assertRaises(ValueError):
            installer.contained(Path("/safe"), "../outside.dm")


if __name__ == "__main__":
    unittest.main()
