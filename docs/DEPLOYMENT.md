# 部署自己的 Monitor

Monitor 1.0 面向个人、自托管使用。服务保存账号、设备、任务与授权结果；不要把状态目录放进 Git、网盘公开链接或静态网站根目录。

## 本机试用

使用 Python 3.12+，安装根目录 `requirements.txt`，运行 `python -m agent_monitor`。默认只监听 `127.0.0.1:8766`。在本机浏览器建立账号，密码为 12–128 个字符。此账号是 Monitor 账号，与 Codex / Claude Code 账号独立。

默认会采集运行服务的当前用户可读取的本地会话。只做同步中心时加 `--no-local`。

## 手机与外网

准备自己控制的 HTTPS 域名、有效证书和反向代理。代理转发到本机 `http://127.0.0.1:8766`，保留正确 Host；不要关闭证书校验。服务不自动信任转发头。公开地址必须是根来源，不包含路径。

```sh
python -m agent_monitor --host 127.0.0.1 --port 8766 --public-url https://monitor.example.com
```

将示例域名替换成自己的地址。服务所在电脑需要保持运行。如果第一次直接以公开模式启动，先在服务主机建立账号：

```sh
python -m agent_monitor.admin --username you@example.com --state-dir .state/hub
```

密码在本地交互提示中输入，不能放在命令行或仓库里。已有账号时此命令拒绝覆盖。Android 登录页选择「服务器地址」，填写同一 HTTPS 地址，按提示关闭并重新打开 App，再完成浏览器登录和连接确认。更换服务器须先退出登录，再保存地址并重开；正在执行的请求不会被切换到另一服务器。

若从早期私人测试版升级，旧连接可能没有服务器绑定。应用会提供明确的本机旧连接清理入口，清理后重新设置地址并登录；本机清理无法代替原服务的设备撤销，需要在原服务设备管理中撤销旧授权。

不需要也不建议为本项目修改所有应用的 DNS、代理或网络出口。已有代理环境请由部署者按自身网络策略配置和验证。

## Docker

镜像是同步中心，不挂载宿主机的 Codex / Claude Code 私有目录：

```sh
docker build -t agent-monitor:1.0.0 .
docker volume create monitor-data
docker run -d --name monitor --restart unless-stopped -p 127.0.0.1:8766:8766 -v monitor-data:/data agent-monitor:1.0.0 --public-url https://monitor.example.com
docker exec -it monitor python -m agent_monitor.admin --username you@example.com --state-dir /data
```

镜像以内置非 root 用户运行。反向代理仍需单独配置。使用目录挂载时保证 `/data` 对容器 UID 10001 可写。电脑连接器作为当前登录用户运行，参见 [连接器说明](CONNECTORS.md)。

## 可选 Google 登录

使用自己的 Google Cloud 项目，创建 Web OAuth 客户端，将准确回调地址登记为：

```text
https://monitor.example.com/api/auth/google/callback
```

把从 Google 下载的 Web 客户端 JSON 保存为服务状态目录下的 `google-oauth.json`，限制文件读取权限并重启服务。不要提交该文件。请求范围仅为 `openid email profile`；Google 登录需与自己的 Monitor 账号按页面流程关联，不会自动开放别人的服务。OAuth 审核和测试用户限制由部署者自行配置。

## 备份与升级

停止服务后备份整个私有状态目录，并加密保存；其中包含账号、会话凭据、结果和附件。升级代码与 Python 依赖后，使用原状态目录启动。不要手动公开数据库、截图中的任务内容或日志里的授权信息。

Windows 用户级自启动见 [脚本说明](../scripts/AUTOSTART.md)。故障反馈请提供版本、错误类别和匿名耗时，去掉域名、账号、路径、任务正文及任何令牌。
