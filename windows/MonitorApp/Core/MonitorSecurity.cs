using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace MonitorApp.Core;

public static class MonitorSecurity
{
    public static bool Matches(string? text, string pattern)
    {
        if (text is null) return false;
        return Regex.IsMatch(text, @"\A(?:" + pattern + @")\z", RegexOptions.CultureInvariant);
    }

    public static string Sha256Hex(ReadOnlySpan<byte> data)
    {
        Span<byte> hash = stackalloc byte[32];
        SHA256.HashData(data, hash);
        return Convert.ToHexString(hash).ToLowerInvariant();
    }

    public static string Sha256Hex(string value) =>
        Sha256Hex(Encoding.UTF8.GetBytes(value));

    public static string Base64Url(ReadOnlySpan<byte> bytes) =>
        Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    public static string CreateVerifier()
    {
        Span<byte> random = stackalloc byte[32];
        RandomNumberGenerator.Fill(random);
        return Base64Url(random);
    }

    public static string Challenge(string verifier)
    {
        Span<byte> hash = stackalloc byte[32];
        SHA256.HashData(Encoding.ASCII.GetBytes(verifier), hash);
        return Base64Url(hash);
    }

    public static string SafeFileName(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw)) return "附件";
        var sb = new StringBuilder(raw.Length);
        foreach (var ch in raw)
        {
            if (char.IsControl(ch) || ch is '/' or '\\' or ':' or '*' or '?' or '"' or '<' or '>' or '|')
                sb.Append('_');
            else
                sb.Append(ch);
        }
        var cleaned = sb.ToString().Trim('.', ' ');
        if (string.IsNullOrEmpty(cleaned)) return "附件";
        return cleaned.Length <= 180 ? cleaned : cleaned[..180];
    }
}

public static class DpapiVault
{
    private static string VaultDirectory(string origin)
    {
        var normalized = ServerOrigin.Normalize(origin)
            ?? throw new InvalidOperationException("请先填写有效的 HTTPS 服务器地址");
        var scope = MonitorSecurity.Sha256Hex(normalized);
        return Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Monitor", "vault-v1", scope);
    }

    private static string EntryPath(string origin, string key)
    {
        var keyHash = MonitorSecurity.Sha256Hex(key);
        return Path.Combine(VaultDirectory(origin), keyHash + ".bin");
    }

    public static T? Read<T>(string origin, string key)
    {
        var path = EntryPath(origin, key);
        if (!File.Exists(path)) return default;
        try
        {
            var encrypted = File.ReadAllBytes(path);
            var entropy = Encoding.UTF8.GetBytes("MonitorVaultV1:" + ServerOrigin.Normalize(origin) + ":" + key);
            var plain = ProtectedData.Unprotect(encrypted, entropy, DataProtectionScope.CurrentUser);
            return JsonSerializer.Deserialize<T>(plain);
        }
        catch
        {
            try { File.Delete(path); } catch { }
            return default;
        }
    }

    public static void Write<T>(string origin, string key, T value)
    {
        var path = EntryPath(origin, key);
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var plain = JsonSerializer.SerializeToUtf8Bytes(value);
        var entropy = Encoding.UTF8.GetBytes("MonitorVaultV1:" + ServerOrigin.Normalize(origin) + ":" + key);
        var encrypted = ProtectedData.Protect(plain, entropy, DataProtectionScope.CurrentUser);
        var temp = path + ".tmp";
        File.WriteAllBytes(temp, encrypted);
        File.Move(temp, path, overwrite: true);
    }

    public static void Remove(string origin, string key)
    {
        var path = EntryPath(origin, key);
        if (File.Exists(path))
        {
            File.Delete(path);
        }
    }
}
