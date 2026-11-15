#!/usr/bin/env python3
"""Build the HypereSIM KernelSU zip module.

The official MIUI LPA APK is tracked inside module/ (upstream layout:
tehcneko/XiaomiEsimLPA). This script validates its identity, stages the
complete module tree under build/module, and writes a reproducible ZIP under
outputs/.
"""

from __future__ import annotations

import argparse
import hashlib
import shutil
import sys
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import NoReturn

ROOT = Path(__file__).resolve().parents[1]
MODULE_SRC = ROOT / "module"

# MIUI LPA 4.1.0.1, extracted unmodified from the HyperOS 4 product image.
EXPECTED_APK_MD5 = "4bd1e9b59606f84e075afed293a9dcaa"
APK_REL = "system/product/priv-app/MIUIEsimLPA/MIUIEsimLPA.apk"
APK_PATH = MODULE_SRC / APK_REL

REQUIRED = {
    "META-INF/com/google/android/updater-script",
    "META-INF/com/google/android/update-binary",
    "module.prop",
    "config",
    "common.sh",
    "customize.sh",
    "post-fs-data.sh",
    "service.sh",
    "action.sh",
    "uninstall.sh",
    "system/product/etc/permissions/privapp-permissions-euicc.xml",
    "system/product/etc/permissions/android.hardware.telephony.euicc.xml",
    "system/vendor/etc/permissions/zz_hyperesim_mep.xml",
    APK_REL,
}

EXECUTABLE = {
    "META-INF/com/google/android/update-binary",
    "common.sh",
    "customize.sh",
    "post-fs-data.sh",
    "service.sh",
    "action.sh",
    "uninstall.sh",
}

XML_FILES = {
    "system/product/etc/permissions/privapp-permissions-euicc.xml",
    "system/product/etc/permissions/android.hardware.telephony.euicc.xml",
    "system/vendor/etc/permissions/zz_hyperesim_mep.xml",
}


def fail(message: str) -> NoReturn:
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(1)


def normalize_text(data: bytes, rel: str) -> bytes:
    if b"\x00" in data:
        fail(f"{rel}: contains NUL bytes")
    return data.replace(b"\r\n", b"\n").replace(b"\r", b"\n")


def read_module_prop(data: bytes) -> dict[str, str]:
    props: dict[str, str] = {}
    for line in data.decode("utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        key, sep, value = line.partition("=")
        if not sep:
            fail(f"module.prop: malformed line: {line!r}")
        props[key] = value
    for key in ("id", "name", "version", "versionCode", "author", "description"):
        if not props.get(key):
            fail(f"module.prop: missing {key}")
    if not props["versionCode"].isdigit():
        fail("module.prop: versionCode must be an integer")
    if not props["id"][:1].isalpha():
        fail("module.prop: id must start with a letter")
    return props


def validate_apk(apk: Path) -> None:
    data = apk.read_bytes()
    md5 = hashlib.md5(data).hexdigest()
    if md5 != EXPECTED_APK_MD5:
        fail(
            f"{apk}: unexpected LPA APK (md5 {md5}, expected {EXPECTED_APK_MD5}). "
            f"Restore {APK_REL} to the unmodified MIUI LPA 4.1.0.1."
        )
    try:
        with zipfile.ZipFile(apk) as zf:
            manifest = zf.read("AndroidManifest.xml")
    except (zipfile.BadZipFile, KeyError) as exc:
        fail(f"{apk}: not a valid APK: {exc}")
    if b"com.miui.euicc" not in manifest and "com.miui.euicc".encode("utf-16le") not in manifest:
        fail(f"{apk}: AndroidManifest.xml does not reference com.miui.euicc")


def stage_module(stage: Path) -> None:
    if stage.exists():
        shutil.rmtree(stage)
    shutil.copytree(MODULE_SRC, stage)


def collect_files(stage: Path) -> dict[str, bytes]:
    files: dict[str, bytes] = {}
    for path in sorted(stage.rglob("*")):
        if path.is_symlink():
            fail(f"{path}: symlinks are not allowed in the module")
        if path.is_file():
            files[path.relative_to(stage).as_posix()] = path.read_bytes()
    missing = REQUIRED - files.keys()
    if missing:
        fail("module is missing: " + ", ".join(sorted(missing)))
    return files


def validate_files(files: dict[str, bytes]) -> tuple[dict[str, str], dict[str, bytes]]:
    normalized: dict[str, bytes] = {}
    for rel, data in files.items():
        normalized[rel] = data if rel == APK_REL else normalize_text(data, rel)

    for rel in XML_FILES:
        try:
            ET.fromstring(normalized[rel])
        except ET.ParseError as exc:
            fail(f"{rel}: invalid XML: {exc}")

    privapp = normalized["system/product/etc/permissions/privapp-permissions-euicc.xml"]
    if b'package="com.miui.euicc"' not in privapp:
        fail("privapp allowlist does not target com.miui.euicc")
    mep = normalized["system/vendor/etc/permissions/zz_hyperesim_mep.xml"]
    if b'<unavailable-feature name="android.hardware.telephony.euicc.mep"' not in mep:
        fail("MEP overlay does not disable android.hardware.telephony.euicc.mep")

    props = read_module_prop(normalized["module.prop"])
    return props, normalized


def write_zip(files: dict[str, bytes], out: Path) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    timestamp = (1980, 1, 1, 0, 0, 0)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zf:
        for rel in sorted(files):
            info = zipfile.ZipInfo(rel, date_time=timestamp)
            info.create_system = 3
            mode = 0o100755 if rel in EXECUTABLE else 0o100644
            info.external_attr = mode << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            zf.writestr(info, files[rel])


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=None, help="output ZIP (default: outputs/HypereSIM-<version>.zip)")
    parser.add_argument("--stage", type=Path, default=ROOT / "build" / "module", help="unpacked staging directory")
    args = parser.parse_args()

    apk = APK_PATH
    if not apk.is_file():
        fail(f"LPA APK not found: {apk}")

    validate_apk(apk)
    stage_module(args.stage)
    files = collect_files(args.stage)
    props, files = validate_files(files)
    for rel, data in files.items():
        (args.stage / rel).write_bytes(data)

    out = args.out or (ROOT / "outputs" / f"HypereSIM-{props['version']}.zip")
    write_zip(files, out)

    print(f"apk     : {apk} (md5 {EXPECTED_APK_MD5})")
    print(f"stage   : {args.stage}")
    print(f"zip     : {out} ({out.stat().st_size} bytes, {len(files)} entries)")
    print(f"sha256  : {hashlib.sha256(out.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    main()
