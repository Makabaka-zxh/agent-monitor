using System.IO;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json;

namespace MonitorApp.Core;

public sealed class MonitorApiException : Exception
{
    public int Status { get; }
    public int RetryAfterSeconds { get; }

    public MonitorApiException(int status, string message, int retryAfterSeconds = 5)
        : base(message)
    {
        Status = status;
        RetryAfterSeconds = Math.Clamp(retryAfterSeconds, 1, 300);
    }
}

public sealed class MonitorApi : IDisposable
{
    private readonly HttpClient _http;
    private readonly HttpClient _rawHttp;
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNameCaseInsensitive = false
    };

    public MonitorApi()
    {
        _http = new HttpClient(CreateHandler(enableCompression: true))
        {
            Timeout = TimeSpan.FromSeconds(18)
        };
        _rawHttp = new HttpClient(CreateHandler(enableCompression: false))
        {
            Timeout = TimeSpan.FromSeconds(45)
        };
    }

    private static SocketsHttpHandler CreateHandler(bool enableCompression) => new()
    {
        AllowAutoRedirect = false,
        UseCookies = false,
        AutomaticDecompression = enableCompression
            ? DecompressionMethods.GZip | DecompressionMethods.Deflate | DecompressionMethods.Brotli
            : DecompressionMethods.None,
        EnableMultipleHttp2Connections = true,
        PooledConnectionLifetime = TimeSpan.FromMinutes(5),
        PooledConnectionIdleTimeout = TimeSpan.FromMinutes(2),
        KeepAlivePingPolicy = HttpKeepAlivePingPolicy.Always,
        KeepAlivePingDelay = TimeSpan.FromSeconds(25),
        KeepAlivePingTimeout = TimeSpan.FromSeconds(10),
        ConnectTimeout = TimeSpan.FromSeconds(8)
    };

    public static Uri BuildUri(string origin, string path, IReadOnlyList<(string Key, string Value)>? query = null)
    {
        var normalized = ServerOrigin.Normalize(origin)
            ?? throw new MonitorApiException(0, "请先填写有效的 HTTPS 服务器地址");
        var builder = new UriBuilder(normalized)
        {
            Path = path
        };
        if (query is { Count: > 0 })
        {
            var parts = new List<string>(query.Count);
            foreach (var (key, value) in query)
            {
                parts.Add($"{Uri.EscapeDataString(key)}={Uri.EscapeDataString(value)}");
            }
            builder.Query = string.Join("&", parts);
        }
        return builder.Uri;
    }

    public async Task<T> JsonAsync<T>(
        string origin,
        string path,
        HttpMethod method,
        object? body = null,
        string? token = null,
        IReadOnlyList<(string Key, string Value)>? query = null,
        CancellationToken ct = default)
    {
        var maxBytes = path == "/api/native/workbench"
            ? 8 * 1024 * 1024
            : path == "/api/native/tasks/result" ? 2 * 1024 * 1024 : 1024 * 1024;
        var raw = await SendBytesAsync(origin, path, method, body, token, maxBytes, query, ct).ConfigureAwait(false);
        return JsonSerializer.Deserialize<T>(raw, JsonOptions)
            ?? throw new MonitorApiException(0, "服务返回的内容无效，请重试");
    }

    public async Task MutateAsync(
        string origin,
        string path,
        HttpMethod method,
        object? body = null,
        string? token = null,
        CancellationToken ct = default)
    {
        _ = await SendBytesAsync(origin, path, method, body, token, 1024 * 1024, null, ct).ConfigureAwait(false);
    }

    public async Task<byte[]> DownloadVerifiedAsync(
        string origin,
        string path,
        string token,
        int expectedSize,
        string expectedSha256,
        IReadOnlyList<(string Key, string Value)> query,
        CancellationToken ct = default)
    {
        if (expectedSize < 0 || expectedSize > 32 * 1024 * 1024 || !MonitorSecurity.Matches(expectedSha256, "[0-9a-f]{64}"))
            throw new MonitorApiException(0, "文件元信息无效，请刷新后重试");
        var data = await SendBytesAsync(origin, path, HttpMethod.Get, null, token, expectedSize, query, ct, identityEncoding: true).ConfigureAwait(false);
        if (data.Length != expectedSize || MonitorSecurity.Sha256Hex(data) != expectedSha256)
            throw new MonitorApiException(0, "文件 SHA-256 校验未通过，请刷新结果后重试");
        return data;
    }

    private async Task<byte[]> SendBytesAsync(
        string origin,
        string path,
        HttpMethod method,
        object? body,
        string? token,
        int maxBytes,
        IReadOnlyList<(string Key, string Value)>? query,
        CancellationToken ct,
        bool identityEncoding = false)
    {
        var activeOrigin = ServerOrigin.Normalize(origin)
            ?? throw new MonitorApiException(0, "请先填写有效的 HTTPS 服务器地址");
        using var request = new HttpRequestMessage(method, BuildUri(activeOrigin, path, query));
        request.Headers.TryAddWithoutValidation("Accept", identityEncoding ? "application/octet-stream" : "application/json");
        request.Headers.TryAddWithoutValidation("Origin", activeOrigin);
        if (identityEncoding)
        {
            request.Headers.TryAddWithoutValidation("Accept-Encoding", "identity");
        }
        if (token is not null)
        {
            if (!MonitorSecurity.Matches(token, "nrd_[A-Za-z0-9_-]{43}"))
                throw new MonitorApiException(401, "连接已失效，请重新登录");
            request.Headers.TryAddWithoutValidation("Authorization", "Bearer " + token);
        }
        if (body is not null)
        {
            var json = JsonSerializer.Serialize(body, JsonOptions);
            request.Content = new StringContent(json, Encoding.UTF8, "application/json");
        }

        var client = identityEncoding ? _rawHttp : _http;
        using var response = await client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
        var status = (int)response.StatusCode;
        var succeeded = status is >= 200 and < 300;
        var cap = succeeded ? maxBytes : 16 * 1024;

        await using var stream = await response.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
        using var ms = new MemoryStream();
        var buffer = new byte[32 * 1024];
        while (true)
        {
            var read = await stream.ReadAsync(buffer, ct).ConfigureAwait(false);
            if (read == 0) break;
            if (ms.Length + read > cap)
                throw new MonitorApiException(0, "服务返回内容超过大小上限");
            ms.Write(buffer, 0, read);
        }
        var data = ms.ToArray();
        if (!succeeded)
        {
            string? detail = null;
            try
            {
                using var doc = JsonDocument.Parse(data);
                if (doc.RootElement.TryGetProperty("detail", out var d) && d.ValueKind == JsonValueKind.String)
                    detail = d.GetString();
            }
            catch { }

            var retry = 5;
            if (response.Headers.RetryAfter?.Delta is { } delta)
                retry = (int)Math.Ceiling(delta.TotalSeconds);
            var fallback = status is 401 or 403 ? "连接已失效，请重新登录" : "请求未完成，请稍后重试";
            var message = string.IsNullOrWhiteSpace(detail) ? fallback : detail!;
            if (message.Length > 300) message = message[..300];
            throw new MonitorApiException(status, message, retry);
        }
        return data;
    }

    public void Dispose()
    {
        _http.Dispose();
        _rawHttp.Dispose();
    }
}
