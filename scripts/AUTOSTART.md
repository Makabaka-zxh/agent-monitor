# Windows 登录后自动启动

在拥有 Codex / Claude Code 会话记录的当前 Windows 用户下，从项目目录执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\register-autostart.ps1 `
  -PublicUrl "https://your-node.your-tailnet.ts.net" `
  -PythonPath "D:\MonitorRuntime\Scripts\python.exe" `
  -StateDir "D:\MonitorData\hub" `
  -Port 8766
```

替换为实际 HTTPS 来源、已有 Python 环境和原数据库目录。后面三个参数可省略，默认取项目 `.venv\Scripts\python.exe`、`.state\hub` 和端口 8766；相对路径按项目目录解析。

这会注册固定任务 `\AgentMonitor-PublicHub`，仅在当前用户登录后调用 `run-public.ps1`，使用 Interactive / Limited 权限和隐藏窗口，不保存密码。已运行时忽略新实例；失败后每分钟重试，最多 3 次，无执行时长上限。注册不会立即启动任务，不停止当前服务，不修改 HTTPS 映射。若同名任务并非本项目、当前用户创建，脚本会拒绝覆盖。

移除本项目的登录自启动：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\register-autostart.ps1 -Uninstall
```

卸载同样检查任务归属，只删除该登录任务，不终止运行中的服务、不删除数据库，也不关闭 Funnel。`-ExecutionPolicy Bypass` 仅作用于这次 PowerShell 进程。
