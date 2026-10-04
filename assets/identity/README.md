# Monitor 原创工具识别符

`monitor-claude.svg` 的橙色 C 与 `monitor-codex.svg` 的绿色终端图形是 Monitor 原创的中性识别符，用于配合工具名称区分任务。它们不描摹官方品牌图标，不是 Anthropic、Claude、OpenAI 或 Codex 的官方标志，也不表示厂商认可。源文件与其派生图像按仓库根 MIT 许可证提供。

SVG 只含圆角矩形和多边形，没有使用字体、外部图片或第三方图标。PNG 由这些 SVG 几何在 8 倍分辨率绘制后缩小为原有资源尺寸，保留 RGBA 透明背景。Android 通知图形使用相同前景几何，转换为 24×24 的白色遮罩，不含背景底板。

| 源 | 对应资源 |
| --- | --- |
| `monitor-claude.svg` | `web/assets/claude-official.png`、`android/res/drawable/claude.png`、`macos/Sources/Monitor/Resources/Claude.png`，均为 32×32；`android/res/drawable/ic_claude.xml` 为 24dp 矢量通知图形 |
| `monitor-codex.svg` | `web/assets/codex-official.png`、`android/res/drawable/codex.png`、`macos/Sources/Monitor/Resources/Codex.png`，均为 104×104；`android/res/drawable/ic_codex.xml` 为 24dp 矢量通知图形 |

文件名沿用原有引用以保持代码兼容；带 `official` 的历史名称不代表这些原创图形是官方标志。
