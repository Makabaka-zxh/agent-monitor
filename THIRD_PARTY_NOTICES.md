# 第三方许可与商标说明

仓库根目录的 MIT 许可证只适用于 Monitor 自有代码、文档和明确标注为原创的图形。下面列出的第三方字体、库及其通知保留各自许可；MIT 不会改变它们的授权条件，也不授予任何第三方商标权。

## MiSans 字体

本应用使用 **MiSans** 字体，由小米提供。

- 字体内的版权声明：Copyright © 2020-2025 Beijing Xiaomi Mobile Software Co.,Ltd. All Rights Reserved.
- 授权文件：[MiSans 字体知识产权许可协议](licenses/MiSans-License.pdf)。它是小米专有字体许可，**不是 MIT 或 SIL OFL**。
- [官方字体下载页](https://hyperos.mi.com/font/zh/download/)与[官方许可 PDF](https://hyperos.mi.com/font-download/MiSans%E5%AD%97%E4%BD%93%E7%9F%A5%E8%AF%86%E4%BA%A7%E6%9D%83%E8%AE%B8%E5%8F%AF%E5%8D%8F%E8%AE%AE.pdf)。来源记录随 Android 和 macOS 资源提供。
- 文件：`web/assets/fonts/MiSans-Regular.ttf`、`android/assets/fonts/MiSans-Regular.ttf`、`android/res/font/misans_regular.ttf`、`macos/Sources/Monitor/Resources/MiSans-Regular.ttf`。
- 四处均保留同一原始字体，未转换、修改或子集化。SHA-256：`9c120f0a849bc0aa5048daae2a3c0f6eecd828b5b33fce682a9622833f5feea6`。

协议第 2 条允许分发使用该字体创作的应用，同时要求在软件中特别注明使用 MiSans，保留版权声明和协议，且不得改编或二次开发字体。原文的署名要求是：“您应在软件中特别注明使用了 MiSans 字体。” 字体不得作为独立字体产品重新许可或分发。本项目随应用提供原字体，不将其重新许可为 MIT；完整条件以随附协议为准。

各应用资源目录中仍保留原许可 PDF。公开发行及衍生构建应保留本说明、随包许可和软件内的字体署名。

## ZXing Core 3.5.3

Android 本地二维码解码使用 `com.google.zxing:core:3.5.3`，按 Apache License 2.0 授权。

- [上游版本](https://github.com/zxing/zxing/tree/zxing-3.5.3)、[Maven Central 原始 JAR](https://repo.maven.apache.org/maven2/com/google/zxing/core/3.5.3/core-3.5.3.jar)。
- [随附完整许可及上游通知](licenses/ZXing-3.5.3-LICENSE.txt)。其中保留上游 LICENSE 附带的 jai-imageio 版权和许可条款；这不表示 Monitor 另行包含 jai-imageio 二进制文件。
- 原依赖及来源说明位于 `android/vendor/zxing-3.5.3/`；Android 安装包同时包含 `assets/licenses/ZXing-3.5.3.txt`。
- JAR SHA-256：`8d8064c1636fdaef7189dd9055c7d59950a8940a12f2293956446ec3c109fd82`，构建前校验。

## jsQR 1.4.0

网页中的本地二维码解码使用 jsQR 1.4.0，按 Apache License 2.0 授权。

- [上游项目](https://github.com/cozmo/jsQR)、[上游包声明](https://github.com/cozmo/jsQR/blob/master/package.json)。
- 文件：`web/assets/vendor/jsQR-1.4.0.js`。
- [随附许可](licenses/jsQR-1.4.0-LICENSE.txt)；原许可同时保留于 `web/assets/vendor/jsQR-LICENSE.txt`。
- 这份说明确认随附许可证和上游包声明，不把本地文件名当作下载来源或完整性验证的证明。

## 原创工具识别符与商标

公开源码中的橙色 C 和绿色终端图形是 **Monitor 原创的中性工具识别符**，不是 Claude 或 Codex 的官方标志，按本项目 MIT 许可证提供。其可编辑源位于 [assets/identity](assets/identity/README.md)。网页、Android 和 macOS 的 PNG 与 Android 通知图形由这两个源几何生成；部分文件保留了历史兼容名称，名称中的 `official` 不表示资产为官方品牌标志。

Monitor 的显示器折线应用图标也是本项目原创资产，适用 MIT。原始 SVG 为 `web/assets/app-icon.svg`。

Claude、Claude Code 和 Anthropic 名称及相关商标归 Anthropic 所有；Codex、OpenAI 名称及相关商标归 OpenAI 所有。名称仅用于说明兼容工具。本项目不隶属于这些公司，也不表示获得其认可、合作或担保。

不要将本项目的 MIT 许可证解释为使用官方品牌图标的授权。如果未来引入官方图标，应单独核对来源和当时适用的品牌条款，例如 [OpenAI 品牌规范](https://openai.com/brand/)与 [Anthropic 官方媒体入口](https://www.anthropic.com/news)。本公开版本未引入 Simple Icons；其项目 CC0 也不意味着每个品牌图标或商标都获得相同授权。

## 安装时获取的依赖

Python 依赖由 `requirements.txt`、`requirements-tested.txt` 和 `macos/scripts/requirements-connector.txt` 声明，安装时由包管理器获取，分别遵循其发布包中的许可。源码仓库不把虚拟环境或安装缓存作为自有代码分发。若另行分发包含这些依赖的完整运行环境、容器或安装程序，应保留相应包的许可证和通知。

JDK、Android SDK、Apple 工具链等构建工具未随本源码提供，其授权不属于本项目 MIT 许可证的范围。
