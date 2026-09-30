#!/usr/bin/env python3
"""Mount each StillWord DMG and verify its contents without installing globally."""
import argparse
import hashlib
import json
from pathlib import Path
import plistlib
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]


def run(args, **kwargs):
    print("Checking", str(args[0]), flush=True)
    result = subprocess.run([str(arg) for arg in args], text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, **kwargs)
    if result.returncode:
        raise RuntimeError(result.stdout)
    return result.stdout.strip()


def digest(path):
    sha = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            sha.update(chunk)
    return sha.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dist", type=Path, default=ROOT / "dist")
    parser.add_argument("--live-video", help="Optional public YouTube URL for a single Apple silicon caption check")
    options = parser.parse_args()
    scratch = Path(tempfile.mkdtemp(prefix="stillword-dmg-verification-"))
    classes = scratch / "classes"
    classes.mkdir()
    run(["javac", "--release", "17", "-cp", ROOT / "build/StillWord.jar", "-d", classes,
         ROOT / "packaging/macos/PackageSmoke.java"])
    alias_resolver = scratch / "resolve-alias"
    run(["/usr/bin/swiftc", "-module-cache-path", scratch / "swift-cache", "-o", alias_resolver,
         ROOT / "packaging/macos/ResolveAlias.swift"])
    manifest = json.loads((options.dist / "release-manifest.json").read_text())
    results = []
    for item in manifest["installers"]:
        dmg = options.dist / item["file"]
        assert digest(dmg) == item["sha256"], "Installer checksum mismatch"
        arch = item["architecture"]
        mount = scratch / ("Mounted Installer " + arch)
        mount.mkdir()
        home = scratch / ("Fresh User " + arch)
        home.mkdir()
        run(["/usr/bin/hdiutil", "attach", "-readonly", "-nobrowse", "-mountpoint", mount, dmg])
        try:
            app = mount / "StillWord.app"
            applications = mount / "Applications"
            if applications.is_symlink():
                assert applications.resolve() == Path("/Applications")
            else:
                # jpackage uses a native Finder alias (stored as :Applications on HFS).
                alias = applications if applications.exists() else mount / ":Applications"
                assert run([alias_resolver, alias]) == "/Applications", "Invalid Applications alias"
            info = plistlib.loads((app / "Contents/Info.plist").read_bytes())
            assert info["LSMinimumSystemVersion"] == "13.0"
            assert info["StillWordSupportEmail"] == "criticalattentionsystems@gmail.com"
            run(["/usr/bin/codesign", "--verify", "--deep", "--strict", app])
            launch = app / "Contents/MacOS/StillWord"
            assert run(["/usr/bin/lipo", "-archs", launch]) == arch
            preview = scratch / ("preview-" + arch + ".png")
            run([launch, "--preview", preview], cwd=scratch, timeout=45)
            java = app / "Contents/runtime/Contents/Home/bin/java"
            smoke = [java, "-cp", str(app / "Contents/app/StillWord.jar") + ":" + str(classes),
                     "org.stillword.PackageSmoke", app, home]
            checks = run(smoke, cwd=scratch, timeout=45)
            tools = app / "Contents/app/tools"
            node = tools / "node/node"
            assert run([node, "-e", "console.log(6*7)"], timeout=20) == "42"
            downloader_version = run([tools / "yt-dlp/yt-dlp_macos", "--version"], timeout=45)
            data = home / "Library/Application Support/Stillword/speech"
            setup = ["/usr/bin/arch", "-" + arch, "/bin/sh", app / "Contents/app/setup-local-speech.sh",
                     "--speech-dir", data, "--prepare-runtime-only"]
            setup_output = run(setup, timeout=45)
            receipt = (data / "bin/build-receipt.txt").read_text().splitlines()
            assert receipt[1] == digest(data / "bin/whisper-cli")
            assert receipt[1] == digest(tools / "speech/bin/whisper-cli")
            assert not (data / "models/ggml-large-v3-turbo.bin").exists(), "Unexpected model download"
            for binary in [node, tools / "yt-dlp/yt-dlp_macos", tools / "speech/bin/whisper-cli"]:
                libraries = run(["/usr/bin/otool", "-L", binary])
                assert "/opt/homebrew" not in libraries and "/usr/local" not in libraries
            run(["/usr/bin/codesign", "--verify", "--deep", "--strict", app])
            entry = {"file": dmg.name, "architecture": arch, "checks": checks,
                     "yt_dlp_version": downloader_version, "speech_setup": setup_output,
                     "preview": str(preview), "mounted_app_verified": True}
            if options.live_video and arch == "arm64":
                try:
                    entry["live_captions"] = run(smoke + [options.live_video], cwd=scratch, timeout=280)
                except (RuntimeError, subprocess.TimeoutExpired) as ex:
                    entry["live_captions_error"] = str(ex)
            results.append(entry)
        finally:
            run(["/usr/bin/hdiutil", "detach", mount])
    report = {"scope": "macOS host; Intel executable under Rosetta", "scratch": str(scratch), "results": results}
    (options.dist / "verification.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2), flush=True)


if __name__ == "__main__":
    main()
