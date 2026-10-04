#!/bin/bash
# Build and test locally on Mac. The installed Monitor.app is not replaced.
set -euo pipefail
package_dir="$(cd "$(dirname "$0")/.." && pwd)"
if [ "$(uname -s)" != "Darwin" ]; then
  echo "需要在 Mac 上执行。" >&2
  exit 1
fi
/usr/bin/xcrun swift test --package-path "$package_dir"
/usr/bin/python3 "$package_dir/scripts/build_app.py" --package "$package_dir" --build-only
/usr/bin/codesign --verify --deep --strict "$package_dir/dist/Monitor.app"
echo "测试和构建通过；候选 App 位于 macos/dist/Monitor.app，尚未替换已安装版本。"
