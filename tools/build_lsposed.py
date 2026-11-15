#!/usr/bin/env python3
"""Build the HypereSIM LSPosed module APK.

The module is a plain libxposed API 102 APK: no activity, no resources, only
META-INF/xposed metadata and the hook entry class. Gradle is driven through the
project wrapper; the APK is verified (metadata, entry class, manifest identity)
before being copied to outputs/.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path
from typing import NoReturn

ROOT = Path(__file__).resolve().parents[1]
PROJECT = ROOT / "lsposed"
APP_GRADLE = PROJECT / "app" / "build.gradle.kts"

APPLICATION_ID = "io.github.xrh0905.hyperesim"
ENTRY_CLASS = "io.github.xrh0905.hyperesim.HyperEsimModule"
MIN_API = 102
TARGET_API = 102
SCOPE = {"com.android.phone", "com.miui.euicc"}
METADATA = ("module.prop", "scope.list", "java_init.list")


def fail(message: str) -> NoReturn:
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(1)


def find_sdk() -> Path:
    candidates = [
        os.environ.get("ANDROID_HOME"),
        os.environ.get("ANDROID_SDK_ROOT"),
        str(Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk"),
        str(Path.home() / "Android" / "Sdk"),
    ]
    local = PROJECT / "local.properties"
    if local.is_file():
        match = re.search(r"^sdk\.dir=(.+)$", local.read_text(encoding="utf-8"), re.MULTILINE)
        if match:
            candidates.insert(0, match.group(1).strip().replace("\\:", ":").replace("\\\\", "\\"))
    for candidate in candidates:
        if candidate and (Path(candidate) / "platforms").is_dir():
            return Path(candidate)
    fail("Android SDK not found; set ANDROID_HOME or create lsposed/local.properties")


def ensure_sdk_properties(sdk: Path) -> None:
    local = PROJECT / "local.properties"
    local.write_text(f"sdk.dir={sdk.as_posix()}\n", encoding="utf-8")


def find_gradle() -> list[str]:
    wrapper = PROJECT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if wrapper.is_file():
        return [str(wrapper)]
    found = shutil.which("gradle")
    if found:
        return [found]
    fail("Gradle not found; run the wrapper or install gradle")


def find_aapt(sdk: Path) -> Path | None:
    build_tools = sdk / "build-tools"
    if not build_tools.is_dir():
        return None

    def version_key(path: Path) -> tuple[int, ...]:
        return tuple(int(part) for part in re.findall(r"\d+", path.name))

    for candidate in sorted(build_tools.iterdir(), key=version_key, reverse=True):
        for name in ("aapt.exe", "aapt"):
            if (candidate / name).is_file():
                return candidate / name
    return None


def parse_expected() -> dict[str, str]:
    gradle = APP_GRADLE.read_text(encoding="utf-8")

    def field(name: str) -> str:
        match = re.search(rf'^\s*{name} = "?([^"\n]+)"?$', gradle, re.MULTILINE)
        if not match:
            fail(f"app/build.gradle.kts: missing {name}")
        return match.group(1)

    prop_text = (PROJECT / "app/src/main/resources/META-INF/xposed/module.prop").read_text(
        encoding="utf-8"
    )
    prop = dict(
        line.split("=", 1) for line in prop_text.splitlines() if line and not line.startswith("#")
    )
    expected = {
        "applicationId": field("applicationId"),
        "versionCode": field("versionCode"),
        "versionName": field("versionName"),
        "moduleVersion": prop["moduleVersion"],
        "moduleVersionCode": prop["moduleVersionCode"],
    }
    if expected["applicationId"] != APPLICATION_ID:
        fail(f"applicationId changed: {expected['applicationId']}")
    if expected["versionCode"] != expected["moduleVersionCode"]:
        fail("versionCode and moduleVersionCode differ")
    if expected["versionName"] != expected["moduleVersion"]:
        fail("versionName and moduleVersion differ")
    return expected


def apk_entries(apk: Path) -> dict[str, bytes]:
    with zipfile.ZipFile(apk) as zf:
        names = set(zf.namelist())
        if "AndroidManifest.xml" not in names:
            fail(f"{apk}: missing AndroidManifest.xml")
        dex_names = sorted(name for name in names if re.fullmatch(r"classes\d*\.dex", name))
        if not dex_names:
            fail(f"{apk}: missing classes*.dex")
        entries = {}
        for name in METADATA:
            key = f"META-INF/xposed/{name}"
            if key not in names:
                fail(f"{apk}: missing {key}")
            entries[name] = zf.read(key)
        # Debug builds may split the module classes into classes2.dex; search all of them.
        entries["dex"] = b"".join(zf.read(name) for name in dex_names)
        return entries


def validate_metadata(entries: dict[str, bytes], expected: dict[str, str]) -> None:
    props = dict(
        line.split("=", 1)
        for line in entries["module.prop"].decode("utf-8").splitlines()
        if line and not line.startswith("#")
    )
    for key, value in (
        ("minApiVersion", str(MIN_API)),
        ("targetApiVersion", str(TARGET_API)),
        ("staticScope", "true"),
        ("moduleVersion", expected["moduleVersion"]),
        ("moduleVersionCode", expected["moduleVersionCode"]),
    ):
        if props.get(key) != value:
            fail(f"module.prop: {key}={props.get(key)!r}, expected {value!r}")

    scope = {line.strip() for line in entries["scope.list"].decode("utf-8").splitlines() if line.strip()}
    if scope != SCOPE:
        fail(f"scope.list is {sorted(scope)}, expected {sorted(SCOPE)}")

    inits = [line.strip() for line in entries["java_init.list"].decode("utf-8").splitlines() if line.strip()]
    if inits != [ENTRY_CLASS]:
        fail(f"java_init.list is {inits}, expected [{ENTRY_CLASS}]")
    descriptor = "L" + ENTRY_CLASS.replace(".", "/") + ";"
    if descriptor.encode() not in entries["dex"]:
        fail(f"entry class {ENTRY_CLASS} not present in classes*.dex")


def validate_manifest(sdk: Path, apk: Path, expected: dict[str, str], debug: bool) -> None:
    aapt = find_aapt(sdk)
    if aapt is None:
        print("warning: aapt not found, skipping manifest identity check", file=sys.stderr)
        return
    result = subprocess.run(
        [str(aapt), "dump", "badging", str(apk)],
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    header = result.stdout.splitlines()[0]
    for key, value in (
        ("name", expected["applicationId"]),
        ("versionCode", expected["versionCode"]),
        ("versionName", expected["versionName"]),
    ):
        if f"{key}='{value}'" not in header:
            fail(f"AndroidManifest.xml: {key} is not {value!r} ({header})")
    debuggable = "application-debuggable" in result.stdout
    if debug and not debuggable:
        fail("debug APK is not debuggable")
    if not debug and debuggable:
        fail("release APK is debuggable")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--offline", action="store_true", help="use cached Gradle dependencies")
    parser.add_argument("--no-daemon", action="store_true", help="do not keep a Gradle daemon")
    parser.add_argument("--debug", action="store_true", help="build the debuggable debug APK")
    parser.add_argument("--out", type=Path, default=None, help="output APK path")
    args = parser.parse_args()

    expected = parse_expected()
    sdk = find_sdk()
    ensure_sdk_properties(sdk)

    variant = "debug" if args.debug else "release"
    build_output = PROJECT / "app/build/outputs/apk" / variant / f"app-{variant}.apk"
    command = find_gradle() + [
        "-p",
        str(PROJECT),
        "--console=plain",
        f"assemble{variant.capitalize()}",
    ]
    if args.offline:
        command.append("--offline")
    if args.no_daemon:
        command.append("--no-daemon")
    print("+ " + " ".join(command))
    subprocess.run(command, cwd=ROOT, check=True)

    if not build_output.is_file():
        fail(f"Gradle did not produce {build_output}")
    entries = apk_entries(build_output)
    validate_metadata(entries, expected)
    validate_manifest(sdk, build_output, expected, args.debug)

    suffix = "-debug" if args.debug else ""
    out = args.out or (
        ROOT / "outputs" / f"HypereSIM-LSPosed-{expected['moduleVersion']}{suffix}.apk"
    )
    out.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(build_output, out)

    print(f"variant : {variant}")
    print(f"apk     : {out} ({out.stat().st_size} bytes)")
    print(f"sha256  : {hashlib.sha256(out.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    main()
