#!/usr/bin/env python3
"""Build a personal, ad-hoc signed native SwiftUI app; no notarization claim."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import plistlib
import shutil
import subprocess
import sys
import tempfile
import uuid

BUNDLE_ID = "com.agentmonitor.mac"
APP_OWNER = "monitor-native-macos-v1"


def app_info() -> dict:
    return {"CFBundleIdentifier": BUNDLE_ID, "CFBundleName": "Monitor", "CFBundleDisplayName": "Monitor",
            "CFBundleExecutable": "Monitor", "CFBundlePackageType": "APPL", "CFBundleInfoDictionaryVersion": "6.0",
            "CFBundleShortVersionString": "1.0.0", "CFBundleVersion": "6", "LSMinimumSystemVersion": "14.0",
            "NSHighResolutionCapable": True, "NSPrincipalClass": "NSApplication",
            "CFBundleURLTypes": [{"CFBundleURLName": "Monitor login return", "CFBundleURLSchemes": ["agentmonitor"]}]}


def owned_app(path: Path) -> bool:
    if path.is_symlink() or not path.is_dir() or path.stat().st_uid != os.getuid():
        return False
    marker = path / "Contents/Resources/monitor-managed.json"
    info = path / "Contents/Info.plist"
    if marker.is_symlink() or info.is_symlink():
        return False
    try:
        return json.loads(marker.read_text()).get("owner") == APP_OWNER and plistlib.loads(info.read_bytes()).get("CFBundleIdentifier") == BUNDLE_ID
    except (OSError, ValueError):
        return False


def create_icon(source: Path, resources: Path) -> bool:
    if not source.is_file():
        return False
    with tempfile.TemporaryDirectory(prefix="monitor-icon-") as temporary:
        iconset = Path(temporary) / "Monitor.iconset"
        iconset.mkdir()
        for size in (16, 32, 128, 256, 512):
            for scale in (1, 2):
                name = f"icon_{size}x{size}" + ("@2x" if scale == 2 else "") + ".png"
                subprocess.run(["/usr/bin/sips", "-z", str(size * scale), str(size * scale), str(source),
                                "--out", str(iconset / name)], check=True, stdout=subprocess.DEVNULL)
        subprocess.run(["/usr/bin/iconutil", "-c", "icns", str(iconset), "-o", str(resources / "Monitor.icns")], check=True)
    return True


def main() -> int:
    parser = argparse.ArgumentParser(description="构建并安装当前用户的原生 Monitor.app（个人测试签名）")
    parser.add_argument("--package", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--build-only", action="store_true", help="只在 macos/dist 生成 App，不安装到 ~/Applications")
    args = parser.parse_args()
    if sys.platform != "darwin" or sys.version_info < (3, 9):
        parser.error("需要 macOS 14+、Python 3.9+ 和 Xcode Command Line Tools")
    if os.geteuid() == 0:
        parser.error("请用当前用户运行，不要使用 sudo")
    os.umask(0o077)
    package = args.package.resolve()
    if not (package / "Package.swift").is_file():
        parser.error("没有找到 Swift package")
    destination_root = package / "dist" if args.build_only else Path.home() / "Applications"
    if destination_root.is_symlink():
        parser.error("安装目录不能是符号链接")
    destination_root.mkdir(mode=0o700, parents=True, exist_ok=True)
    if destination_root.stat().st_uid != os.getuid():
        parser.error("安装目录不属于当前用户")
    destination = destination_root / "Monitor.app"
    if destination.exists() and not owned_app(destination):
        parser.error("已有 Monitor.app 不属于本安装器，未覆盖；请先选择其他位置")
    try:
        subprocess.run(["/usr/bin/xcrun", "swift", "build", "--package-path", str(package), "-c", "release"], check=True)
        result = subprocess.run(["/usr/bin/xcrun", "swift", "build", "--package-path", str(package), "-c", "release", "--show-bin-path"],
                                check=True, capture_output=True, text=True)
        binary_dir = Path(result.stdout.strip()).resolve()
        binary = binary_dir / "Monitor"
        bundle = binary_dir / "AgentMonitor_Monitor.bundle"
        if not binary.is_file() or not bundle.is_dir():
            raise RuntimeError("Swift executable or resource bundle is missing")
        with tempfile.TemporaryDirectory(prefix=".Monitor-stage-", dir=destination_root) as temporary:
            staged = Path(temporary) / "Monitor.app"
            executable_dir = staged / "Contents/MacOS"
            resources = staged / "Contents/Resources"
            executable_dir.mkdir(parents=True)
            resources.mkdir()
            shutil.copy2(binary, executable_dir / "Monitor")
            (executable_dir / "Monitor").chmod(0o700)
            # The app explicitly resolves this installed bundle before Bundle.module's development fallback.
            shutil.copytree(bundle, resources / bundle.name)
            info = app_info()
            if create_icon(package / "Sources/Monitor/Resources/AppIcon.png", resources):
                info["CFBundleIconFile"] = "Monitor.icns"
            (staged / "Contents/Info.plist").write_bytes(plistlib.dumps(info, sort_keys=False))
            (resources / "monitor-managed.json").write_text(json.dumps({"owner": APP_OWNER}) + "\n")
            subprocess.run(["/usr/bin/codesign", "--force", "--deep", "--sign", "-", str(staged)], check=True)
            subprocess.run(["/usr/bin/codesign", "--verify", "--deep", "--strict", str(staged)], check=True)
            backup = destination_root / (".Monitor-previous-" + uuid.uuid4().hex + ".app")
            if destination.exists():
                # Recheck immediately before replacing; only our marked app may move.
                if not owned_app(destination):
                    raise RuntimeError("App ownership changed during build")
                destination.rename(backup)
            try:
                staged.rename(destination)
            except OSError:
                if backup.exists() and not destination.exists():
                    backup.rename(destination)
                raise
            if backup.exists():
                if backup.resolve().parent != destination_root.resolve() or not owned_app(backup):
                    raise RuntimeError("Unexpected backup location; backup preserved")
                shutil.rmtree(backup)
        print(f"已生成原生 App：{destination}")
        print("此包使用个人测试签名，未经过 Apple 公证。")
        return 0
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"构建未完成（{type(error).__name__}）。", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
