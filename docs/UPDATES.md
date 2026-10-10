# Android 应用内更新

Monitor 的更新页面由用户手动发起检查。它匿名读取此仓库的 GitHub 正式 Release，不使用 Monitor 账号的请求通道、会话凭据或服务地址。

固定检查入口：`https://api.github.com/repos/Makabaka-zxh/agent-monitor/releases/latest`。Draft 和 prerelease 不作为应用内正式更新。页面允许查看同版本发布信息，只有更大的 `versionCode` 且满足手机 Android 版本要求时才允许下载升级。

## 发布所需文件

每个支持应用内更新的正式 Release 必须同时上传：

1. 一个最终签名的 APK。
2. UTF-8 JSON 文件 **`update.json`**，其内容描述这个 APK。

`update.json` 字段与 `UpdatePolicy.Manifest` 完全对应：

| 字段 | 类型与要求 |
| --- | --- |
| `schema` | 整数，固定为 `1` |
| `packageName` | 字符串，固定为 `com.agentmonitor.live` |
| `versionName` | 与 APK 完全相同；1–80 个 ASCII 字符，字母数字开头，后续仅字母数字及 `._+-` |
| `versionCode` | 正整数，不超过 Java `int`；升级时必须大于已安装版本 |
| `minSdk` | 正整数，与 APK manifest 完全相同 |
| `apkName` | 与 Release asset 名完全相同；字母数字开头，后续仅字母数字及 `._-`，最多 180 字符，结尾为 `.apk` |
| `sha256` | 最终 APK 字节的 SHA-256，64 位十六进制字符 |
| `size` | 最终 APK 的字节数，正整数，最多 150 MiB（157286400 字节） |

元数据最大 1 MiB。Release tag 同样只能使用字母数字开头的安全路径段，后续使用字母数字及 `._-`，最多 180 字符；例如 `v1.0.1`。

## 手动准备一次更新

首个公开版本为 **1.0.0 / versionCode 29**。后续版本例如 `1.0.1 / 30`，必须先更新源码 manifest，再使用原发行 keystore 构建。具体命令见 [构建说明](BUILDING.md)。新的开发签名不能替代既有发行签名。

将最终构建产物复制为发布文件名，例如 `android/build/monitor-1.0.1.apk`。随后从源码 manifest 和最终 APK 生成元数据；以下 PowerShell 命令只写本地 `update.json`：

```powershell
$apk = Get-Item -LiteralPath 'android/build/monitor-1.0.1.apk'
$manifest = [xml](Get-Content -LiteralPath 'android/AndroidManifest.xml' -Raw)
$androidNamespace = 'http://schemas.android.com/apk/res/android'
$metadata = [ordered]@{
    schema = 1
    packageName = $manifest.manifest.GetAttribute('package')
    versionName = $manifest.manifest.GetAttribute('versionName', $androidNamespace)
    versionCode = [int]$manifest.manifest.GetAttribute('versionCode', $androidNamespace)
    minSdk = [int]$manifest.manifest.'uses-sdk'.GetAttribute('minSdkVersion', $androidNamespace)
    apkName = $apk.Name
    sha256 = (Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    size = [long]$apk.Length
}
$metadataPath = Join-Path $apk.DirectoryName 'update.json'
[System.IO.File]::WriteAllText($metadataPath, ($metadata | ConvertTo-Json), [System.Text.UTF8Encoding]::new($false))
```

发布前检查 APK 内部版本、最小系统版本、包名与元数据一致，并确认签名证书与上一版本相同。不要在生成哈希后再次修改、对齐或重新签名 APK。

在此仓库手动创建对应 tag 的 GitHub Release，上传这两个文件并写明变更说明，最后明确发布为正式版本。可以先准备 Draft，但只有完成发布后应用才会把它作为候选版本。本仓库没有自动创建或发布 Release 的工作流。

## 下载与安装检查

客户端只接受精确匹配此仓库、Release tag 和文件名的 GitHub 下载地址。跳转限于同一文件的 GitHub Release 地址或 `release-assets.githubusercontent.com`，全程要求 HTTPS；CDN 签名查询参数可以保留。它不会接受其他仓库、任意主机、userinfo 或非默认 HTTPS 端口。

下载时限制字节数并验证 SHA-256；完成后还检查 APK 包名、版本名称、版本号、最小系统版本，以及与当前安装一致的签名证书。进入安装前再次验证文件。SHA-256 用于确认文件与发布元数据一致，签名证书检查用于确认应用发行身份；两者都必须通过。

用户点击安装后，仍需 Android 系统的安装许可和确认界面。若需要开启“允许来自此来源的应用”，返回 Monitor 后必须再次点击安装。应用不会自动安装、绕过系统确认或关闭 TLS 校验。离开页面会取消正在进行的操作，过期回调不会自动启动安装。

从 1.0.1 起，系统保存并重建更新页面时，会保留一小时内已完成下载的候选信息。页面只保存固定缓存目录中的文件名、受限版本清单、完成时间与权限等待标记，不保存已校验对象或安装器授权。返回前台后重新检查文件大小、哈希、包名、版本、系统要求和签名，成功后仍需再次点击安装。过期、缺失或校验失败时需要重新检查；主动退出页面或取消恢复会清理对应候选。此恢复只覆盖系统保存的页面重建，不是后台下载、断点续传或完全退出后的自动恢复。

从 1.0.2 起，交给系统安装器后也会保存已完成下载的候选信息。返回更新页时重新读取已安装版本：已包含这次更新则不再提供安装；尚未升级则重新校验候选，等待用户再次点击。取消安装后发生页面重建，也遵循同样流程。清理候选时，如果安装器仍持有该文件的有效短期授权，会保留文件至授权到期后的缓存清理，避免页面退出打断系统读取；页面重建不会延长候选有效期或安装授权。

自建或 fork 的 APK 若使用自己的签名，将无法安装由其他证书签名的发行版本。维护 fork 的更新渠道时，应同步调整固定仓库策略和对应测试；不要仅修改下载页面链接。

## 验证边界

更新策略的离线测试覆盖 URL 混淆、跳转主机、版本资格、字段取值边界、大小和哈希格式。发布者还需在真实设备验证从上一正式版本检查、下载、系统确认和覆盖安装的流程。文档与 CI 不代表这些真机步骤已经通过。

更新流程的主机集成测试还会直接执行当前更新页面、包校验与安装文件提供器源码，覆盖版本重读、候选重新校验、取消与迟到回调、候选重建和只读授权清理。Android 框架及包管理器使用合成边界，不能据此宣称系统安装返回、真实重建或签名解析已通过实机验收。独立运行方法见[构建说明](BUILDING.md#更新流程主机集成测试)。

从 1.0.1 升到 1.0.2 时，下载与安装交接由手机上原有的 1.0.1 更新页面执行。这条升级链路成功，不能代替 1.0.2 安装返回与重建分支的设备验收。测试记录应分别列出升级链路、新版页面与恢复分支的实际覆盖范围。

2026-10-09 已完成该正式版本升级链路，并核验安装包哈希、登录保留、筛选、前后台返回和“已是最新版本”。code31 的取消重试、安装返回与系统页面重建仍待后续真实版本升级时进行设备验收。
