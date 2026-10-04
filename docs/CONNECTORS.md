# 连接电脑与同步权限

在拥有目标会话记录的用户账号下安装 Python 依赖。每台电脑需要单独配对，不能复制其他设备的凭据文件。

```sh
python -m agent_monitor.remote_agent pair-qr --server https://monitor.example.com --name "开发电脑"
python -m agent_monitor.remote_agent run --status-only
```

配对二维码需要在已登录的 Monitor 中确认，也可使用工作台生成的一次性配对码：

```sh
python -m agent_monitor.remote_agent pair --server https://monitor.example.com --code YOUR_PAIR_CODE --name "开发电脑"
```

`--status-only` 只同步状态，不上传结果或附件，也不接收回复。验证状态正常后，在设备同步设置中明确启用需要的结果、文件与回复能力，然后按需要去掉此参数。可用 `run --once --status-only` 做一次状态检查。不同命令的 `--state-dir` 必须保持一致；配置和凭据保存在私有状态目录内。

## 数据来源与限制

- 采集当前用户有权读取的 Codex / Claude Code 会话文件与生命周期信息。不同 CLI 版本、hook 配置或后台子任务会影响状态准确性。
- 最终结果不包含完整思考链；下载限于工具明确关联且通过路径和大小校验的文件，并非任意远程文件浏览器。
- 同会话回复需要对应工具可用且已登录，并由该设备显式启用。不要以管理员或 root 身份运行连接器。
- Token 与套餐窗口只展示可读取的数据，来源缺失时显示未知。它们不是供应商账单。
- 断网会使状态滞后，过期信息不应视为任务完成证据。

macOS 原生客户端及用户级连接器的构建与地址配置见 [构建说明](BUILDING.md)。本版本提供源码，不提供经过 Apple 公证的安装包。Windows 自动启动脚本只面向已登录用户，不保存系统密码。

## 撤销

从自己的 Monitor 设备管理页撤销不再使用的设备，并停止该电脑的连接器。删除本地配置不等于服务端已经撤销凭据；优先在线完成撤销。不要将 `.state`、钥匙串导出、Codex 或 Claude Code 登录文件上传到 GitHub。
