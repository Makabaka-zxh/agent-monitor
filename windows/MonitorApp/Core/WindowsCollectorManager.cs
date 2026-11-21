using System.Diagnostics;
using System.IO;
using System.Text;
using System.Text.Json;

namespace MonitorApp.Core;

public sealed class WindowsCollectorManager
{
    public CollectorStatusDto? Status { get; private set; }
    public bool Busy { get; private set; }
    public string Message { get; private set; } = "";

    public static string ExecutablePath =>
        Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Monitor", "collector", "bin", "monitor-collector.cmd");

    public bool IsInstalledOnDisk => File.Exists(ExecutablePath);

    public async Task RefreshAsync(string? origin)
    {
        if (Busy) return;
        if (!IsInstalledOnDisk || string.IsNullOrWhiteSpace(origin))
        {
            Status = null;
            return;
        }
        Busy = true;
        try
        {
            var output = await RunAsync(["status"], origin, null, TimeSpan.FromSeconds(20)).ConfigureAwait(true);
            var parsed = JsonSerializer.Deserialize<CollectorStatusDto>(output);
            if (parsed?.SupportsServerBinding != true)
            {
                Status = null;
                Message = "请先更新本机连接器后再连接当前服务器。";
                return;
            }
            Status = parsed;
            Message = "";
        }
        catch
        {
            Status = null;
            Message = "暂时无法读取本机连接状态";
        }
        finally
        {
            Busy = false;
        }
    }

    public async Task<bool> PairAsync(string origin, string code)
    {
        if (Busy || !MonitorSecurity.Matches(code, "[A-F0-9]{12}")) return false;
        if (!IsInstalledOnDisk) return false;
        Busy = true;
        var ok = false;
        try
        {
            var pcName = Environment.MachineName;
            if (string.IsNullOrWhiteSpace(pcName)) pcName = "Windows PC";
            if (pcName.Length > 60) pcName = pcName[..60];
            _ = await RunAsync(["pair", "--code-stdin", "--name", pcName], origin, code + "\n", TimeSpan.FromSeconds(45)).ConfigureAwait(true);
            ok = true;
        }
        catch
        {
            ok = false;
        }
        finally
        {
            Busy = false;
        }
        await RefreshAsync(origin).ConfigureAwait(true);
        Message = ok ? "这台 Windows 电脑已连接" : "连接未完成，请重试";
        return ok;
    }

    public async Task SetRunningAsync(string origin, bool running)
    {
        if (Busy || !IsInstalledOnDisk) return;
        Busy = true;
        var failed = false;
        try
        {
            _ = await RunAsync([running ? "start" : "stop"], origin, null, TimeSpan.FromSeconds(30)).ConfigureAwait(true);
        }
        catch
        {
            failed = true;
        }
        finally
        {
            Busy = false;
        }
        await RefreshAsync(origin).ConfigureAwait(true);
        if (failed) Message = "操作未完成，请重试";
    }

    public async Task<bool> SetModeAsync(string origin, string mode)
    {
        if (Busy || !IsInstalledOnDisk) return false;
        Busy = true;
        var ok = false;
        try
        {
            var output = await RunAsync(["mode", "--set", mode], origin, null, TimeSpan.FromSeconds(60)).ConfigureAwait(true);
            var parsed = JsonSerializer.Deserialize<CollectorStatusDto>(output);
            if (parsed?.Mode == mode)
            {
                Status = parsed;
                ok = true;
            }
        }
        catch
        {
            ok = false;
        }
        finally
        {
            Busy = false;
        }
        if (ok) Message = "同步设置已保存";
        else
        {
            await RefreshAsync(origin).ConfigureAwait(true);
            Message = "设置未完成，请检查本机连接状态后重试";
        }
        return ok;
    }

    private static (string FileName, List<string> PrefixArgs) ResolveManagedCommand()
    {
        try
        {
            var baseDir = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "Monitor", "collector");
            var currentPath = Path.Combine(baseDir, "current.json");
            var releasesRoot = Path.GetFullPath(Path.Combine(baseDir, "releases"));
            if (File.Exists(currentPath))
            {
                using var doc = JsonDocument.Parse(File.ReadAllText(currentPath, Encoding.UTF8));
                if (doc.RootElement.TryGetProperty("release", out var relProp)
                    && relProp.GetString() is { } rel
                    && MonitorSecurity.Matches(rel, "[0-9a-f]{32}"))
                {
                    var runtimeDir = Path.GetFullPath(Path.Combine(releasesRoot, rel));
                    if (runtimeDir.StartsWith(releasesRoot + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase))
                    {
                        var pythonExe = Path.Combine(runtimeDir, "venv", "Scripts", "python.exe");
                        var scriptPy = Path.Combine(runtimeDir, "scripts", "collector_service.py");
                        if (File.Exists(pythonExe) && File.Exists(scriptPy))
                        {
                            return (pythonExe, ["-I", scriptPy]);
                        }
                    }
                }
            }
        }
        catch { }
        return ("cmd.exe", ["/c", ExecutablePath]);
    }

    private static async Task<string> RunAsync(IReadOnlyList<string> args, string? origin, string? stdinText, TimeSpan timeout)
    {
        var scoped = ServerOrigin.CollectorArguments(args, origin);
        var (exe, prefix) = ResolveManagedCommand();
        var psi = new ProcessStartInfo
        {
            FileName = exe,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8
        };
        psi.Environment["PYTHONUTF8"] = "1";
        psi.Environment["PYTHONIOENCODING"] = "utf-8";
        foreach (var item in prefix)
        {
            psi.ArgumentList.Add(item);
        }
        foreach (var arg in scoped)
        {
            psi.ArgumentList.Add(arg);
        }

        using var process = Process.Start(psi) ?? throw new InvalidOperationException("无法启动本机连接器");
        if (stdinText is not null)
        {
            await process.StandardInput.WriteAsync(stdinText).ConfigureAwait(false);
        }
        process.StandardInput.Close();

        using var cts = new CancellationTokenSource(timeout);
        try
        {
            var stdoutTask = process.StandardOutput.ReadToEndAsync(cts.Token);
            var stderrTask = process.StandardError.ReadToEndAsync(cts.Token);
            await process.WaitForExitAsync(cts.Token).ConfigureAwait(false);
            var stdout = await stdoutTask.ConfigureAwait(false);
            _ = await stderrTask.ConfigureAwait(false);
            if (process.ExitCode != 0 || stdout.Length > 16 * 1024)
                throw new InvalidOperationException("本机连接器返回异常");
            return stdout;
        }
        catch (OperationCanceledException)
        {
            try { process.Kill(entireProcessTree: true); } catch { }
            throw;
        }
    }
}
