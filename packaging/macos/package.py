#!/usr/bin/env python3
"""Build architecture-specific, self-contained StillWord Mac disk images."""
import argparse
import hashlib
import json
import platform
from pathlib import Path
import plistlib
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[2]


def run(*args, **kwargs):
    print("Running", args[0], flush=True)
    subprocess.run([str(arg) for arg in args], check=True, **kwargs)


def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def copy(source, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--native-dir", type=Path, default=ROOT / "tools/distribution")
    parser.add_argument("--arch", choices=["all", "arm64", "x64"], default="all")
    parser.add_argument("--output", type=Path, default=ROOT / "dist")
    options = parser.parse_args()
    native = options.native_dir.resolve()
    output = options.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    details = dict(line.split("=", 1) for line in (ROOT / "src/main/resources/stillword.properties").read_text().splitlines()
                   if line and not line.startswith("#"))
    version = details["version"]
    run(ROOT / "build.sh", cwd=ROOT)
    assets = ROOT / "build/release-assets"
    helper = ROOT / "build/packaging"
    helper.mkdir(parents=True, exist_ok=True)
    host_arch = "arm64" if platform.machine() in ("arm64", "aarch64") else "x64"
    build_jdk = next((native / f"temurin17-{host_arch}").glob("*/Contents/Home"))
    run(build_jdk / "bin/javac", "--release", "17", "-d", helper,
        ROOT / "packaging/macos/RenderReleaseAssets.java")
    run(build_jdk / "bin/java", "-cp", str(ROOT / "build/classes") + ":" + str(helper),
        "RenderReleaseAssets", assets)
    run("/usr/bin/iconutil", "-c", "icns", assets / "StillWord.iconset", "-o", assets / "StillWord.icns")
    outputs = []
    for arch in (["arm64", "x64"] if options.arch == "all" else [options.arch]):
        label = "AppleSilicon" if arch == "arm64" else "Intel"
        target = output / f"StillWord-{version}-{label}-unsigned.dmg"
        if target.exists():
            raise SystemExit(f"Refusing to replace existing installer: {target}")
        work = ROOT / "build/macos" / arch
        if work.exists():
            shutil.rmtree(work)
        inputs = work / "input"
        inputs.mkdir(parents=True)
        jdk = next((native / f"temurin17-{arch}").glob("*/Contents/Home"))
        runtime = work / "runtime"
        run(jdk / "bin/jlink", "--add-modules", "java.desktop,java.net.http,jdk.crypto.ec,jdk.charsets",
            "--strip-debug", "--no-header-files", "--no-man-pages", "--compress=2", "--output", runtime)
        copy(ROOT / "build/StillWord.jar", inputs / "StillWord.jar")
        copy(ROOT / "setup-local-speech.sh", inputs / "setup-local-speech.sh")
        copy(native / "yt-dlp/yt-dlp_macos", inputs / "tools/yt-dlp/yt-dlp_macos")
        copy(native / f"node-{arch}/node", inputs / "tools/node/node")
        copy(native / "whisper/whisper-cli", inputs / "tools/speech/bin/whisper-cli")
        copy(native / "whisper/WHISPER_LICENSE", inputs / "tools/speech/WHISPER_LICENSE")
        shutil.copytree(native / "licenses", inputs / "licenses")
        copy(ROOT / "packaging/macos/INSTALL.txt", inputs / "INSTALL.txt")
        copy(ROOT / "packaging/macos/THIRD-PARTY-NOTICES.txt", inputs / "THIRD-PARTY-NOTICES.txt")
        images = work / "images"
        run(jdk / "bin/jpackage", "--type", "app-image", "--name", "StillWord", "--app-version", version,
            "--vendor", details["developer"], "--description", "Video transcripts and thoughtful summaries",
            "--input", inputs, "--main-jar", "StillWord.jar", "--main-class", "org.stillword.StillWord",
            "--runtime-image", runtime, "--icon", assets / "StillWord.icns", "--dest", images,
            "--mac-package-identifier", "org.stillword.app", "--mac-app-category", "public.app-category.productivity")
        app = images / "StillWord.app"
        plist = app / "Contents/Info.plist"
        info = plistlib.loads(plist.read_bytes())
        info.update(LSMinimumSystemVersion="13.0", StillWordDeveloper=details["developer"],
                    StillWordDeveloperWebsite=details["website"], StillWordSupportEmail=details["support.email"])
        plist.write_bytes(plistlib.dumps(info))
        # These local builds carry ad-hoc integrity signatures, not a Developer ID identity.
        # Sign copied tools before recording the final speech checksum.
        toolroot = app / "Contents/app/tools"
        for executable in [toolroot / "yt-dlp/yt-dlp_macos", toolroot / "node/node", toolroot / "speech/bin/whisper-cli"]:
            run("/usr/bin/codesign", "--force", "--sign", "-", executable)
        machine = "arm64" if arch == "arm64" else "x86_64"
        (toolroot / "speech/bin/build-receipt.txt").write_text(
            f"whisper.cpp 1.9.4 {machine} static embedded-metal\n" + sha(toolroot / "speech/bin/whisper-cli") + "\n")
        run("/usr/bin/codesign", "--force", "--sign", "-", app)
        run("/usr/bin/codesign", "--verify", "--deep", "--strict", app)
        preview = work / "packaged-preview.png"
        run(app / "Contents/MacOS/StillWord", "--preview", preview, cwd="/private/tmp")
        # jpackage supplies the standard drag-to-Applications disk image layout.
        package_dir = work / "dmg"
        run(jdk / "bin/jpackage", "--type", "dmg", "--name", "StillWord", "--app-version", version,
            "--app-image", app, "--dest", package_dir, "--icon", assets / "StillWord.icns",
            "--about-url", details["website"], "--vendor", details["developer"])
        generated = list(package_dir.glob("*.dmg"))
        if len(generated) != 1:
            raise SystemExit("Expected one disk image")
        shutil.move(generated[0], target)
        run("/usr/bin/hdiutil", "verify", target)
        outputs.append({"file": target.name, "sha256": sha(target), "bytes": target.stat().st_size,
                        "architecture": machine, "minimum_macos": "13.0", "developer_id_signed": False,
                        "notarized": False})
    (output / "release-manifest.json").write_text(json.dumps({"version": version, "developer": details["developer"],
        "website": details["website"], "support_email": details["support.email"], "installers": outputs}, indent=2) + "\n")
    (output / "SHA256SUMS.txt").write_text("".join(item["sha256"] + "  " + item["file"] + "\n" for item in outputs))
    copy(ROOT / "packaging/macos/INSTALL.txt", output / "READ-ME-FIRST.txt")
    print("Built:", *[str(output / item["file"]) for item in outputs], sep="\n", flush=True)


if __name__ == "__main__":
    main()
