#!/usr/bin/env python3
"""Package an already-built standalone APK and AGP-generated baseline DMs."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
import zipfile

from standalone_install import FORMAT, contained, digest, validate_kit, validate_pair


def copy_atomic(source: Path, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=target.parent, prefix=".amatv-copy-", delete=False) as stream:
        temporary = Path(stream.name)
    try:
        shutil.copyfile(source, temporary)
        os.replace(temporary, target)
    finally:
        temporary.unlink(missing_ok=True)


def package(metadata_path: Path, output_dir: Path, name: str | None = None, archive: bool = False) -> Path:
    metadata = json.loads(metadata_path.read_text())
    if metadata.get("applicationId") != "dev.amatv" or metadata.get("variantName") != "standalone":
        raise ValueError("Expected the standalone AMATV AGP output metadata")
    elements = metadata["elements"]
    if len(elements) != 1 or elements[0].get("filters"):
        raise ValueError("This kit expects the universal standalone APK, not split APKs")
    element = elements[0]
    apk = contained(metadata_path.parent, element["outputFile"])
    release_name = name or f"AMATV-{element['versionName']}"
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,99}", release_name):
        raise ValueError("--name must be a plain filename stem, e.g. AMATV-20261102")
    rows = []
    for group in metadata.get("baselineProfiles", []):
        candidates = group["baselineProfiles"]
        if len(candidates) != 1:
            raise ValueError("Expected exactly one DM per SDK range for this universal APK")
        dm = contained(metadata_path.parent, candidates[0])
        minimum, maximum = int(group["minApi"]), int(group["maxApi"])
        proof = validate_pair(apk, dm, minimum, maximum)
        if (proof["package_name"] != metadata["applicationId"] or
            proof["version_code"] != element["versionCode"] or
            proof["version_name"] != element["versionName"]):
            raise ValueError("APK manifest identity does not match the AGP version metadata")
        folder = f"api{minimum}plus" if maximum == 2147483647 else f"api{minimum}-{maximum}"
        rows.append((dm, {"min_api": minimum, "max_api": maximum,
                          "path": f"{folder}/{release_name}.dm", "sha256": digest(dm),
                          "profile_version": proof["profile_version"], "metadata_version": proof["metadata_version"],
                          "dex_entries": proof["dex_entries"]}))
    if not rows:
        raise ValueError("AGP did not emit SDK-ranged DexMetadata; rebuild with baseline profiles")
    kit = output_dir / release_name
    if kit.exists():
        prior = kit / "manifest.json"
        if not prior.is_file() or json.loads(prior.read_text()).get("format") != FORMAT:
            raise ValueError("Refusing to overwrite a directory that is not a generated AMATV kit")
    kit.mkdir(parents=True, exist_ok=True)
    manifest = {"format": FORMAT, "created_utc": datetime.now(timezone.utc).isoformat(),
                "package_name": metadata["applicationId"], "version_code": element["versionCode"],
                "version_name": element["versionName"],
                "apk": {"path": f"{release_name}.apk", "sha256": digest(apk)},
                "profiles": [row for _, row in sorted(rows, key=lambda row: row[1]["min_api"])]}
    copy_atomic(apk, kit / manifest["apk"]["path"])
    for source, row in rows:
        copy_atomic(source, kit / row["path"])
    copy_atomic(Path(__file__).with_name("standalone_install.py"), kit / "install.py")
    (kit / "README.txt").write_text(
        f"AMATV {element['versionName']} (versionCode {element['versionCode']})\n\n"
        "先安裝 Python 3.9+ 與 Android SDK Platform-Tools，並連線到電視 ADB。\n"
        "在這個資料夾執行（以實際電視位址取代範例）：\n"
        "  python3 install.py --serial <TV_ADB_SERIAL>\n\n"
        "安裝程式會驗證檔案、依 Android 版本選擇同版 baseline profile，\n"
        "再一起安裝 APK 與 DM，保留現有帳號與 App 資料。\n"
        "僅驗證檔案：python3 install.py --validate-only\n\n"
        "僅安裝 APK 時，profile 於首次啟動後提供後續背景最佳化；\n"
        "這份套件會在安裝 APK 時一起交付 profile。\n", encoding="utf-8")
    # Manifest is written last: an interrupted update fails SHA checks rather
    # than accepting a mixture of different builds with the same version code.
    (kit / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    validate_kit(kit)
    # Preserve the existing flat APK artifact used by older release workflows.
    copy_atomic(kit / manifest["apk"]["path"], output_dir / manifest["apk"]["path"])
    if archive:
        target = output_dir / f"{release_name}-install-kit.zip"
        with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_STORED) as zipped:
            files = [manifest["apk"]["path"], "manifest.json", "install.py", "README.txt"] + [row["path"] for row in manifest["profiles"]]
            for relative in files:
                zipped.write(kit / relative, arcname=f"{release_name}/{relative}")
    return kit


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--metadata", type=Path, default=Path("app/build/outputs/apk/standalone/output-metadata.json"))
    parser.add_argument("--output-dir", type=Path, default=Path("dist"))
    parser.add_argument("--name", help="Optional dated artifact stem, e.g. AMATV-20261102")
    parser.add_argument("--zip", action="store_true", help="Also create a shareable install-kit ZIP")
    args = parser.parse_args()
    kit = package(args.metadata, args.output_dir, args.name, args.zip)
    print(f"Validated installation kit: {kit.resolve()}")
    print(f"Shareable APK: {(args.output_dir / (kit.name + '.apk')).resolve()}")
    if args.zip:
        print(f"Installation ZIP: {(args.output_dir / (kit.name + '-install-kit.zip')).resolve()}")


if __name__ == "__main__":
    main()
