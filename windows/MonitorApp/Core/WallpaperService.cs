using System.IO;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace MonitorApp.Core;

public sealed record BingWallpaperInfo(
    BitmapSource? Image,
    string Title,
    string Copyright,
    string Link,
    string State); // "updated" | "cached" | "offline" | "restricted" | "unavailable"

/// <summary>
/// Opt-in local &amp; Bing daily wallpaper manager for Monitor Desktop.
/// Matches Android NativeBingWallpaperPolicy security rules (no cookies, no redirects, bounded sizes, atomic cache).
/// Never modifies the user's Windows system desktop wallpaper.
/// </summary>
public static class WallpaperService
{
    public const string BingMetadataUrl = "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=zh-CN";
    private const int MaxMetadataBytes = 64 * 1024;
    private const int MaxBingImageBytes = 8 * 1024 * 1024;
    private const int MaxDecodedEdge = 2400;

    private static readonly Regex OhrIdRegex = new(@"^OHR\.[A-Za-z0-9_-]{1,210}_(?:UHD|[0-9]{3,4}x[0-9]{3,4})\.jpg$", RegexOptions.Compiled);
    private static readonly HttpClient PublicHttp = new(new SocketsHttpHandler
    {
        AllowAutoRedirect = false,
        UseCookies = false,
        ConnectTimeout = TimeSpan.FromSeconds(8)
    })
    {
        Timeout = TimeSpan.FromSeconds(18)
    };

    private static string WallpaperDir
    {
        get
        {
            var dir = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "Monitor", "wallpapers");
            Directory.CreateDirectory(dir);
            return dir;
        }
    }

    private static string CustomImagePath => Path.Combine(WallpaperDir, "custom-wallpaper.jpg");
    private static string BingImagePath => Path.Combine(WallpaperDir, "bing-daily.jpg");
    private static string BingMetaPath => Path.Combine(WallpaperDir, "bing-daily.json");

    public static bool HasCustomWallpaper => File.Exists(CustomImagePath);

    public static BitmapSource? LoadCustomWallpaper()
    {
        try
        {
            if (!File.Exists(CustomImagePath)) return null;
            var bytes = File.ReadAllBytes(CustomImagePath);
            return DecodeBoundedBitmap(bytes);
        }
        catch
        {
            return null;
        }
    }

    public static BitmapSource ImportCustomWallpaper(string sourceFilePath)
    {
        var info = new FileInfo(sourceFilePath);
        if (!info.Exists || info.Length <= 0 || info.Length > 25 * 1024 * 1024)
            throw new InvalidOperationException("请选择小于 25 MB 的有效图片文件。");

        var raw = File.ReadAllBytes(sourceFilePath);
        var decoded = DecodeBoundedBitmap(raw)
                      ?? throw new InvalidOperationException("无法解析这张图片，请选择 JPG、PNG 或 BMP 格式图片。");

        var encoder = new JpegBitmapEncoder { QualityLevel = 91 };
        encoder.Frames.Add(BitmapFrame.Create(decoded));
        var tmpPath = CustomImagePath + ".tmp";
        using (var fs = File.Create(tmpPath))
        {
            encoder.Save(fs);
        }
        File.Move(tmpPath, CustomImagePath, overwrite: true);
        return decoded;
    }

    public static string NormalizeBingImageUrl(string? rawUrl)
    {
        if (string.IsNullOrWhiteSpace(rawUrl) || rawUrl.Length > 2048)
            throw new InvalidOperationException("invalid_bing_url");
        if (!Uri.TryCreate(rawUrl.StartsWith('/') ? "https://www.bing.com" + rawUrl : rawUrl, UriKind.Absolute, out var uri))
            throw new InvalidOperationException("invalid_bing_url");
        if (!string.Equals(uri.Scheme, "https", StringComparison.OrdinalIgnoreCase)
            || !string.Equals(uri.Host, "www.bing.com", StringComparison.OrdinalIgnoreCase)
            || !uri.IsDefaultPort
            || !string.IsNullOrEmpty(uri.UserInfo)
            || uri.AbsolutePath != "/th")
        {
            throw new InvalidOperationException("invalid_bing_url");
        }

        string? id = null;
        foreach (var part in uri.Query.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var eq = part.IndexOf('=');
            if (eq <= 0) continue;
            var k = part[..eq];
            var v = part[(eq + 1)..];
            if (k == "id") id = v;
        }
        if (string.IsNullOrEmpty(id) || !OhrIdRegex.IsMatch(id))
            throw new InvalidOperationException("invalid_bing_id");
        return "https://www.bing.com/th?id=" + id;
    }

    public static string SafeBingLink(string? rawLink)
    {
        if (!string.IsNullOrWhiteSpace(rawLink)
            && rawLink.Length <= 2048
            && Uri.TryCreate(rawLink, UriKind.Absolute, out var uri)
            && string.Equals(uri.Scheme, "https", StringComparison.OrdinalIgnoreCase)
            && string.Equals(uri.Host, "www.bing.com", StringComparison.OrdinalIgnoreCase)
            && uri.IsDefaultPort
            && string.IsNullOrEmpty(uri.UserInfo))
        {
            return uri.AbsoluteUri;
        }
        return "https://www.bing.com/";
    }

    public static BingWallpaperInfo? TryReadCachedBing()
    {
        try
        {
            if (!File.Exists(BingMetaPath) || !File.Exists(BingImagePath)) return null;
            using var doc = JsonDocument.Parse(File.ReadAllText(BingMetaPath, Encoding.UTF8));
            var root = doc.RootElement;
            var sha = root.TryGetProperty("sha256", out var s) ? s.GetString() ?? "" : "";
            var imgBytes = File.ReadAllBytes(BingImagePath);
            if (imgBytes.Length == 0 || imgBytes.Length > MaxBingImageBytes || MonitorSecurity.Sha256Hex(imgBytes) != sha)
                return null;
            var bmp = DecodeBoundedBitmap(imgBytes);
            if (bmp is null) return null;
            var day = root.TryGetProperty("day", out var d) ? d.GetString() ?? "" : "";
            var today = UsageCalendar.TodayBeijing().ToString("yyyyMMdd");
            var title = root.TryGetProperty("title", out var t) ? t.GetString() ?? "Bing 每日壁纸" : "Bing 每日壁纸";
            var copyright = root.TryGetProperty("copyright", out var c) ? c.GetString() ?? "" : "";
            var link = SafeBingLink(root.TryGetProperty("link", out var l) ? l.GetString() : null);
            return new BingWallpaperInfo(bmp, title, copyright, link, day == today ? "cached" : "offline");
        }
        catch
        {
            return null;
        }
    }

    public static async Task<BingWallpaperInfo> LoadBingWallpaperAsync(bool forceRefresh = false, CancellationToken ct = default)
    {
        var today = UsageCalendar.TodayBeijing().ToString("yyyyMMdd");
        var cached = TryReadCachedBing();
        if (!forceRefresh && cached is { State: "cached", Image: not null })
        {
            return cached;
        }

        try
        {
            var metaBytes = await FetchPublicBytesAsync(BingMetadataUrl, MaxMetadataBytes, "application/json", ct).ConfigureAwait(false);
            using var doc = JsonDocument.Parse(metaBytes);
            if (!doc.RootElement.TryGetProperty("images", out var images)
                || images.ValueKind != JsonValueKind.Array
                || images.GetArrayLength() < 1)
            {
                throw new InvalidOperationException("invalid_bing_json");
            }
            var item = images[0];
            if (item.TryGetProperty("wp", out var wpProp) && wpProp.ValueKind == JsonValueKind.False)
            {
                return cached is not null
                    ? cached with { State = "restricted" }
                    : new BingWallpaperInfo(null, "Bing 每日壁纸", "", "https://www.bing.com/", "restricted");
            }

            var rawUrl = item.TryGetProperty("url", out var uProp) ? uProp.GetString() : null;
            var imageUrl = NormalizeBingImageUrl(rawUrl);
            var title = SanitizeText(item.TryGetProperty("title", out var tProp) ? tProp.GetString() : null, 160);
            if (string.IsNullOrEmpty(title)) title = "Bing 每日壁纸";
            var copyright = SanitizeText(item.TryGetProperty("copyright", out var cProp) ? cProp.GetString() : null, 512);
            var link = SafeBingLink(item.TryGetProperty("copyrightlink", out var lProp) ? lProp.GetString() : null);

            var imgBytes = await FetchPublicBytesAsync(imageUrl, MaxBingImageBytes, "image/jpeg", ct).ConfigureAwait(false);
            var bmp = DecodeBoundedBitmap(imgBytes) ?? throw new InvalidOperationException("decode_failed");

            var tmpImg = BingImagePath + ".tmp";
            await File.WriteAllBytesAsync(tmpImg, imgBytes, ct).ConfigureAwait(false);
            File.Move(tmpImg, BingImagePath, overwrite: true);

            var metaObj = new Dictionary<string, string>
            {
                ["day"] = today,
                ["title"] = title,
                ["copyright"] = copyright,
                ["link"] = link,
                ["sha256"] = MonitorSecurity.Sha256Hex(imgBytes)
            };
            var tmpMeta = BingMetaPath + ".tmp";
            await File.WriteAllTextAsync(tmpMeta, JsonSerializer.Serialize(metaObj), Encoding.UTF8, ct).ConfigureAwait(false);
            File.Move(tmpMeta, BingMetaPath, overwrite: true);

            return new BingWallpaperInfo(bmp, title, copyright, link, "updated");
        }
        catch
        {
            return cached is not null
                ? cached with { State = "offline" }
                : new BingWallpaperInfo(null, "Bing 每日壁纸", "", "https://www.bing.com/", "unavailable");
        }
    }

    private static async Task<byte[]> FetchPublicBytesAsync(string url, int maxBytes, string accept, CancellationToken ct)
    {
        using var req = new HttpRequestMessage(HttpMethod.Get, url);
        req.Headers.TryAddWithoutValidation("Accept", accept);
        req.Headers.TryAddWithoutValidation("User-Agent", "Monitor-Wallpaper/1");
        using var resp = await PublicHttp.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
        if ((int)resp.StatusCode != 200)
            throw new InvalidOperationException("http_failed");
        await using var stream = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
        using var ms = new MemoryStream();
        var buf = new byte[16 * 1024];
        while (true)
        {
            var n = await stream.ReadAsync(buf, ct).ConfigureAwait(false);
            if (n == 0) break;
            if (ms.Length + n > maxBytes)
                throw new InvalidOperationException("too_large");
            ms.Write(buf, 0, n);
        }
        return ms.ToArray();
    }

    private static BitmapSource? DecodeBoundedBitmap(byte[] bytes)
    {
        if (bytes.Length < 8) return null;
        using var probeStream = new MemoryStream(bytes);
        var decoder = BitmapDecoder.Create(probeStream, BitmapCreateOptions.IgnoreColorProfile, BitmapCacheOption.OnLoad);
        if (decoder.Frames.Count == 0) return null;
        var frame = decoder.Frames[0];
        if (frame.PixelWidth <= 0 || frame.PixelHeight <= 0 || frame.PixelWidth > 12000 || frame.PixelHeight > 12000)
            return null;

        BitmapSource result = frame;
        var maxSide = Math.Max(frame.PixelWidth, frame.PixelHeight);
        if (maxSide > MaxDecodedEdge)
        {
            var scale = (double)MaxDecodedEdge / maxSide;
            var scaled = new TransformedBitmap(frame, new ScaleTransform(scale, scale));
            var cached = new CachedBitmap(scaled, BitmapCreateOptions.None, BitmapCacheOption.OnLoad);
            cached.Freeze();
            return cached;
        }
        if (!result.IsFrozen) result.Freeze();
        return result;
    }

    private static string SanitizeText(string? raw, int maxLen)
    {
        if (string.IsNullOrWhiteSpace(raw)) return "";
        var sb = new StringBuilder();
        foreach (var ch in raw)
        {
            if (sb.Length >= maxLen) break;
            if (char.IsControl(ch)) sb.Append(' ');
            else if (ch is (>= '\u202a' and <= '\u202e') or (>= '\u2066' and <= '\u2069')) continue;
            else sb.Append(ch);
        }
        return sb.ToString().Trim();
    }
}
