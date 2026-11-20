using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace MonitorApp.Core;

public static partial class ServerOrigin
{
    [GeneratedRegex(@"^(?:[A-Za-z0-9.-]+|\[[0-9A-Fa-f:.]+\])(?::[0-9]+)?$", RegexOptions.CultureInvariant)]
    private static partial Regex HostPortRegex();

    [GeneratedRegex(@"^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$", RegexOptions.CultureInvariant)]
    private static partial Regex DnsLabelRegex();

    private static string SettingsPath =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Monitor", "server-origin.json");

    public static bool IsEnvironmentControlled =>
        !string.IsNullOrWhiteSpace(Environment.GetEnvironmentVariable("MONITOR_SERVER_URL"));

    public static string? Current
    {
        get
        {
            var env = Environment.GetEnvironmentVariable("MONITOR_SERVER_URL")?.Trim();
            if (!string.IsNullOrEmpty(env))
            {
                return Normalize(env);
            }
            try
            {
                if (!File.Exists(SettingsPath)) return null;
                var json = File.ReadAllText(SettingsPath);
                using var doc = JsonDocument.Parse(json);
                if (doc.RootElement.TryGetProperty("origin", out var prop) && prop.ValueKind == JsonValueKind.String)
                {
                    return Normalize(prop.GetString());
                }
            }
            catch
            {
                // Ignore corrupt settings and treat as unconfigured.
            }
            return null;
        }
    }

    public static string? Save(string? raw)
    {
        if (IsEnvironmentControlled) return null;
        var normalized = Normalize(raw);
        if (normalized is null) return null;
        var dir = Path.GetDirectoryName(SettingsPath)!;
        Directory.CreateDirectory(dir);
        var payload = JsonSerializer.Serialize(new { origin = normalized });
        File.WriteAllText(SettingsPath, payload);
        return normalized;
    }

    public static string? SuggestedOrigin()
    {
        try
        {
            var collectorConfig = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "Monitor", "collector", "agent", "config.json");
            if (File.Exists(collectorConfig) && new FileInfo(collectorConfig).Length <= 64 * 1024)
            {
                using var doc = JsonDocument.Parse(File.ReadAllText(collectorConfig));
                if (doc.RootElement.TryGetProperty("server", out var server) && server.ValueKind == JsonValueKind.String)
                {
                    var normalized = Normalize(server.GetString());
                    if (normalized is not null) return normalized;
                }
            }
        }
        catch
        {
            // Optional convenience hint only.
        }
        return null;
    }

    public static string? Normalize(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw)) return null;
        var trimmed = raw.Trim();
        if (trimmed.Length > 512) return null;
        foreach (var ch in trimmed)
        {
            if (ch <= 32 || ch >= 127 || ch == '%' || ch == '\\') return null;
        }
        if (trimmed.Contains('?') || trimmed.Contains('#')) return null;

        if (!Uri.TryCreate(trimmed, UriKind.Absolute, out var uri)) return null;
        if (!string.Equals(uri.Scheme, "https", StringComparison.OrdinalIgnoreCase)) return null;
        if (!string.IsNullOrEmpty(uri.UserInfo)) return null;
        if (uri.AbsolutePath is not ("" or "/")) return null;

        var authority = uri.Authority;
        if (string.IsNullOrEmpty(authority) || authority.EndsWith(':')) return null;
        if (!HostPortRegex().IsMatch(authority)) return null;
        if (!uri.IsDefaultPort && (uri.Port < 1 || uri.Port > 65535)) return null;

        var host = uri.Host;
        string canonicalHost;
        if (host.StartsWith('[') && host.EndsWith(']'))
        {
            var inner = host[1..^1];
            if (!IPAddress.TryParse(inner, out var ip) || ip.AddressFamily != AddressFamily.InterNetworkV6) return null;
            canonicalHost = "[" + inner.ToLowerInvariant() + "]";
        }
        else if (host.Contains(':'))
        {
            if (!IPAddress.TryParse(host, out var ip) || ip.AddressFamily != AddressFamily.InterNetworkV6) return null;
            canonicalHost = "[" + host.ToLowerInvariant() + "]";
        }
        else
        {
            if (host.Length > 253 || host.StartsWith('.') || host.EndsWith('.') || host.Contains("..")) return null;
            var labels = host.Split('.');
            if (labels.Any(label => !DnsLabelRegex().IsMatch(label))) return null;
            canonicalHost = host.ToLowerInvariant();
        }

        var portSuffix = (!uri.IsDefaultPort && uri.Port != 443) ? $":{uri.Port}" : "";
        return $"https://{canonicalHost}{portSuffix}";
    }

    public static string[] CollectorArguments(IReadOnlyList<string> args, string? origin)
    {
        var normalized = Normalize(origin) ?? throw new InvalidOperationException("请先填写有效的 HTTPS 服务器地址");
        var result = new List<string>(args.Count + 2);
        result.AddRange(args);
        result.Add("--server");
        result.Add(normalized);
        return result.ToArray();
    }
}
