# 在手机回答桌面 Claude Code 的选项

在运行 Monitor 本机采集服务的电脑上，可以为一次 Claude Code CLI 会话启用选择题转发。Claude Code 调用 `AskUserQuestion` 时，原窗口等候，指定手机的工作台显示「等待回答」。在手机点选并提交后，答案会写回这次提问。多选会按 Claude Code 接受的格式返回。预览内容保存在电脑，手机显示原始标签和说明。

### 为一个窗口启用

需要已登录且支持 `PreToolUse`、命令钩子 `args` 和 `updatedInput` 的 Claude Code CLI，运行新版服务并已采集本机 Claude 任务的 Monitor，以及一台以「完整 App」模式配对的手机。命令在 Monitor 源码根目录运行，Python 使用该服务安装了依赖的解释器；数据库路径选服务正在使用的那份 `monitor.sqlite3`。电脑端服务和 Claude Code 应在同一个操作系统用户下运行。

以下是 Windows PowerShell 示例。将数据库路径、设备编号和片段路径替换为你自己的值。先列出设备，这个命令只显示设备名称与编号。

```powershell
$monitorDb = 'D:\Monitor\state\monitor.sqlite3'
$monitorPython = '.\.venv\Scripts\python.exe'
& $monitorPython -m agent_monitor.claude_question_hook --database $monitorDb --list-readers
```

选定手机后将片段保存为一个单独的新 JSON 文件，仅在目标窗口启动时传入。生成器只输出配置。已有会话可以加上 Claude Code 自己的 `--resume <会话编号>` 参数。

```powershell
$phoneReader = '上一步列出的设备编号'
$hookFile = 'D:\Monitor\claude-phone-questions.json'
$fragment = & $monitorPython -m agent_monitor.claude_question_hook --database $monitorDb --print-config "--reader-id=$phoneReader"
if ($LASTEXITCODE -ne 0) { throw '未能生成手机选择题配置' }
$fragment | Set-Content -LiteralPath $hookFile -Encoding ascii
claude --settings $hookFile
```

之后普通启动 `claude` 即可恢复桌面流程。用户或管理员配置的钩子策略仍生效。通过这一方式接收的是窗口加载片段后新发出的题，已经在旧窗口弹出的题继续在原处处理。[Claude Code 命令行参数](https://code.claude.com/docs/en/cli-reference) · [Claude Code 钩子说明](https://code.claude.com/docs/en/hooks)

### 等候、取消与适用范围

- 手机打开对应任务，在「待你处理」里选择原题的答案，再点「发送回答」。多部设备登录同一账号时，只有生成片段时选定的完整手机能看到这道题和提交答案。
- 手机交回电脑、超过 90 秒无人回答、连接撤销、电脑离线、任务归档，或服务重启时，由桌面处理。新的桌面任务最多等 8 秒让本机采集器发现。电脑未在此服务本机采集时也走桌面流程。
- 已提交的答案只写回产生问题的那次钩子调用。手机的「已交给电脑」代表答案写入原窗口通道。会话后续进展可在任务详情里查看。Monitor 发起的 Claude 续接使用其原有双向通道。
- 该配置只接收主会话的 `AskUserQuestion`，不会替用户批准其他工具。使用前可用测试问题验证当前 Claude Code 版本的钩子和手机配对情况。仓库的回归使用独立测试数据库、真实钩子子进程和手机 HTTP 接口，实际登录窗口的表现仍以实机为准。
