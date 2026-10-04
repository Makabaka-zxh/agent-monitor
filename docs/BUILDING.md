# 本地构建与测试

以下命令从仓库根目录执行。公开版本起点为 Android **1.0.0 / versionCode 29**；实际安装包版本以 `android/AndroidManifest.xml` 为准。

## Python 服务与 Web 测试

需要 Python 3.12 或更新版本。建议创建独立虚拟环境并使用其中的 Python：

```sh
python -m venv .venv
# Windows PowerShell: .venv\Scripts\Activate.ps1
# macOS / Linux: source .venv/bin/activate
python -m pip install -r requirements-dev.txt
python -m pytest -q tests macos/scripts
```

`requirements-dev.txt` 包含服务依赖，并使用 `requirements-tested.txt` 中的版本约束。部分 Windows / macOS 专用测试在其他系统上会跳过；单个平台通过不代表所有平台均已验证。

Web 交互测试需要 Node.js 22 和 Playwright Chromium。测试使用隔离数据及本地接口，不需要个人服务账号。

```sh
npm install --no-save --package-lock=false playwright@1.55.0
npx playwright install chromium
```

Linux 若缺少浏览器系统依赖，使用 `npx playwright install --with-deps chromium`。随后明确选择已安装项目依赖的 Python：

```powershell
# Windows PowerShell
$env:QR_PYTHON = (Get-Command python).Source
node tests/test_ui_async.cjs
```

```sh
# macOS / Linux
QR_PYTHON="$(command -v python)" node tests/test_ui_async.cjs
```

启动本地服务：

```sh
python -m agent_monitor
```

自托管 HTTPS 服务使用 `--public-url https://monitor.example.com` 配置公开来源，并由部署者提供 HTTPS 入口。Docker 镜像固定以非 root 用户运行、将状态放在 `/data`；运行时必须追加自己的 `--public-url`，因为镜像入口监听 `0.0.0.0`。

## Android

当前构建入口支持 **Windows / PowerShell 5.1+ / JDK 17**，需要 Android SDK Platform `android-36.1` 和 Build Tools `36.1.0`：

```powershell
sdkmanager 'platforms;android-36.1' 'build-tools;36.1.0'
```

SDK 位置按 `-AndroidSdk`、`ANDROID_SDK_ROOT`、`ANDROID_HOME` 的顺序选择；JDK 位置按 `-JavaDirectory`、`JAVA_HOME` 的顺序选择。工具版本固定在已使用的构建配置中，脚本不会自动安装 SDK 或 JDK。

```powershell
# 环境变量已配置时
powershell -NoProfile -ExecutionPolicy Bypass -File android/build.ps1

# 也可明确指定本机目录
powershell -NoProfile -ExecutionPolicy Bypass -File android/build.ps1 `
  -AndroidSdk 'C:\Android\Sdk' -JavaDirectory 'C:\Java\jdk-17'
```

没有提供外部签名时，开发构建会在 `android/.signing/debug.keystore` 创建本机开发密钥。这个目录和所有 keystore 必须保持在版本控制之外。不同机器生成的开发签名不同，不能据此覆盖另一个签名的已安装应用。

构建产物为 `android/build/monitor-live-test.apk`。脚本检查 APK 签名及字体资源，并运行 Android 的纯 Java 和隔离平台测试，包括更新策略测试；它不安装到手机。这个历史文件名同样用于外部签名构建，发布前可复制为带版本号的文件名。

### 使用既有签名发布

`-Release` 要求提供已有 keystore；缺少配置时直接失败，不会生成开发密钥作为替代。发布后必须持续使用同一个签名证书，才能通过应用内更新和 Android 的覆盖安装检查。

签名路径和别名可通过 `-KeyStorePath` / `-KeyAlias` 或 `MONITOR_KEYSTORE` / `MONITOR_KEY_ALIAS` 提供。外部签名同时要求当前进程环境中已有：

- `MONITOR_KEYSTORE_PASSWORD`
- `MONITOR_KEY_PASSWORD`

通过本地凭据管理工具准备这两个环境变量；不要将密码写入脚本、命令历史或仓库。脚本传给 `apksigner` 的是 `env:变量名`，不是密码值。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File android/build.ps1 `
  -Release -KeyStorePath 'C:\private\monitor-release.keystore' -KeyAlias 'monitor'
```

这里的路径和别名只是示例，应使用原发行签名的实际位置和别名。`-Release` 约束签名来源，并要求 manifest 明确设置 `debuggable="false"`；不会自行重写版本或厂商接入配置。小米接入仍使用 `-XiaomiAppId` 和 `-XiaomiCertificateMode` 参数，默认不配置平台接入。

## macOS

原生 App 需要 macOS 14+、提供 Swift 5.9+ 的 Xcode Command Line Tools，以及 Python 3.9+。电脑连接器另需 Python 3.12+。

```sh
xcrun swift test --package-path macos
python3 macos/scripts/build_app.py --build-only
codesign --verify --deep --strict macos/dist/Monitor.app
```

也可以执行 `bash macos/scripts/verify_candidate.sh`，完成原生测试、候选构建与严格签名检查。`--build-only` 只生成 `macos/dist/Monitor.app`；省略该选项会尝试安装到当前用户的 `~/Applications`。

当前构建使用宿主架构和 ad hoc 本地签名，没有 Apple 公证。面向其他用户分发时，需要单独准备目标架构、Developer ID 签名和公证流程；本仓库 CI 不执行这些操作。

首次使用原生 Mac 客户端，先完全退出 App，再设置自己的 HTTPS 来源：

```sh
defaults write com.agentmonitor.mac MonitorServerOrigin -string 'https://monitor.example.com'
```

也可在启动进程时设置 `MONITOR_SERVER_URL`，它优先于上述配置；无效值不会回退到其他服务器。配置在每次进程启动时固定，未配置时不会发送账号请求。钥匙串按服务器隔离，公开版不迁移早期未绑定服务器的凭据，需要重新登录。更换服务器前先退出账号并暂停旧连接器，退出 App 后更改配置，再重新登录和配对。原生 App 会检查连接器的服务器隔离支持版本，旧连接器需先更新。

连接器的 `--server` 优先使用显式参数，否则读取 `MONITOR_SERVER_URL`，再否则使用占位地址 `https://monitor.example.com`。配对时应提供自己的 HTTPS 服务地址，例如：

```sh
python3 macos/scripts/collector_service.py pair --server https://monitor.example.com
```

扫码配对沿用 HTTPS 检查；原有代码配对的本机回环测试例外保持不变。环境变量只提供命令默认值，已经完成配对的后台连接使用保存的连接配置。

## CI 范围

`.github/workflows/ci.yml` 包含 Ubuntu / Windows Python 与 Web 测试、Windows Android 开发构建，以及 macOS 原生 Swift 测试。它只需要仓库读取权限，不使用发行 Secrets、不上传安装包、不创建 Release。CI 通过不能代替手机安装、厂商权限、macOS 原生界面或真实更新流程的验收。
