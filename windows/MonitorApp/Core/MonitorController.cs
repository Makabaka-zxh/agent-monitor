using System.Diagnostics;

namespace MonitorApp.Core;

public sealed class MonitorController : IDisposable
{
    public event Action? StateChanged;
    public event Action? LoginCompleted;
    public event Action<string, string>? QuotaAlertTriggered;

    public MonitorApi Api { get; } = new();
    public WindowsCollectorManager Collector { get; } = new();

    public string Origin { get; private set; } = "";
    public bool Connected { get; private set; }
    public bool LoginBusy { get; private set; }
    public bool PendingBrowserLogin { get; private set; }
    public bool QrBusy { get; private set; }
    public string QrError { get; private set; } = "";
    public QrLoginSession? QrLogin { get; private set; }

    public bool Refreshing { get; private set; }
    public bool Mutating { get; private set; }
    public List<Computer> Devices { get; private set; } = [];
    public List<MonitorTask> Tasks { get; private set; } = [];
    public AccountResponse? Account { get; private set; }
    public bool SyncOutput { get; private set; }
    public DateTimeOffset? LastSuccess { get; private set; }
    public string ConnectionMessage { get; private set; } = "";
    public UsageSummaryDto? Usage { get; private set; }
    public bool UsageRefreshing { get; private set; }
    public string UsageError { get; private set; } = "";
    public string? Notice { get; set; }
    public ComputerPairingCodeDto? PairingCode { get; private set; }
    public SyncDiagnosticsSample SyncDiagnostics { get; private set; } = new();

    public ToolFilter Tool { get; set; } = ToolFilter.All;
    public TaskScope Scope { get; set; } = TaskScope.Active;
    public string SearchQuery { get; set; } = "";

    private LoginSecret? _credential;
    private PairingSecret? _browserPairing;
    private Guid _generation = Guid.NewGuid();
    private DateTimeOffset _cooldownUntil = DateTimeOffset.MinValue;
    private CancellationTokenSource? _qrLoopCts;
    private CancellationTokenSource? _browserLoopCts;
    private bool _qrPollInFlight;
    private bool _filterInitialized;

    public MonitorController()
    {
        Origin = ServerOrigin.Current ?? "";
        LoadCredentialsForOrigin();
    }

    public bool IsFresh => LastSuccess is { } last && (DateTimeOffset.UtcNow - last).TotalSeconds < 25;
    public bool RateLimited => DateTimeOffset.UtcNow < _cooldownUntil;
    public int OnlineDeviceCount => IsFresh ? Devices.Count(d => d.Online) : 0;
    public string TokenFingerprint => _credential is { } c ? MonitorSecurity.Sha256Hex(c.Token) : "";
    public string? ActiveToken => Connected ? _credential?.Token : null;

    public ComputerPairingCodeDto? ActivePairingCode =>
        PairingCode is { } p && MonitorDate.Parse(p.ExpiresAt) is { } exp && exp > DateTimeOffset.UtcNow ? p : null;

    public List<MonitorTask> VisibleTasks =>
        Tasks.Where(t => t.Matches(Tool, Scope, IsFresh, SearchQuery)).ToList();

    public List<MonitorTask> PendingTasks =>
        WorkbenchVisibilityPolicy.PendingTasks(Tasks, Devices, IsFresh);

    public bool IsDeviceOnline(string? deviceId) =>
        IsFresh && !string.IsNullOrWhiteSpace(deviceId) && Devices.Any(d => d.Id == deviceId && d.Online);

    public Computer? BuiltInLocalHubComputer =>
        Devices.FirstOrDefault(d => d.Local);

    public UsageAlertSettings ReadAlertSettings()
    {
        if (string.IsNullOrEmpty(Origin) || string.IsNullOrEmpty(TokenFingerprint))
            return new UsageAlertSettings();
        return DpapiVault.Read<UsageAlertSettings>(Origin, "alert-settings:" + TokenFingerprint) ?? new UsageAlertSettings();
    }

    public void SaveAlertSettings(UsageAlertSettings settings)
    {
        if (string.IsNullOrEmpty(Origin) || string.IsNullOrEmpty(TokenFingerprint)) return;
        DpapiVault.Write(Origin, "alert-settings:" + TokenFingerprint, settings);
        NotifyChanged();
    }

    public LocalUiSettings ReadUiSettings()
    {
        var path = System.IO.Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Monitor", "ui-settings.json");
        try
        {
            if (System.IO.File.Exists(path))
                return System.Text.Json.JsonSerializer.Deserialize<LocalUiSettings>(System.IO.File.ReadAllText(path)) ?? new LocalUiSettings();
        }
        catch { }
        return new LocalUiSettings();
    }

    public void SaveUiSettings(LocalUiSettings settings)
    {
        var path = System.IO.Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Monitor", "ui-settings.json");
        try
        {
            System.IO.Directory.CreateDirectory(System.IO.Path.GetDirectoryName(path)!);
            System.IO.File.WriteAllText(path, System.Text.Json.JsonSerializer.Serialize(settings));
        }
        catch { }
        NotifyChanged();
    }

    public async Task SaveToolFilterAsync(ToolFilter next)
    {
        Tool = next;
        _filterInitialized = true;
        NotifyChanged();
        if (_credential is not { } login || !Connected) return;
        try
        {
            await Api.MutateAsync(Origin, "/api/native/preferences", System.Net.Http.HttpMethod.Patch,
                new Dictionary<string, object?> { ["tool_filter"] = next.WireKey() }, login.Token);
        }
        catch { }
    }

    public async Task<bool> RemoveComputerAsync(string deviceId)
    {
        if (_credential is not { } login || Mutating || !MonitorSecurity.Matches(deviceId, "[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
            return false;
        Mutating = true;
        var epoch = _generation;
        NotifyChanged();
        try
        {
            await Api.MutateAsync(Origin, $"/api/native/computers/{deviceId}", System.Net.Http.HttpMethod.Delete, token: login.Token);
            if (epoch == _generation) await RefreshWorkbenchAsync();
            return true;
        }
        catch (Exception ex)
        {
            HandleApiError(ex, login.Token, showAlert: true);
            return false;
        }
        finally
        {
            if (epoch == _generation) Mutating = false;
            NotifyChanged();
        }
    }

    public async Task<bool> RevokeNativeConnectionAsync(string readerId)
    {
        if (_credential is not { } login || Mutating || !MonitorSecurity.Matches(readerId, "[A-Za-z0-9_-]{32}"))
            return false;
        Mutating = true;
        var epoch = _generation;
        NotifyChanged();
        try
        {
            await Api.MutateAsync(Origin, $"/api/native/connections/{readerId}", System.Net.Http.HttpMethod.Delete, token: login.Token);
            if (epoch == _generation) await RefreshAccountAsync();
            return true;
        }
        catch (Exception ex)
        {
            HandleApiError(ex, login.Token, showAlert: true);
            return false;
        }
        finally
        {
            if (epoch == _generation) Mutating = false;
            NotifyChanged();
        }
    }

    public async Task<bool> RevokeBrowserSessionAsync(string sessionId, bool authorizesCurrent)
    {
        if (_credential is not { } login || Mutating || !MonitorSecurity.Matches(sessionId, "[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
            return false;
        Mutating = true;
        var epoch = _generation;
        NotifyChanged();
        try
        {
            await Api.MutateAsync(Origin, $"/api/native/sessions/{sessionId}", System.Net.Http.HttpMethod.Delete, token: login.Token);
            if (authorizesCurrent)
            {
                Invalidate(login.Token);
                return true;
            }
            if (epoch == _generation) await RefreshAccountAsync();
            return true;
        }
        catch (Exception ex)
        {
            HandleApiError(ex, login.Token, showAlert: true);
            return false;
        }
        finally
        {
            if (epoch == _generation) Mutating = false;
            NotifyChanged();
        }
    }

    private void LoadCredentialsForOrigin()
    {
        _credential = null;
        _browserPairing = null;
        Connected = false;
        PendingBrowserLogin = false;
        if (string.IsNullOrEmpty(Origin)) return;

        var saved = DpapiVault.Read<LoginSecret>(Origin, "login");
        if (saved is not null && saved.ExpiresAt > DateTimeOffset.UtcNow && MonitorSecurity.Matches(saved.Token, "nrd_[A-Za-z0-9_-]{43}"))
        {
            _credential = saved;
            Connected = true;
            return;
        }
        var pending = DpapiVault.Read<PairingSecret>(Origin, "pairing");
        if (pending is not null && pending.ExpiresAt > DateTimeOffset.UtcNow
            && MonitorSecurity.Matches(pending.Id, "[A-Za-z0-9_-]{32}")
            && MonitorSecurity.Matches(pending.Verifier, "[A-Za-z0-9_-]{43,128}"))
        {
            _browserPairing = pending;
            PendingBrowserLogin = true;
        }
    }

    public bool SaveServerOrigin(string? input)
    {
        if (Connected || LoginBusy || ServerOrigin.IsEnvironmentControlled) return false;
        ClearPendingBrowserLogin();
        ClearQrLogin();
        var normalized = ServerOrigin.Save(input);
        if (normalized is null)
        {
            Notice = "请输入有效的 HTTPS 服务器根地址（如 https://monitor.example.com，不带路径或查询参数）";
            NotifyChanged();
            return false;
        }
        Origin = normalized;
        _generation = Guid.NewGuid();
        _cooldownUntil = DateTimeOffset.MinValue;
        _filterInitialized = false;
        Notice = null;
        LoadCredentialsForOrigin();
        NotifyChanged();
        return true;
    }

    public void ClearQrLogin()
    {
        _qrLoopCts?.Cancel();
        _qrLoopCts = null;
        QrLogin = null;
        QrBusy = false;
        _qrPollInFlight = false;
        QrError = "";
        NotifyChanged();
    }

    public async Task StartQrLoginAsync(string? serverInput = null)
    {
        if (Connected || QrBusy) return;
        if (serverInput is not null && !ServerOrigin.IsEnvironmentControlled)
        {
            var trimmed = serverInput.Trim();
            if (string.IsNullOrEmpty(Origin) || (!string.IsNullOrEmpty(trimmed) && ServerOrigin.Normalize(trimmed) != Origin))
            {
                if (!SaveServerOrigin(trimmed)) return;
                if (Connected)
                {
                    await RefreshWorkbenchAsync();
                    await RefreshAccountAsync();
                    return;
                }
            }
        }
        if (string.IsNullOrEmpty(Origin))
        {
            QrError = "请先填写有效的 HTTPS 服务器地址";
            NotifyChanged();
            return;
        }
        if (DateTimeOffset.UtcNow < _cooldownUntil)
        {
            QrError = "请求较多，请稍后再试";
            NotifyChanged();
            return;
        }

        ClearQrLogin();
        QrBusy = true;
        var epoch = _generation;
        NotifyChanged();
        try
        {
            await Collector.RefreshAsync(Origin);
            if (epoch != _generation || Connected) return;
            var needsCollector = Collector.Status?.SupportsServerBinding == true && Collector.Status.Paired != true;
            var verifier = MonitorSecurity.CreateVerifier();
            var hostName = Environment.MachineName;
            if (string.IsNullOrWhiteSpace(hostName)) hostName = "Windows PC";
            if (hostName.Length > 42) hostName = hostName[..42];
            var readerName = $"Monitor · {hostName}";
            if (readerName.Length > 60) readerName = readerName[..60];

            var body = new Dictionary<string, object?>
            {
                ["name"] = hostName,
                ["platform"] = "Windows",
                ["include_collector"] = needsCollector,
                ["issue_collector_code"] = needsCollector,
                ["include_reader"] = true,
                ["reader_name"] = readerName,
                ["code_challenge"] = MonitorSecurity.Challenge(verifier)
            };
            var res = await Api.JsonAsync<QrPairingStartDto>(Origin, "/api/agent/pairing/start", System.Net.Http.HttpMethod.Post, body);
            if (epoch != _generation || Connected) return;

            var expectedUrl = Origin + "/#/pairing-confirm/" + res.RequestId;
            var expiry = MonitorDate.Parse(res.ExpiresAt);
            if (!MonitorSecurity.Matches(res.RequestId, "[A-Za-z0-9_-]{32}")
                || !MonitorSecurity.Matches(res.PollSecret, "[A-Za-z0-9_-]{32,128}")
                || res.VerificationUrl != expectedUrl
                || expiry is null || expiry <= DateTimeOffset.UtcNow)
            {
                throw new MonitorApiException(0, "服务返回的二维码参数无效");
            }

            var interval = Math.Clamp(res.Interval ?? 3, 3, 10);
            QrLogin = new QrLoginSession
            {
                Id = res.RequestId,
                PollSecret = res.PollSecret,
                Verifier = verifier,
                VerificationUrl = res.VerificationUrl,
                ExpiresAt = expiry.Value,
                IncludeCollector = needsCollector,
                IntervalSeconds = interval
            };
            QrError = "";
            StartQrPollingLoop(interval, epoch);
        }
        catch (Exception ex)
        {
            if (epoch != _generation) return;
            if (ex is MonitorApiException apiEx && apiEx.Status == 429)
                _cooldownUntil = DateTimeOffset.UtcNow.AddSeconds(apiEx.RetryAfterSeconds);
            QrError = ex is MonitorApiException ? ex.Message : "暂时无法生成登录二维码，请检查服务器地址或改用浏览器登录";
        }
        finally
        {
            if (epoch == _generation) QrBusy = false;
            NotifyChanged();
        }
    }

    private void StartQrPollingLoop(int intervalSeconds, Guid epoch)
    {
        _qrLoopCts?.Cancel();
        var cts = new CancellationTokenSource();
        _qrLoopCts = cts;
        var fastDelayMs = Math.Max(2150, intervalSeconds * 720); // ~2.15s stays safely below 30 req/60s limit
        _ = Task.Run(async () =>
        {
            while (!cts.IsCancellationRequested)
            {
                try
                {
                    await Task.Delay(fastDelayMs, cts.Token);
                }
                catch (OperationCanceledException) { return; }
                if (cts.IsCancellationRequested || _generation != epoch || QrLogin is null || Connected) return;
                if (System.Windows.Application.Current?.Dispatcher is not { HasShutdownStarted: false } disp) return;
                await disp.InvokeAsync(PollQrLoginAsync).Task.Unwrap();
            }
        });
    }

    public async Task PollQrLoginAsync()
    {
        if (_qrPollInFlight || LoginBusy || QrLogin is not { } active || Connected || string.IsNullOrEmpty(Origin) || DateTimeOffset.UtcNow < _cooldownUntil)
            return;
        if (active.ExpiresAt <= DateTimeOffset.UtcNow)
        {
            ClearQrLogin();
            QrError = "二维码已过期，请点击刷新重新生成";
            NotifyChanged();
            return;
        }
        _qrPollInFlight = true;
        var epoch = _generation;
        try
        {
            var body = new Dictionary<string, object?>
            {
                ["poll_secret"] = active.PollSecret,
                ["code_verifier"] = active.Verifier
            };
            var res = await Api.JsonAsync<QrPairingPollDto>(Origin, $"/api/agent/pairing/{active.Id}/poll", System.Net.Http.HttpMethod.Post, body);
            if (epoch != _generation || QrLogin?.Id != active.Id || Connected) return;
            if (res.Status == "pending") return;

            var expiry = MonitorDate.Parse(res.ReaderExpiresAt);
            if (res.Status != "approved" || res.ReaderMode != "full_app"
                || string.IsNullOrEmpty(res.ReaderToken)
                || !MonitorSecurity.Matches(res.ReaderToken, "nrd_[A-Za-z0-9_-]{43}")
                || expiry is null || expiry <= DateTimeOffset.UtcNow)
            {
                throw new MonitorApiException(0, "登录凭据校验失败，请重新生成二维码");
            }

            var secret = new LoginSecret { Token = res.ReaderToken, ExpiresAt = expiry.Value };
            try
            {
                DpapiVault.Write(Origin, "login", secret);
            }
            catch
            {
                try { await Api.MutateAsync(Origin, "/api/native/session", System.Net.Http.HttpMethod.Delete, token: res.ReaderToken); } catch { }
                throw;
            }
            DpapiVault.Remove(Origin, "pairing");
            _browserLoopCts?.Cancel();
            ClearQrLogin();
            _credential = secret;
            _browserPairing = null;
            PendingBrowserLogin = false;
            Connected = true;
            ConnectionMessage = "";
            LoginCompleted?.Invoke();
            NotifyChanged();

            var initialLoads = Task.WhenAll(
                RefreshWorkbenchAsync(),
                RefreshAccountAsync(),
                RefreshUsageAsync());

            if (!string.IsNullOrEmpty(res.CollectorCode) && MonitorSecurity.Matches(res.CollectorCode, "[A-F0-9]{12}"))
            {
                var originSnapshot = Origin;
                var codeSnapshot = res.CollectorCode;
                _ = Task.Run(async () =>
                {
                    if (System.Windows.Application.Current?.Dispatcher is { HasShutdownStarted: false } disp)
                    {
                        await disp.InvokeAsync(async () =>
                        {
                            await Collector.PairAsync(originSnapshot, codeSnapshot);
                            await RefreshWorkbenchAsync();
                        }).Task.Unwrap();
                    }
                });
            }
            await initialLoads;
        }
        catch (MonitorApiException apiEx)
        {
            if (epoch != _generation) return;
            if (apiEx.Status == 429)
            {
                _cooldownUntil = DateTimeOffset.UtcNow.AddSeconds(apiEx.RetryAfterSeconds);
                return;
            }
            if (apiEx.Status is 401 or 403 or 404 or 410)
            {
                ClearQrLogin();
                QrError = apiEx.Message;
                NotifyChanged();
            }
        }
        catch { }
        finally
        {
            if (epoch == _generation) _qrPollInFlight = false;
        }
    }

    public async Task BeginBrowserLoginAsync(string? serverInput = null)
    {
        if (Connected || LoginBusy) return;
        if (serverInput is not null && !ServerOrigin.IsEnvironmentControlled)
        {
            var trimmed = serverInput.Trim();
            if (string.IsNullOrEmpty(Origin) || (!string.IsNullOrEmpty(trimmed) && ServerOrigin.Normalize(trimmed) != Origin))
            {
                if (!SaveServerOrigin(trimmed)) return;
                if (Connected)
                {
                    await RefreshWorkbenchAsync();
                    await RefreshAccountAsync();
                    return;
                }
            }
        }
        if (string.IsNullOrEmpty(Origin))
        {
            Notice = "请先填写有效的 HTTPS 服务器地址";
            NotifyChanged();
            return;
        }
        ClearQrLogin();
        if (_browserPairing is { } existing && existing.ExpiresAt > DateTimeOffset.UtcNow)
        {
            OpenBrowserUrl($"{Origin}/#/native-connect/{existing.Id}");
            StartBrowserPollLoop(_generation);
            await PollBrowserLoginAsync();
            return;
        }

        LoginBusy = true;
        var epoch = _generation;
        NotifyChanged();
        try
        {
            var verifier = MonitorSecurity.CreateVerifier();
            var name = $"Monitor · {Environment.MachineName}";
            if (name.Length > 60) name = name[..60];
            var body = new Dictionary<string, object?>
            {
                ["device_name"] = name,
                ["mode"] = "full_app",
                ["code_challenge"] = MonitorSecurity.Challenge(verifier)
            };
            var res = await Api.JsonAsync<PairingStartDto>(Origin, "/api/native/pairing/start", System.Net.Http.HttpMethod.Post, body);
            if (epoch != _generation) return;
            var expiry = MonitorDate.Parse(res.ExpiresAt);
            var expectedUrl = $"{Origin}/#/native-connect/{res.RequestId}";
            if (!MonitorSecurity.Matches(res.RequestId, "[A-Za-z0-9_-]{32}") || res.VerificationUrl != expectedUrl || expiry is null || expiry <= DateTimeOffset.UtcNow)
                throw new MonitorApiException(0, "服务返回的登录请求无效");

            var pairing = new PairingSecret { Id = res.RequestId, Verifier = verifier, ExpiresAt = expiry.Value };
            DpapiVault.Write(Origin, "pairing", pairing);
            _browserPairing = pairing;
            PendingBrowserLogin = true;
            OpenBrowserUrl(expectedUrl);
            StartBrowserPollLoop(epoch);
        }
        catch (Exception ex)
        {
            if (epoch == _generation) Notice = ex is MonitorApiException ? ex.Message : "暂时无法发起登录，请检查网络";
        }
        finally
        {
            if (epoch == _generation) LoginBusy = false;
            NotifyChanged();
        }
    }

    private void StartBrowserPollLoop(Guid epoch)
    {
        _browserLoopCts?.Cancel();
        var cts = new CancellationTokenSource();
        _browserLoopCts = cts;
        _ = Task.Run(async () =>
        {
            while (!cts.IsCancellationRequested)
            {
                try { await Task.Delay(2500, cts.Token); } catch { return; }
                if (cts.IsCancellationRequested || _generation != epoch || !PendingBrowserLogin || Connected) return;
                if (System.Windows.Application.Current?.Dispatcher is not { HasShutdownStarted: false } disp) return;
                await disp.InvokeAsync(PollBrowserLoginAsync).Task.Unwrap();
            }
        });
    }

    public async Task PollBrowserLoginAsync()
    {
        if (LoginBusy || _browserPairing is not { } pairing || Connected || string.IsNullOrEmpty(Origin)) return;
        if (pairing.ExpiresAt <= DateTimeOffset.UtcNow)
        {
            ClearPendingBrowserLogin();
            Notice = "浏览器确认已过期，请重新发起";
            NotifyChanged();
            return;
        }
        LoginBusy = true;
        var epoch = _generation;
        try
        {
            var res = await Api.JsonAsync<PairingPollDto>(Origin, $"/api/native/pairing/{pairing.Id}/poll", System.Net.Http.HttpMethod.Post,
                new Dictionary<string, object?> { ["code_verifier"] = pairing.Verifier });
            if (epoch != _generation || res.Status == "pending") return;
            var expiry = MonitorDate.Parse(res.ExpiresAt);
            if (res.Status != "approved" || res.Mode != "full_app" || string.IsNullOrEmpty(res.ReaderToken)
                || !MonitorSecurity.Matches(res.ReaderToken, "nrd_[A-Za-z0-9_-]{43}") || expiry is null || expiry <= DateTimeOffset.UtcNow)
            {
                throw new MonitorApiException(0, "登录验证未通过");
            }
            var secret = new LoginSecret { Token = res.ReaderToken, ExpiresAt = expiry.Value };
            try
            {
                DpapiVault.Write(Origin, "login", secret);
            }
            catch
            {
                try { await Api.MutateAsync(Origin, "/api/native/session", System.Net.Http.HttpMethod.Delete, token: res.ReaderToken); } catch { }
                throw;
            }
            DpapiVault.Remove(Origin, "pairing");
            _browserLoopCts?.Cancel();
            _credential = secret;
            _browserPairing = null;
            PendingBrowserLogin = false;
            Connected = true;
            LoginCompleted?.Invoke();
            NotifyChanged();
            await Task.WhenAll(
                RefreshWorkbenchAsync(),
                RefreshAccountAsync(),
                RefreshUsageAsync());
        }
        catch (MonitorApiException apiEx)
        {
            if (epoch != _generation) return;
            if (apiEx.Status is 401 or 403 or 404 or 410) ClearPendingBrowserLogin();
            Notice = apiEx.Message;
        }
        catch { }
        finally
        {
            if (epoch == _generation) LoginBusy = false;
            NotifyChanged();
        }
    }

    public void ClearPendingBrowserLogin()
    {
        _browserLoopCts?.Cancel();
        _browserLoopCts = null;
        if (!string.IsNullOrEmpty(Origin)) DpapiVault.Remove(Origin, "pairing");
        _browserPairing = null;
        PendingBrowserLogin = false;
        LoginBusy = false;
        NotifyChanged();
    }

    public async Task RefreshWorkbenchAsync()
    {
        if (_credential is not { } login || Refreshing || !Connected || DateTimeOffset.UtcNow < _cooldownUntil) return;
        if (login.ExpiresAt <= DateTimeOffset.UtcNow) { Invalidate(login.Token); return; }
        Refreshing = true;
        var wasFirstScreen = LastSuccess is null;
        var sw = Stopwatch.StartNew();
        var epoch = _generation;
        NotifyChanged();
        try
        {
            var res = await Api.JsonAsync<WorkbenchResponse>(Origin, "/api/native/workbench", System.Net.Http.HttpMethod.Get, token: login.Token);
            if (epoch != _generation || _credential?.Token != login.Token) return;
            sw.Stop();
            var devMap = res.Devices
                .Where(d => !string.IsNullOrEmpty(d.Id))
                .GroupBy(d => d.Id, StringComparer.Ordinal)
                .ToDictionary(g => g.Key, g => g.First().Name, StringComparer.Ordinal);
            foreach (var task in res.Tasks)
            {
                if (string.IsNullOrWhiteSpace(task.DeviceName) && devMap.TryGetValue(task.DeviceId, out var resolvedName))
                {
                    task.DeviceName = resolvedName;
                }
            }
            Devices = res.Devices;
            Tasks = res.Tasks;
            SyncOutput = res.Preferences.SyncOutput;
            if (!_filterInitialized && !string.IsNullOrWhiteSpace(res.Preferences.ToolFilter))
            {
                Tool = ToolFilterExtensions.ParseTool(res.Preferences.ToolFilter);
                _filterInitialized = true;
            }
            var now = DateTimeOffset.UtcNow;
            LastSuccess = now;
            ConnectionMessage = "";
            SyncDiagnostics = new SyncDiagnosticsSample
            {
                FirstScreenMs = wasFirstScreen || SyncDiagnostics.FirstScreenMs < 0 ? sw.ElapsedMilliseconds : SyncDiagnostics.FirstScreenMs,
                LastRefreshMs = sw.ElapsedMilliseconds,
                ComputerCompletedAt = MonitorDate.Parse(res.SyncTimings?.ComputerCompletedAt),
                ServerReceivedAt = MonitorDate.Parse(res.SyncTimings?.ServerReceivedAt ?? res.GeneratedAt),
                ClientDisplayedAt = now
            };
            if (res.Usage is not null)
            {
                if (Usage is null)
                {
                    Usage = res.Usage;
                }
                else if (res.Usage.Providers is { Count: > 0 } liveProviders && Usage.Providers is not null)
                {
                    foreach (var lp in liveProviders)
                    {
                        var existing = Usage.Providers.FirstOrDefault(p => p.Tool == lp.Tool);
                        if (existing is not null)
                        {
                            if (lp.TodayTokens is not null) existing.TodayTokens = lp.TodayTokens;
                            if (lp.Quotas is not null) existing.Quotas = lp.Quotas;
                        }
                        else
                        {
                            Usage.Providers.Add(lp);
                        }
                    }
                }
                ProcessUsageAlerts(res.Usage);
            }
        }
        catch (Exception ex)
        {
            if (epoch != _generation) return;
            ConnectionMessage = "暂时无法连接同步服务，点击重试";
            HandleApiError(ex, login.Token, showAlert: false);
        }
        finally
        {
            if (epoch == _generation) Refreshing = false;
            NotifyChanged();
        }
    }

    public async Task RefreshUsageAsync()
    {
        if (_credential is not { } login || UsageRefreshing || !Connected || DateTimeOffset.UtcNow < _cooldownUntil) return;
        UsageRefreshing = true;
        var epoch = _generation;
        NotifyChanged();
        try
        {
            var res = await Api.JsonAsync<UsageSummaryDto>(Origin, "/api/native/usage", System.Net.Http.HttpMethod.Get, token: login.Token);
            if (epoch != _generation || _credential?.Token != login.Token) return;
            if (res.Loading && Usage is not null)
            {
                Usage.Loading = true;
            }
            else
            {
                Usage = res;
            }
            UsageError = "";
            ProcessUsageAlerts(res);
        }
        catch (Exception ex)
        {
            if (epoch != _generation) return;
            UsageError = Usage is null ? "暂时无法读取用量" : "暂未更新，显示上次记录";
            HandleApiError(ex, login.Token, showAlert: false);
        }
        finally
        {
            if (epoch == _generation) UsageRefreshing = false;
            NotifyChanged();
        }
    }

    private void ProcessUsageAlerts(UsageSummaryDto summary)
    {
        if (string.IsNullOrEmpty(Origin) || string.IsNullOrEmpty(TokenFingerprint)) return;
        var settings = ReadAlertSettings();
        var recordKey = "alert-state:" + TokenFingerprint;
        var state = DpapiVault.Read<UsageAlertRecord>(Origin, recordKey) ?? new UsageAlertRecord();
        var fired = UsageAlertEvaluator.Evaluate(summary, settings, state, DateTimeOffset.UtcNow);
        DpapiVault.Write(Origin, recordKey, state);
        foreach (var item in fired)
        {
            QuotaAlertTriggered?.Invoke(item.Title, item.Body);
        }
    }

    public async Task RefreshAccountAsync()
    {
        if (_credential is not { } login || !Connected || DateTimeOffset.UtcNow < _cooldownUntil) return;
        var epoch = _generation;
        try
        {
            var res = await Api.JsonAsync<AccountResponse>(Origin, "/api/native/account", System.Net.Http.HttpMethod.Get, token: login.Token);
            if (epoch != _generation || _credential?.Token != login.Token) return;
            Account = res;
            SyncOutput = res.Preferences.SyncOutput;
            NotifyChanged();
        }
        catch (Exception ex)
        {
            HandleApiError(ex, login.Token, showAlert: false);
        }
    }

    public async Task ArchiveTaskAsync(MonitorTask task)
    {
        if (_credential is not { } login || Mutating) return;
        Mutating = true;
        var epoch = _generation;
        NotifyChanged();
        try
        {
            await Api.MutateAsync(Origin, "/api/native/tasks/archive", System.Net.Http.HttpMethod.Patch,
                new Dictionary<string, object?> { ["task_id"] = task.Id, ["archived"] = !task.Archived }, login.Token);
            if (epoch == _generation) await RefreshWorkbenchAsync();
        }
        catch (Exception ex)
        {
            HandleApiError(ex, login.Token, showAlert: true);
        }
        finally
        {
            if (epoch == _generation) Mutating = false;
            NotifyChanged();
        }
    }

    public async Task<bool> SetResultSyncAsync(bool enabled)
    {
        if (_credential is not { } login || Mutating) return false;
        Mutating = true;
        var epoch = _generation;
        NotifyChanged();
        try
        {
            var confirmed = await Api.JsonAsync<PreferencesDto>(Origin, "/api/native/preferences", System.Net.Http.HttpMethod.Patch,
                new Dictionary<string, object?> { ["sync_output"] = enabled }, login.Token);
            if (epoch != _generation) return false;
            SyncOutput = confirmed.SyncOutput;
            return confirmed.SyncOutput == enabled;
        }
        catch (Exception ex)
        {
            HandleApiError(ex, login.Token, showAlert: true);
            return false;
        }
        finally
        {
            if (epoch == _generation) Mutating = false;
            NotifyChanged();
        }
    }

    public async Task<bool> SaveProfileAsync(string displayName, string avatarDataUrl)
    {
        var trimmed = displayName.Trim();
        if (trimmed.Length is < 1 or > 40)
        {
            Notice = "昵称请输入 1–40 个字符";
            NotifyChanged();
            return false;
        }
        if (_credential is not { } login || Mutating) return false;
        Mutating = true;
        var epoch = _generation;
        NotifyChanged();
        try
        {
            await Api.MutateAsync(Origin, "/api/native/profile", System.Net.Http.HttpMethod.Patch,
                new Dictionary<string, object?> { ["display_name"] = trimmed, ["avatar"] = avatarDataUrl }, login.Token);
            if (epoch == _generation) await RefreshAccountAsync();
            return true;
        }
        catch (Exception ex)
        {
            HandleApiError(ex, login.Token, showAlert: true);
            return false;
        }
        finally
        {
            if (epoch == _generation) Mutating = false;
            NotifyChanged();
        }
    }

    public async Task<ComputerPairingCodeDto?> CreatePairingCodeAsync()
    {
        if (_credential is not { } login || Mutating) return null;
        Mutating = true;
        var epoch = _generation;
        NotifyChanged();
        try
        {
            var res = await Api.JsonAsync<ComputerPairingCodeDto>(Origin, "/api/native/computers/pairing", System.Net.Http.HttpMethod.Post, token: login.Token);
            if (epoch != _generation) return null;
            if (!MonitorSecurity.Matches(res.Code, "[A-F0-9]{12}"))
                throw new MonitorApiException(0, "返回的配对码格式无效");
            PairingCode = res;
            return res;
        }
        catch (Exception ex)
        {
            HandleApiError(ex, login.Token, showAlert: true);
            return null;
        }
        finally
        {
            if (epoch == _generation) Mutating = false;
            NotifyChanged();
        }
    }

    public async Task LogoutAsync()
    {
        if (_credential is not { } login || Mutating) return;
        Mutating = true;
        NotifyChanged();
        try
        {
            await Api.MutateAsync(Origin, "/api/native/session", System.Net.Http.HttpMethod.Delete, token: login.Token);
            Invalidate(login.Token);
        }
        catch (MonitorApiException apiEx) when (apiEx.Status == 401)
        {
            Invalidate(login.Token);
        }
        catch (Exception ex)
        {
            Mutating = false;
            HandleApiError(ex, login.Token, showAlert: true);
        }
    }

    public void Invalidate(string token)
    {
        if (_credential?.Token != token) return;
        if (!string.IsNullOrEmpty(Origin)) DpapiVault.Remove(Origin, "login");
        ClearQrLogin();
        _browserLoopCts?.Cancel();
        _generation = Guid.NewGuid();
        _credential = null;
        Connected = false;
        Refreshing = false;
        Mutating = false;
        Account = null;
        Tasks = [];
        Devices = [];
        LastSuccess = null;
        PairingCode = null;
        Usage = null;
        UsageRefreshing = false;
        UsageError = "";
        _filterInitialized = false;
        NotifyChanged();
    }

    public void HandleApiError(Exception ex, string token, bool showAlert)
    {
        if (_credential?.Token != token) return;
        if (ex is MonitorApiException apiEx)
        {
            if (apiEx.Status is 401 or 403)
            {
                Invalidate(token);
                Notice = "连接已失效，请重新登录";
                NotifyChanged();
                return;
            }
            if (apiEx.Status == 429)
            {
                _cooldownUntil = DateTimeOffset.UtcNow.AddSeconds(apiEx.RetryAfterSeconds);
            }
            if (showAlert) Notice = apiEx.Message;
        }
        else if (showAlert)
        {
            Notice = "暂时无法连接，请稍后重试";
        }
        NotifyChanged();
    }

    public static void OpenBrowserUrl(string url)
    {
        if (!url.StartsWith("https://", StringComparison.OrdinalIgnoreCase)) return;
        try
        {
            Process.Start(new ProcessStartInfo { FileName = url, UseShellExecute = true });
        }
        catch { }
    }

    private void NotifyChanged() => StateChanged?.Invoke();

    public void Dispose()
    {
        _qrLoopCts?.Cancel();
        _browserLoopCts?.Cancel();
        Api.Dispose();
    }
}
