using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Effects;
using System.Windows.Media.Imaging;
using MonitorApp.Core;

namespace MonitorApp.UI;

/// <summary>
/// Monitor Family Liquid Glass Design System for Windows 11.
/// Shares exact typography (MiSans), specular Liquid Glass shaders (1:1 with Android NativeGlassNavigation),
/// precision geometry (20px card / 10px control), and Nordic Slate palette (#466987).
/// </summary>
public static class UiKit
{
    public const double CardRadius = 20.0;
    public const double ControlRadius = 10.0;

    public static readonly FontFamily AppFont = CreateMiSansFamily();
    public static readonly FontFamily MonoFont = new("Cascadia Mono, Consolas, Courier New");

    public static bool IsDark { get; private set; }
    public static string ActiveBackgroundMode { get; private set; } = "cloud";
    public static string ActiveWallpaperStyle { get; private set; } = "balanced";
    public static double ActiveWallpaperOpacity { get; private set; } = 0.48;
    public static double ActiveWallpaperBlur { get; private set; } = 10.0;
    public static BitmapSource? ActiveWallpaperImage { get; private set; }
    public static event Action? WallpaperVisualChanged;

    public static SolidColorBrush BgBrush { get; private set; } = null!;
    public static SolidColorBrush HeaderBrush { get; private set; } = null!;
    public static SolidColorBrush SidebarBrush { get; private set; } = null!;
    public static SolidColorBrush SurfaceBrush { get; private set; } = null!;
    public static SolidColorBrush SoftBrush { get; private set; } = null!;
    public static SolidColorBrush HoverBrush { get; private set; } = null!;
    public static SolidColorBrush LineBrush { get; private set; } = null!;
    public static SolidColorBrush TextBrush { get; private set; } = null!;
    public static SolidColorBrush MutedBrush { get; private set; } = null!;
    public static SolidColorBrush AccentBrush { get; private set; } = null!;
    public static SolidColorBrush AccentHoverBrush { get; private set; } = null!;
    public static SolidColorBrush OnAccentBrush { get; private set; } = null!;
    public static SolidColorBrush ClaudeInkBrush { get; private set; } = null!;
    public static SolidColorBrush GreenBrush { get; private set; } = null!;
    public static SolidColorBrush WarningBrush { get; private set; } = null!;
    public static SolidColorBrush DangerBrush { get; private set; } = null!;

    // Liquid Glass Optical Brushes (matching Android NativeGlassNavigation & compactHeaderMaterial)
    public static Brush CanvasBrush { get; private set; } = null!;
    public static Brush MicaCanvasBrush { get; private set; } = null!;
    public static Brush GlassHeaderBrush { get; private set; } = null!;
    public static Brush GlassRailBrush { get; private set; } = null!;
    public static Brush GlassCardBrush { get; private set; } = null!;
    public static Brush GlassCardHoverBrush { get; private set; } = null!;
    public static Brush GlassSelectedCardBrush { get; private set; } = null!;
    public static Brush GlassRimBrush { get; private set; } = null!;
    public static Brush GlassSubtleRimBrush { get; private set; } = null!;
    public static Brush GlassTrackBrush { get; private set; } = null!;
    public static Brush GlassTrayBrush { get; private set; } = null!;
    public static Brush GlassTrayRimBrush { get; private set; } = null!;
    public static Brush GlassButtonBrush { get; private set; } = null!;
    public static Brush GlassButtonHoverBrush { get; private set; } = null!;
    public static Brush GlassInputBrush { get; private set; } = null!;
    public static Brush TopSpecularSheenBrush { get; private set; } = null!;
    public static Brush SplitDividerBrush { get; private set; } = null!;
    public static Brush AmbientGlowPrimaryBrush { get; private set; } = null!;
    public static Brush AmbientGlowSecondaryBrush { get; private set; } = null!;
    public static Brush AmbientGlowCenterBrush { get; private set; } = null!;

    // Compatibility aliases
    public static SolidColorBrush MutedBgBrush => SoftBrush;
    public static SolidColorBrush OrangeBrush => WarningBrush;
    public static SolidColorBrush RedBrush => DangerBrush;

    static UiKit()
    {
        ApplyTheme(false);
    }

    private static FontFamily CreateMiSansFamily()
    {
        try
        {
            return new FontFamily(
                new Uri("pack://application:,,,/Monitor;component/Resources/"),
                "./#MiSans, Microsoft YaHei UI, Segoe UI");
        }
        catch
        {
            return new FontFamily("MiSans, Microsoft YaHei UI, Segoe UI");
        }
    }

    public static void SetWallpaperVisualLive(double opacity, double blur)
    {
        ActiveWallpaperOpacity = Math.Clamp(opacity, 0.05, 0.95);
        ActiveWallpaperBlur = Math.Clamp(blur, 0.0, 32.0);
        WallpaperVisualChanged?.Invoke();
    }

    public static byte ComputeWallpaperVeilAlpha(double opacity, bool dark)
    {
        var clamped = Math.Clamp(opacity, 0.05, 0.95);
        // Smooth optical curve: 5% -> ~93% veil, 50% -> ~50% veil, 95% -> ~7% veil
        var veilRatio = Math.Clamp(1.0 - clamped * (dark ? 0.96 : 0.97), 0.05, 0.95);
        return (byte)Math.Round(veilRatio * 255.0);
    }

    public static void ApplyTheme(
        bool dark,
        string backgroundMode = "cloud",
        string wallpaperStyle = "balanced",
        BitmapSource? wallpaper = null,
        double? wallpaperOpacity = null,
        double? wallpaperBlur = null)
    {
        IsDark = dark;
        ActiveBackgroundMode = backgroundMode ?? "cloud";
        ActiveWallpaperStyle = wallpaperStyle ?? "balanced";
        ActiveWallpaperImage = wallpaper;

        var defaults = LocalUiSettings.PresetValues(ActiveWallpaperStyle);
        ActiveWallpaperOpacity = Math.Clamp(wallpaperOpacity ?? defaults.Opacity, 0.05, 0.95);
        ActiveWallpaperBlur = Math.Clamp(wallpaperBlur ?? defaults.Blur, 0.0, 32.0);

        var hasWp = wallpaper is not null && ActiveBackgroundMode is "bing" or "custom";
        // When wallpaper shines through strongly, make cards genuinely translucent crystal while keeping high text contrast
        var crystalFactor = hasWp ? (1.0 - Math.Clamp((ActiveWallpaperOpacity - 0.20) * 0.30, 0.0, 0.24)) : 1.0;
        byte ScaleAlpha(byte baseAlpha) => (byte)Math.Clamp(Math.Round(baseAlpha * crystalFactor), 0x8A, 0xFC);

        var (cTop, cMid, cBot) = (dark, ActiveBackgroundMode) switch
        {
            (true, "sand") => (Color.FromRgb(0x19, 0x16, 0x14), Color.FromRgb(0x20, 0x1C, 0x19), Color.FromRgb(0x16, 0x13, 0x11)),
            (true, "mist") => (Color.FromRgb(0x11, 0x1A, 0x17), Color.FromRgb(0x16, 0x21, 0x1E), Color.FromRgb(0x0F, 0x17, 0x15)),
            (true, "blue") => (Color.FromRgb(0x0F, 0x18, 0x24), Color.FromRgb(0x14, 0x20, 0x2F), Color.FromRgb(0x0E, 0x16, 0x22)),
            (true, _) => (Color.FromRgb(0x10, 0x17, 0x1F), Color.FromRgb(0x15, 0x1E, 0x28), Color.FromRgb(0x11, 0x19, 0x23)),
            (false, "sand") => (Color.FromRgb(0xF5, 0xEF, 0xE5), Color.FromRgb(0xFA, 0xF7, 0xF1), Color.FromRgb(0xF0, 0xE8, 0xDC)),
            (false, "mist") => (Color.FromRgb(0xE6, 0xF1, 0xEB), Color.FromRgb(0xF2, 0xF8, 0xF4), Color.FromRgb(0xE1, 0xED, 0xE7)),
            (false, "blue") => (Color.FromRgb(0xE0, 0xEC, 0xF8), Color.FromRgb(0xEE, 0xF5, 0xFC), Color.FromRgb(0xDB, 0xE8, 0xF6)),
            (false, _) => (Color.FromRgb(0xE8, 0xEF, 0xF7), Color.FromRgb(0xF3, 0xF7, 0xFC), Color.FromRgb(0xE4, 0xED, 0xF6))
        };

        if (dark)
        {
            BgBrush = new SolidColorBrush(cMid);
            BgBrush.Freeze();
            HeaderBrush = Hex(0x17, 0x1F, 0x28);
            SidebarBrush = Hex(0x1A, 0x23, 0x2D);
            SurfaceBrush = Hex(0x20, 0x2A, 0x35);
            SoftBrush = Hex(0x2A, 0x37, 0x45);
            HoverBrush = HexA(0xB8, 0x2C, 0x3B, 0x4B);
            LineBrush = HexA(0x78, 0x3D, 0x50, 0x63);
            TextBrush = Hex(0xF3, 0xF6, 0xFA);
            MutedBrush = Hex(0x9E, 0xAD, 0xBC);
            AccentBrush = Hex(0xAF, 0xC8, 0xDF);
            AccentHoverBrush = Hex(0xC2, 0xD7, 0xEB);
            OnAccentBrush = Hex(0x13, 0x1A, 0x22);
            ClaudeInkBrush = Hex(0xCD, 0xA9, 0x91);
            GreenBrush = Hex(0x6C, 0xCB, 0x98);
            WarningBrush = Hex(0xE3, 0xBF, 0x86);
            DangerBrush = Hex(0xED, 0xAB, 0xAB);

            CanvasBrush = DiagonalGradient((0.0, cTop), (0.5, cMid), (1.0, cBot));
            MicaCanvasBrush = DiagonalGradient(
                (0.0, Color.FromArgb(0xC6, cTop.R, cTop.G, cTop.B)),
                (0.5, Color.FromArgb(0xD6, cMid.R, cMid.G, cMid.B)),
                (1.0, Color.FromArgb(0xC6, cBot.R, cBot.G, cBot.B)));
            GlassHeaderBrush = VerticalGradient(
                Color.FromArgb(hasWp ? (byte)0x58 : (byte)0x78, 0x18, 0x21, 0x2B),
                Color.FromArgb(0x08, 0x16, 0x1F, 0x28));
            GlassRailBrush = VerticalGradient(
                Color.FromArgb(ScaleAlpha(0xCC), 0x26, 0x34, 0x43),
                Color.FromArgb(ScaleAlpha(0xDA), 0x1A, 0x24, 0x2F));
            GlassCardBrush = VerticalGradient(
                (0.0, Color.FromArgb(ScaleAlpha(0xDB), 0x2A, 0x38, 0x47)),
                (0.52, Color.FromArgb(ScaleAlpha(0xC6), 0x20, 0x2B, 0x37)),
                (1.0, Color.FromArgb(ScaleAlpha(0xD2), 0x1C, 0x26, 0x31)));
            GlassCardHoverBrush = VerticalGradient(
                Color.FromArgb(ScaleAlpha(0xEB), 0x31, 0x42, 0x54),
                Color.FromArgb(ScaleAlpha(0xDA), 0x24, 0x31, 0x3F));
            GlassSelectedCardBrush = VerticalGradient(
                Color.FromArgb(ScaleAlpha(0xEE), 0x34, 0x48, 0x5C),
                Color.FromArgb(ScaleAlpha(0xDC), 0x27, 0x37, 0x48));
            GlassRimBrush = DiagonalGradient(
                (0.0, Color.FromArgb(0x88, 0x9E, 0xB6, 0xCE)),
                (0.48, Color.FromArgb(0x28, 0x54, 0x68, 0x7B)),
                (1.0, Color.FromArgb(0x56, 0x70, 0x85, 0x99)));
            GlassSubtleRimBrush = DiagonalGradient(
                (0.0, Color.FromArgb(0x5A, 0x8A, 0xA2, 0xBA)),
                (1.0, Color.FromArgb(0x28, 0x4B, 0x5E, 0x70)));
            GlassTrackBrush = VerticalGradient(
                Color.FromArgb(ScaleAlpha(0xA6), 0x15, 0x1E, 0x27),
                Color.FromArgb(ScaleAlpha(0xBA), 0x1B, 0x25, 0x30));
            GlassTrayBrush = VerticalGradient(
                Color.FromArgb(0xEA, 0x4E, 0x65, 0x7C),
                Color.FromArgb(0xD2, 0x38, 0x4A, 0x5D));
            GlassTrayRimBrush = HexA(0x84, 0x9A, 0xB3, 0xCC);
            GlassButtonBrush = VerticalGradient(
                Color.FromArgb(ScaleAlpha(0xD2), 0x2D, 0x3B, 0x4B),
                Color.FromArgb(ScaleAlpha(0xBC), 0x23, 0x2F, 0x3C));
            GlassButtonHoverBrush = VerticalGradient(
                Color.FromArgb(0xEA, 0x38, 0x4A, 0x5D),
                Color.FromArgb(0xD8, 0x2C, 0x3B, 0x4B));
            GlassInputBrush = HexA(ScaleAlpha(0xCA), 0x18, 0x21, 0x2A);
            TopSpecularSheenBrush = HorizontalGradient(
                (0.0, Color.FromArgb(0x00, 0xFF, 0xFF, 0xFF)),
                (0.25, Color.FromArgb(0x2A, 0xD8, 0xEA, 0xFC)),
                (0.5, Color.FromArgb(0x3C, 0xEE, 0xF6, 0xFF)),
                (0.75, Color.FromArgb(0x2A, 0xD8, 0xEA, 0xFC)),
                (1.0, Color.FromArgb(0x00, 0xFF, 0xFF, 0xFF)));
            SplitDividerBrush = VerticalGradient(
                (0.0, Color.FromArgb(0x00, 0x84, 0x9B, 0xB2)),
                (0.18, Color.FromArgb(0x46, 0x84, 0x9B, 0xB2)),
                (0.82, Color.FromArgb(0x46, 0x84, 0x9B, 0xB2)),
                (1.0, Color.FromArgb(0x00, 0x84, 0x9B, 0xB2)));
            AmbientGlowPrimaryBrush = RadialGlow(Color.FromArgb(0x40, 0x4B, 0x7B, 0xAA));
            AmbientGlowSecondaryBrush = RadialGlow(Color.FromArgb(0x2A, 0x94, 0x6F, 0x57));
            AmbientGlowCenterBrush = RadialGlow(Color.FromArgb(0x1E, 0x62, 0x8D, 0xB4));

            UpdateDynamicResources(
                scrollIdle: Color.FromArgb(0x40, 0xA9, 0xB8, 0xC6),
                scrollHover: Color.FromArgb(0x8C, 0xAF, 0xC8, 0xDF),
                accent: Color.FromRgb(0xAF, 0xC8, 0xDF),
                onAccent: Color.FromRgb(0x13, 0x1A, 0x22),
                ctrlBg: Color.FromArgb(0xC8, 0x1B, 0x25, 0x30),
                ctrlBorder: Color.FromArgb(0x98, 0x5B, 0x70, 0x85),
                ctrlHover: Color.FromArgb(0xE0, 0x27, 0x35, 0x44),
                tipBg: Color.FromArgb(0xF4, 0x1A, 0x24, 0x2E),
                tipBorder: Color.FromArgb(0x66, 0xAF, 0xC8, 0xDF),
                tipText: Color.FromRgb(0xF3, 0xF6, 0xFA));
        }
        else
        {
            BgBrush = new SolidColorBrush(cMid);
            BgBrush.Freeze();
            HeaderBrush = Hex(0xF6, 0xF9, 0xFC);
            SidebarBrush = Hex(0xEA, 0xF0, 0xF6);
            SurfaceBrush = Hex(0xFF, 0xFF, 0xFF);
            SoftBrush = Hex(0xE3, 0xEC, 0xF4);
            HoverBrush = HexA(0xC8, 0xE6, 0xEF, 0xF7);
            LineBrush = HexA(0x98, 0xCF, 0xDC, 0xE8);
            TextBrush = Hex(0x1C, 0x25, 0x2E);
            MutedBrush = Hex(0x5A, 0x68, 0x76);
            AccentBrush = Hex(0x46, 0x69, 0x87);
            AccentHoverBrush = Hex(0x37, 0x59, 0x74);
            OnAccentBrush = Hex(0xFF, 0xFF, 0xFF);
            ClaudeInkBrush = Hex(0x96, 0x66, 0x4F);
            GreenBrush = Hex(0x2B, 0x7A, 0x57);
            WarningBrush = Hex(0x8A, 0x60, 0x1E);
            DangerBrush = Hex(0xA4, 0x43, 0x43);

            CanvasBrush = DiagonalGradient((0.0, cTop), (0.48, cMid), (1.0, cBot));
            MicaCanvasBrush = DiagonalGradient(
                (0.0, Color.FromArgb(0xCA, cTop.R, cTop.G, cTop.B)),
                (0.5, Color.FromArgb(0xD8, cMid.R, cMid.G, cMid.B)),
                (1.0, Color.FromArgb(0xCA, cBot.R, cBot.G, cBot.B)));
            GlassHeaderBrush = VerticalGradient(
                Color.FromArgb(hasWp ? (byte)0x5C : (byte)0x84, 0xFF, 0xFF, 0xFF),
                Color.FromArgb(0x06, 0xF1, 0xF6, 0xFB));
            GlassRailBrush = VerticalGradient(
                Color.FromArgb(ScaleAlpha(0xCE), 0xFF, 0xFF, 0xFF),
                Color.FromArgb(ScaleAlpha(0xB8), 0xEE, 0xF4, 0xFA));
            GlassCardBrush = VerticalGradient(
                (0.0, Color.FromArgb(ScaleAlpha(0xEC), 0xFF, 0xFF, 0xFF)),
                (0.55, Color.FromArgb(ScaleAlpha(0xD4), 0xF9, 0xFC, 0xFF)),
                (1.0, Color.FromArgb(ScaleAlpha(0xDF), 0xF1, 0xF6, 0xFC)));
            GlassCardHoverBrush = VerticalGradient(
                Color.FromArgb(ScaleAlpha(0xF8), 0xFF, 0xFF, 0xFF),
                Color.FromArgb(ScaleAlpha(0xEA), 0xF3, 0xF8, 0xFD));
            GlassSelectedCardBrush = VerticalGradient(
                Color.FromArgb(ScaleAlpha(0xF6), 0xE7, 0xF0, 0xF9),
                Color.FromArgb(ScaleAlpha(0xE8), 0xDB, 0xE8, 0xF4));
            GlassRimBrush = DiagonalGradient(
                (0.0, Color.FromArgb(0xFC, 0xFF, 0xFF, 0xFF)),
                (0.5, Color.FromArgb(0x80, 0xFF, 0xFF, 0xFF)),
                (1.0, Color.FromArgb(0xBC, 0xC4, 0xD4, 0xE4)));
            GlassSubtleRimBrush = DiagonalGradient(
                (0.0, Color.FromArgb(0xEE, 0xFF, 0xFF, 0xFF)),
                (1.0, Color.FromArgb(0x9E, 0xCA, 0xD8, 0xE6)));
            GlassTrackBrush = VerticalGradient(
                Color.FromArgb(ScaleAlpha(0x9C), 0xDB, 0xE5, 0xEF),
                Color.FromArgb(ScaleAlpha(0x86), 0xE6, 0xEE, 0xF7));
            GlassTrayBrush = VerticalGradient(
                Color.FromArgb(0xFC, 0xFF, 0xFF, 0xFF),
                Color.FromArgb(0xE6, 0xE5, 0xEE, 0xF7));
            GlassTrayRimBrush = HexA(0xF6, 0xFF, 0xFF, 0xFF);
            GlassButtonBrush = VerticalGradient(
                Color.FromArgb(ScaleAlpha(0xE2), 0xFF, 0xFF, 0xFF),
                Color.FromArgb(ScaleAlpha(0xC8), 0xE8, 0xF0, 0xF8));
            GlassButtonHoverBrush = VerticalGradient(
                Color.FromArgb(0xF8, 0xFF, 0xFF, 0xFF),
                Color.FromArgb(0xE2, 0xDC, 0xE8, 0xF4));
            GlassInputBrush = HexA(ScaleAlpha(0xDC), 0xFF, 0xFF, 0xFF);
            TopSpecularSheenBrush = HorizontalGradient(
                (0.0, Color.FromArgb(0x00, 0xFF, 0xFF, 0xFF)),
                (0.22, Color.FromArgb(0x96, 0xFF, 0xFF, 0xFF)),
                (0.5, Color.FromArgb(0xD8, 0xFF, 0xFF, 0xFF)),
                (0.78, Color.FromArgb(0x96, 0xFF, 0xFF, 0xFF)),
                (1.0, Color.FromArgb(0x00, 0xFF, 0xFF, 0xFF)));
            SplitDividerBrush = VerticalGradient(
                (0.0, Color.FromArgb(0x00, 0xBD, 0xCC, 0xDC)),
                (0.16, Color.FromArgb(0x88, 0xBD, 0xCC, 0xDC)),
                (0.84, Color.FromArgb(0x88, 0xBD, 0xCC, 0xDC)),
                (1.0, Color.FromArgb(0x00, 0xBD, 0xCC, 0xDC)));
            AmbientGlowPrimaryBrush = RadialGlow(Color.FromArgb(0x36, 0x67, 0x98, 0xC6));
            AmbientGlowSecondaryBrush = RadialGlow(Color.FromArgb(0x26, 0xCA, 0xAA, 0x8F));
            AmbientGlowCenterBrush = RadialGlow(Color.FromArgb(0x20, 0x88, 0xB2, 0xD8));

            UpdateDynamicResources(
                scrollIdle: Color.FromArgb(0x36, 0x5C, 0x69, 0x77),
                scrollHover: Color.FromArgb(0x7E, 0x46, 0x69, 0x87),
                accent: Color.FromRgb(0x46, 0x69, 0x87),
                onAccent: Color.FromRgb(0xFF, 0xFF, 0xFF),
                ctrlBg: Color.FromArgb(0xD8, 0xFF, 0xFF, 0xFF),
                ctrlBorder: Color.FromArgb(0xCC, 0xB6, 0xC5, 0xD4),
                ctrlHover: Color.FromArgb(0xF2, 0xEC, 0xF3, 0xF9),
                tipBg: Color.FromArgb(0xF2, 0x1E, 0x27, 0x30),
                tipBorder: Color.FromArgb(0x48, 0xAF, 0xC8, 0xDF),
                tipText: Color.FromRgb(0xF5, 0xF8, 0xFC));
        }
    }

    private static void UpdateDynamicResources(
        Color scrollIdle,
        Color scrollHover,
        Color accent,
        Color onAccent,
        Color ctrlBg,
        Color ctrlBorder,
        Color ctrlHover,
        Color tipBg,
        Color tipBorder,
        Color tipText)
    {
        try
        {
            if (Application.Current?.Resources is { } res)
            {
                void SetBrush(string key, Color c)
                {
                    var b = new SolidColorBrush(c);
                    b.Freeze();
                    res[key] = b;
                }
                SetBrush("MonitorScrollThumbIdleBrush", scrollIdle);
                SetBrush("MonitorScrollThumbHoverBrush", scrollHover);
                SetBrush("MonitorAccentBrush", accent);
                SetBrush("MonitorOnAccentBrush", onAccent);
                SetBrush("MonitorControlBgBrush", ctrlBg);
                SetBrush("MonitorControlBorderBrush", ctrlBorder);
                SetBrush("MonitorControlHoverBrush", ctrlHover);
                SetBrush("MonitorTooltipBgBrush", tipBg);
                SetBrush("MonitorTooltipBorderBrush", tipBorder);
                SetBrush("MonitorTooltipTextBrush", tipText);
            }
        }
        catch { }
    }

    private static SolidColorBrush Hex(byte r, byte g, byte b)
    {
        var brush = new SolidColorBrush(Color.FromRgb(r, g, b));
        brush.Freeze();
        return brush;
    }

    private static SolidColorBrush HexA(byte a, byte r, byte g, byte b)
    {
        var brush = new SolidColorBrush(Color.FromArgb(a, r, g, b));
        brush.Freeze();
        return brush;
    }

    private static LinearGradientBrush VerticalGradient(Color top, Color bottom)
    {
        var brush = new LinearGradientBrush(top, bottom, new Point(0, 0), new Point(0, 1));
        brush.Freeze();
        return brush;
    }

    private static LinearGradientBrush VerticalGradient(params (double Offset, Color Color)[] stops)
    {
        var brush = new LinearGradientBrush { StartPoint = new Point(0, 0), EndPoint = new Point(0, 1) };
        foreach (var (offset, color) in stops)
            brush.GradientStops.Add(new GradientStop(color, offset));
        brush.Freeze();
        return brush;
    }

    private static LinearGradientBrush HorizontalGradient(params (double Offset, Color Color)[] stops)
    {
        var brush = new LinearGradientBrush { StartPoint = new Point(0, 0), EndPoint = new Point(1, 0) };
        foreach (var (offset, color) in stops)
            brush.GradientStops.Add(new GradientStop(color, offset));
        brush.Freeze();
        return brush;
    }

    private static LinearGradientBrush DiagonalGradient(params (double Offset, Color Color)[] stops)
    {
        var brush = new LinearGradientBrush { StartPoint = new Point(0, 0), EndPoint = new Point(1, 1) };
        foreach (var (offset, color) in stops)
            brush.GradientStops.Add(new GradientStop(color, offset));
        brush.Freeze();
        return brush;
    }

    private static RadialGradientBrush RadialGlow(Color center)
    {
        var brush = new RadialGradientBrush
        {
            Center = new Point(0.5, 0.5),
            GradientOrigin = new Point(0.5, 0.5),
            RadiusX = 0.5,
            RadiusY = 0.5
        };
        brush.GradientStops.Add(new GradientStop(center, 0.0));
        brush.GradientStops.Add(new GradientStop(Color.FromArgb(0, center.R, center.G, center.B), 1.0));
        brush.Freeze();
        return brush;
    }

    public static void PopulateBackdrop(Grid backdropLayer, bool compact = false)
    {
        backdropLayer.Children.Clear();

        if (ActiveWallpaperImage is not null && ActiveBackgroundMode is "bing" or "custom")
        {
            var blurRadius = ActiveWallpaperBlur;
            var blurEffect = new BlurEffect { Radius = blurRadius, RenderingBias = RenderingBias.Performance };
            var img = new Image
            {
                Source = ActiveWallpaperImage,
                Stretch = Stretch.UniformToFill,
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
                Margin = blurRadius > 0.2 ? new Thickness(-blurRadius * 1.25) : new Thickness(0),
                Effect = blurRadius > 0.2 ? blurEffect : null
            };
            RenderOptions.SetBitmapScalingMode(img, BitmapScalingMode.HighQuality);
            backdropLayer.Children.Add(img);

            var baseCol = BgBrush.Color;
            var veilAlpha = ComputeWallpaperVeilAlpha(ActiveWallpaperOpacity, IsDark);
            var veilBorder = new Border
            {
                Background = HexA(veilAlpha, baseCol.R, baseCol.G, baseCol.B)
            };
            backdropLayer.Children.Add(veilBorder);

            void OnLiveWallpaperVisualChanged()
            {
                var nextBlur = ActiveWallpaperBlur;
                if (nextBlur > 0.2)
                {
                    blurEffect.Radius = nextBlur;
                    if (img.Effect is null) img.Effect = blurEffect;
                    img.Margin = new Thickness(-nextBlur * 1.25);
                }
                else
                {
                    img.Effect = null;
                    img.Margin = new Thickness(0);
                }
                var c = BgBrush.Color;
                var a = ComputeWallpaperVeilAlpha(ActiveWallpaperOpacity, IsDark);
                veilBorder.Background = HexA(a, c.R, c.G, c.B);
            }

            WallpaperVisualChanged += OnLiveWallpaperVisualChanged;
            img.Unloaded += (_, _) => WallpaperVisualChanged -= OnLiveWallpaperVisualChanged;
        }
        else
        {
            var orb1 = new Border
            {
                Width = compact ? 280 : 620,
                Height = compact ? 240 : 480,
                HorizontalAlignment = HorizontalAlignment.Left,
                VerticalAlignment = VerticalAlignment.Top,
                Margin = compact ? new Thickness(-80, -70, 0, 0) : new Thickness(-140, -120, 0, 0),
                Background = AmbientGlowPrimaryBrush
            };
            var orb2 = new Border
            {
                Width = compact ? 260 : 580,
                Height = compact ? 240 : 460,
                HorizontalAlignment = HorizontalAlignment.Right,
                VerticalAlignment = VerticalAlignment.Bottom,
                Margin = compact ? new Thickness(0, 0, -80, -70) : new Thickness(0, 0, -130, -110),
                Background = AmbientGlowSecondaryBrush
            };
            backdropLayer.Children.Add(orb1);
            backdropLayer.Children.Add(orb2);
            if (!compact)
            {
                var orb3 = new Border
                {
                    Width = 540,
                    Height = 380,
                    HorizontalAlignment = HorizontalAlignment.Center,
                    VerticalAlignment = VerticalAlignment.Top,
                    Margin = new Thickness(120, -80, 0, 0),
                    Background = AmbientGlowCenterBrush
                };
                backdropLayer.Children.Add(orb3);
            }
        }
    }

    /// <summary>
    /// Wraps a layout root in an atmospheric Liquid Glass light field (or Custom / Bing Daily Wallpaper with stepless live opacity &amp; blur)
    /// while keeping foreground ClearType text 100% vector-sharp.
    /// </summary>
    public static Grid AmbientStage(UIElement content, bool compact = false)
    {
        var stage = new Grid();
        var backdropLayer = new Grid { IsHitTestVisible = false, ClipToBounds = true };
        PopulateBackdrop(backdropLayer, compact);
        stage.Children.Add(backdropLayer);
        stage.Children.Add(content);
        return stage;
    }

    private static Grid WrapWithTopSpecular(UIElement child, double padding, double radius)
    {
        var innerGrid = new Grid();
        innerGrid.Children.Add(child);
        var sheenInset = Math.Max(6, radius * 0.55);
        innerGrid.Children.Add(new Border
        {
            Height = 1,
            VerticalAlignment = VerticalAlignment.Top,
            Margin = new Thickness(sheenInset - padding, -padding, sheenInset - padding, 0),
            Background = TopSpecularSheenBrush,
            IsHitTestVisible = false
        });
        return innerGrid;
    }

    /// <summary>
    /// Wraps a Liquid Glass card/rail with a sibling GPU-cached ambient shadow layer + top specular rim so text inside stays 100% ClearType crisp.
    /// </summary>
    public static Grid ElevatedGlass(UIElement child, double padding = 20, double radius = CardRadius, Brush? fill = null, Brush? rim = null)
    {
        var wrapper = new Grid();
        var shadowCaster = new Border
        {
            IsHitTestVisible = false,
            CacheMode = new BitmapCache(),
            CornerRadius = new CornerRadius(radius),
            Background = IsDark ? HexA(0x58, 0x08, 0x0E, 0x14) : HexA(0x38, 0xD4, 0xE0, 0xEC),
            Margin = new Thickness(1.5),
            Effect = new DropShadowEffect
            {
                Color = IsDark ? Colors.Black : Color.FromRgb(0x16, 0x32, 0x4E),
                BlurRadius = 26,
                ShadowDepth = 6,
                Direction = 270,
                Opacity = IsDark ? 0.30 : 0.085
            }
        };
        var surface = new Border
        {
            Background = fill ?? GlassCardBrush,
            BorderBrush = rim ?? GlassRimBrush,
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(radius),
            Padding = new Thickness(padding),
            SnapsToDevicePixels = true,
            Child = WrapWithTopSpecular(child, padding, radius)
        };
        wrapper.Children.Add(shadowCaster);
        wrapper.Children.Add(surface);
        return wrapper;
    }

    public static Image BrandImage(string? toolOrIcon, double size = 30)
    {
        var file = toolOrIcon switch
        {
            "claude" => "Claude.png",
            "codex" => "Codex.png",
            "antigravity" => "Antigravity.png",
            _ => "AppIcon.png"
        };
        var img = new Image
        {
            Width = size,
            Height = size,
            Stretch = Stretch.Uniform
        };
        RenderOptions.SetBitmapScalingMode(img, BitmapScalingMode.HighQuality);
        try
        {
            img.Source = new BitmapImage(new Uri($"pack://application:,,,/Resources/{file}", UriKind.Absolute));
        }
        catch { }
        return img;
    }

    /// <summary>
    /// Family tool identity badge matching NativeUi.taskIdentity (icon + bold brand + muted computer).
    /// </summary>
    public static StackPanel TaskIdentityBadge(string tool, string? computerName = null, double iconSize = 16)
    {
        var row = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
        var icon = BrandImage(tool, iconSize);
        icon.Margin = new Thickness(0, 0, 6, 0);
        row.Children.Add(icon);

        var brandText = new TextBlock
        {
            Text = MonitorTask.BrandFor(tool),
            FontFamily = AppFont,
            FontSize = 12,
            FontWeight = FontWeights.SemiBold,
            Foreground = tool == "claude" ? ClaudeInkBrush : AccentBrush,
            VerticalAlignment = VerticalAlignment.Center
        };
        row.Children.Add(brandText);

        if (!string.IsNullOrWhiteSpace(computerName))
        {
            row.Children.Add(new TextBlock
            {
                Text = " · " + computerName!.Trim(),
                FontFamily = AppFont,
                FontSize = 12,
                Foreground = MutedBrush,
                VerticalAlignment = VerticalAlignment.Center
            });
        }
        return row;
    }

    public static Image AvatarImage(string? dataUrl, double size = 56)
    {
        var border = new Image
        {
            Width = size,
            Height = size,
            Stretch = Stretch.UniformToFill,
            Clip = new EllipseGeometry(new Point(size / 2, size / 2), size / 2, size / 2)
        };
        RenderOptions.SetBitmapScalingMode(border, BitmapScalingMode.HighQuality);
        if (!string.IsNullOrWhiteSpace(dataUrl) && dataUrl.StartsWith("data:image/", StringComparison.OrdinalIgnoreCase))
        {
            var comma = dataUrl.IndexOf(',');
            if (comma > 0)
            {
                try
                {
                    var bytes = Convert.FromBase64String(dataUrl[(comma + 1)..]);
                    if (bytes.Length <= 1024 * 1024)
                    {
                        using var ms = new MemoryStream(bytes);
                        var bmp = new BitmapImage();
                        bmp.BeginInit();
                        bmp.CacheOption = BitmapCacheOption.OnLoad;
                        bmp.StreamSource = ms;
                        bmp.EndInit();
                        bmp.Freeze();
                        border.Source = bmp;
                        return border;
                    }
                }
                catch { }
            }
        }
        try
        {
            border.Source = new BitmapImage(new Uri("pack://application:,,,/Resources/AppIcon.png", UriKind.Absolute));
        }
        catch { }
        return border;
    }

    public static Border Card(UIElement child, double padding = 20, double radius = CardRadius)
    {
        return new Border
        {
            Background = GlassCardBrush,
            BorderBrush = GlassRimBrush,
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(radius),
            Padding = new Thickness(padding),
            SnapsToDevicePixels = true,
            Child = WrapWithTopSpecular(child, padding, radius)
        };
    }

    public static TextBlock Heading(string text, double size = 18) => new()
    {
        Text = text,
        FontFamily = AppFont,
        FontSize = size,
        FontWeight = FontWeights.SemiBold,
        Foreground = TextBrush,
        TextWrapping = TextWrapping.Wrap
    };

    public static TextBlock Body(string text, double size = 13.5, Brush? color = null) => new()
    {
        Text = text,
        FontFamily = AppFont,
        FontSize = size,
        Foreground = color ?? TextBrush,
        TextWrapping = TextWrapping.Wrap,
        LineHeight = size * 1.42
    };

    public static Button PrimaryButton(string label, Action onClick, bool enabled = true, string? glyph = null)
    {
        var content = ButtonLabelRow(label, OnAccentBrush, FontWeights.Medium, glyph);
        var bg = AccentBrush;
        var hover = AccentHoverBrush;
        var border = new Border
        {
            Background = enabled ? bg : SoftBrush,
            BorderBrush = enabled ? GlassSubtleRimBrush : Brushes.Transparent,
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(ControlRadius),
            Padding = new Thickness(15, 8.5, 15, 8.5),
            SnapsToDevicePixels = true,
            Child = content,
            Opacity = enabled ? 1.0 : 0.55
        };
        var btn = BareButton(border, onClick, enabled);
        if (enabled)
        {
            btn.MouseEnter += (_, _) => border.Background = hover;
            btn.MouseLeave += (_, _) => border.Background = bg;
        }
        return btn;
    }

    /// <summary>
    /// Precision-milled Liquid Glass secondary button (10px radius, specular edge, zero puffy oval shape).
    /// </summary>
    public static Button SecondaryButton(string label, Action onClick, bool enabled = true, Brush? textBrush = null, string? glyph = null)
    {
        var fg = textBrush ?? AccentBrush;
        var content = ButtonLabelRow(label, fg, FontWeights.Medium, glyph);
        var bg = GlassButtonBrush;
        var hover = GlassButtonHoverBrush;
        var border = new Border
        {
            Background = bg,
            BorderBrush = GlassSubtleRimBrush,
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(ControlRadius),
            Padding = new Thickness(13, 7.5, 13, 7.5),
            SnapsToDevicePixels = true,
            Child = content,
            Opacity = enabled ? 1.0 : 0.5
        };
        var btn = BareButton(border, onClick, enabled);
        if (enabled)
        {
            btn.MouseEnter += (_, _) => border.Background = hover;
            btn.MouseLeave += (_, _) => border.Background = bg;
        }
        return btn;
    }

    public static Button IconButton(string glyph, string tooltip, Action onClick, bool selected = false, bool enabled = true, double boxSize = 32, double iconSize = 16.5)
    {
        var ink = selected ? AccentBrush : MutedBrush;
        var icon = new MonitorIcon(glyph, ink, iconSize);
        var bg = selected ? GlassTrayBrush : Brushes.Transparent;
        var rim = selected ? GlassTrayRimBrush : Brushes.Transparent;
        var hover = GlassButtonHoverBrush;
        var border = new Border
        {
            Width = boxSize,
            Height = boxSize,
            CornerRadius = new CornerRadius(Math.Round(boxSize * 0.28)),
            Background = bg,
            BorderBrush = rim,
            BorderThickness = new Thickness(1),
            SnapsToDevicePixels = true,
            Child = icon,
            Opacity = enabled ? 1.0 : 0.4
        };
        var btn = BareButton(border, onClick, enabled);
        btn.ToolTip = tooltip;
        if (enabled)
        {
            btn.MouseEnter += (_, _) =>
            {
                border.Background = selected ? GlassTrayBrush : hover;
                border.BorderBrush = GlassSubtleRimBrush;
                if (!selected)
                {
                    icon.Ink = TextBrush;
                    icon.InvalidateVisual();
                }
            };
            btn.MouseLeave += (_, _) =>
            {
                border.Background = bg;
                border.BorderBrush = rim;
                if (!selected)
                {
                    icon.Ink = ink;
                    icon.InvalidateVisual();
                }
            };
        }
        return btn;
    }

    private static UIElement ButtonLabelRow(string label, Brush fg, FontWeight weight, string? glyph)
    {
        var txt = new TextBlock
        {
            Text = label,
            FontFamily = AppFont,
            FontSize = 13,
            FontWeight = weight,
            Foreground = fg,
            VerticalAlignment = VerticalAlignment.Center
        };
        if (string.IsNullOrEmpty(glyph))
        {
            txt.HorizontalAlignment = HorizontalAlignment.Center;
            return txt;
        }
        var row = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center
        };
        var icon = new MonitorIcon(glyph, fg, 15) { Margin = new Thickness(0, 0, 6, 0) };
        row.Children.Add(icon);
        row.Children.Add(txt);
        return row;
    }

    public static Button LinkButton(string label, Action onClick, bool enabled = true, Brush? color = null)
    {
        var text = new TextBlock
        {
            Text = label,
            FontFamily = AppFont,
            FontSize = 12.5,
            Foreground = color ?? AccentBrush,
            Opacity = enabled ? 1.0 : 0.5
        };
        return BareButton(text, onClick, enabled);
    }

    /// <summary>
    /// Horizontal row panel that measures each child's exact pixel text width and distributes
    /// leftover width EQUALLY to every child, guaranteeing identical left/right whitespace around every label.
    /// </summary>
    private sealed class EqualPaddingRowPanel : Panel
    {
        public double Gap { get; set; } = 3.0;

        protected override Size MeasureOverride(Size availableSize)
        {
            var count = InternalChildren.Count;
            if (count == 0) return new Size(0, 0);

            double sumWidth = 0;
            double maxHeight = 0;
            var unbounded = new Size(double.PositiveInfinity, availableSize.Height);
            for (var i = 0; i < count; i++)
            {
                var child = InternalChildren[i];
                child.Measure(unbounded);
                sumWidth += child.DesiredSize.Width;
                if (child.DesiredSize.Height > maxHeight)
                    maxHeight = child.DesiredSize.Height;
            }
            sumWidth += Math.Max(0, count - 1) * Gap;
            var desiredWidth = double.IsInfinity(availableSize.Width) ? sumWidth : Math.Max(sumWidth, availableSize.Width);
            return new Size(desiredWidth, maxHeight);
        }

        protected override Size ArrangeOverride(Size finalSize)
        {
            var count = InternalChildren.Count;
            if (count == 0) return finalSize;

            double naturalSum = 0;
            for (var i = 0; i < count; i++)
                naturalSum += InternalChildren[i].DesiredSize.Width;

            var totalGap = Math.Max(0, count - 1) * Gap;
            var availWidth = Math.Max(0, finalSize.Width - totalGap);

            double x = 0;
            if (availWidth >= naturalSum || naturalSum <= 0)
            {
                var extraPerItem = (availWidth - naturalSum) / count;
                for (var i = 0; i < count; i++)
                {
                    var child = InternalChildren[i];
                    var w = Math.Max(0, child.DesiredSize.Width + extraPerItem);
                    child.Arrange(new Rect(x, 0, w, finalSize.Height));
                    x += w + Gap;
                }
            }
            else
            {
                var scale = availWidth / naturalSum;
                for (var i = 0; i < count; i++)
                {
                    var child = InternalChildren[i];
                    var w = Math.Max(0, child.DesiredSize.Width * scale);
                    child.Arrange(new Rect(x, 0, w, finalSize.Height));
                    x += w + Gap;
                }
            }
            return finalSize;
        }
    }

    /// <summary>
    /// Liquid Glass Segmented Control (1:1 family aesthetic with Android NativeGlassNavigation tray &amp; macOS segmented Picker).
    /// Uses EqualPaddingRowPanel so every option gets identical left &amp; right whitespace and never squeezes CJK labels.
    /// </summary>
    public static Border SegmentedControl(
        IReadOnlyList<(string Label, bool Selected, Action OnClick)> items,
        bool equalColumns = false,
        bool compact = false)
    {
        var outerRadius = compact ? 9.5 : 11.0;
        var innerRadius = compact ? 7.0 : 8.0;

        Panel hostPanel;
        Grid? eqGrid = null;
        if (equalColumns)
        {
            eqGrid = new Grid();
            for (var i = 0; i < items.Count; i++)
                eqGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            hostPanel = eqGrid;
        }
        else
        {
            hostPanel = new EqualPaddingRowPanel { Gap = 3.0 };
        }

        for (var i = 0; i < items.Count; i++)
        {
            var (label, selected, onClick) = items[i];
            var segmentInner = new Grid();

            var txt = new TextBlock
            {
                Text = label,
                FontFamily = AppFont,
                FontSize = compact ? 11.5 : 12.5,
                FontWeight = selected ? FontWeights.SemiBold : FontWeights.Normal,
                Foreground = selected ? TextBrush : MutedBrush,
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
                TextWrapping = TextWrapping.NoWrap,
                TextTrimming = TextTrimming.CharacterEllipsis,
                Margin = new Thickness(0, 0, 0, selected ? 1.5 : 0)
            };
            segmentInner.Children.Add(txt);

            if (selected)
            {
                // Persistent 10x2px Nordic Slate indicator mark matching NativeGlassNavigation line 265
                segmentInner.Children.Add(new Border
                {
                    Width = 10,
                    Height = 2,
                    CornerRadius = new CornerRadius(1),
                    Background = AccentBrush,
                    HorizontalAlignment = HorizontalAlignment.Center,
                    VerticalAlignment = VerticalAlignment.Bottom,
                    Margin = new Thickness(0, 0, 0, -3)
                });
            }

            var segBorder = new Border
            {
                Background = selected ? GlassTrayBrush : Brushes.Transparent,
                BorderBrush = selected ? GlassTrayRimBrush : Brushes.Transparent,
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(innerRadius),
                Padding = compact ? new Thickness(6, 5, 6, 5) : new Thickness(8, 6, 8, 6),
                Margin = equalColumns
                    ? new Thickness(i == 0 ? 0 : 1.5, 0, i == items.Count - 1 ? 0 : 1.5, 0)
                    : new Thickness(0),
                SnapsToDevicePixels = true,
                Child = segmentInner
            };

            var btn = BareButton(segBorder, onClick, true);
            if (!selected)
            {
                btn.MouseEnter += (_, _) =>
                {
                    segBorder.Background = HoverBrush;
                    txt.Foreground = TextBrush;
                };
                btn.MouseLeave += (_, _) =>
                {
                    segBorder.Background = Brushes.Transparent;
                    txt.Foreground = MutedBrush;
                };
            }
            if (eqGrid is not null)
                Grid.SetColumn(btn, i);
            hostPanel.Children.Add(btn);
        }

        return new Border
        {
            Background = GlassTrackBrush,
            BorderBrush = GlassSubtleRimBrush,
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(outerRadius),
            Padding = new Thickness(3),
            SnapsToDevicePixels = true,
            Child = hostPanel
        };
    }

    public static Button BareButton(UIElement content, Action onClick, bool enabled = true)
    {
        var template = new ControlTemplate(typeof(Button));
        var presenter = new FrameworkElementFactory(typeof(ContentPresenter));
        template.VisualTree = presenter;

        var btn = new Button
        {
            Content = content,
            Template = template,
            Cursor = enabled ? Cursors.Hand : Cursors.Arrow,
            IsEnabled = enabled,
            Background = Brushes.Transparent,
            BorderThickness = new Thickness(0),
            Focusable = true
        };
        btn.Click += (_, _) => { if (btn.IsEnabled) onClick(); };
        return btn;
    }

    public static void SetButtonState(Button btn, string label, bool enabled, bool primary = true)
    {
        btn.IsEnabled = enabled;
        btn.Cursor = enabled ? Cursors.Hand : Cursors.Arrow;
        if (btn.Content is Border border)
        {
            border.Opacity = enabled ? 1.0 : (primary ? 0.55 : 0.5);
            if (primary)
                border.Background = enabled ? AccentBrush : SoftBrush;
            if (border.Child is TextBlock tb)
                tb.Text = label;
            else if (border.Child is StackPanel sp && sp.Children.OfType<TextBlock>().FirstOrDefault() is { } rowTb)
                rowTb.Text = label;
        }
    }

    public static TextBox StyledTextBox(string initial = "", string placeholder = "", bool multiline = false)
    {
        var template = new ControlTemplate(typeof(TextBox));
        var bd = new FrameworkElementFactory(typeof(Border), "Bd");
        bd.SetValue(Border.BackgroundProperty, new TemplateBindingExtension(Control.BackgroundProperty));
        bd.SetValue(Border.BorderBrushProperty, new TemplateBindingExtension(Control.BorderBrushProperty));
        bd.SetValue(Border.BorderThicknessProperty, new TemplateBindingExtension(Control.BorderThicknessProperty));
        bd.SetValue(Border.CornerRadiusProperty, new CornerRadius(ControlRadius));

        var grid = new FrameworkElementFactory(typeof(Grid));
        var contentHost = new FrameworkElementFactory(typeof(ScrollViewer), "PART_ContentHost");
        contentHost.SetValue(FrameworkElement.MarginProperty, new Thickness(0));
        grid.AppendChild(contentHost);

        var watermark = new FrameworkElementFactory(typeof(TextBlock), "Watermark");
        watermark.SetValue(TextBlock.TextProperty, new TemplateBindingExtension(FrameworkElement.TagProperty));
        watermark.SetValue(TextBlock.FontFamilyProperty, AppFont);
        watermark.SetValue(TextBlock.FontSizeProperty, 13.5);
        watermark.SetValue(TextBlock.ForegroundProperty, MutedBrush);
        watermark.SetValue(TextBlock.PaddingProperty, new Thickness(12, 8.5, 12, 8.5));
        watermark.SetValue(UIElement.IsHitTestVisibleProperty, false);
        watermark.SetValue(UIElement.VisibilityProperty, Visibility.Collapsed);
        watermark.SetValue(TextBlock.TextTrimmingProperty, TextTrimming.CharacterEllipsis);
        grid.AppendChild(watermark);

        bd.AppendChild(grid);
        template.VisualTree = bd;

        var emptyTrigger = new Trigger { Property = TextBox.TextProperty, Value = "" };
        emptyTrigger.Setters.Add(new Setter(UIElement.VisibilityProperty, Visibility.Visible, "Watermark"));
        template.Triggers.Add(emptyTrigger);

        var nullTrigger = new Trigger { Property = TextBox.TextProperty, Value = null };
        nullTrigger.Setters.Add(new Setter(UIElement.VisibilityProperty, Visibility.Visible, "Watermark"));
        template.Triggers.Add(nullTrigger);

        var focusTrigger = new Trigger { Property = UIElement.IsKeyboardFocusWithinProperty, Value = true };
        focusTrigger.Setters.Add(new Setter(Border.BorderBrushProperty, AccentBrush, "Bd"));
        template.Triggers.Add(focusTrigger);

        return new TextBox
        {
            Text = initial,
            Tag = placeholder,
            Template = template,
            FontFamily = AppFont,
            FontSize = 13.5,
            Foreground = TextBrush,
            CaretBrush = AccentBrush,
            Background = GlassInputBrush,
            BorderBrush = GlassSubtleRimBrush,
            BorderThickness = new Thickness(1),
            Padding = new Thickness(10, 8, 10, 8),
            AcceptsReturn = multiline,
            TextWrapping = multiline ? TextWrapping.Wrap : TextWrapping.NoWrap,
            VerticalScrollBarVisibility = multiline ? ScrollBarVisibility.Auto : ScrollBarVisibility.Hidden
        };
    }

    public static ComboBox StyledComboBox()
    {
        var itemTemplate = new ControlTemplate(typeof(ComboBoxItem));
        var itemBd = new FrameworkElementFactory(typeof(Border), "ItemBd");
        itemBd.SetValue(Border.BackgroundProperty, Brushes.Transparent);
        itemBd.SetValue(Border.CornerRadiusProperty, new CornerRadius(7));
        itemBd.SetValue(Border.PaddingProperty, new Thickness(10, 7, 10, 7));
        itemBd.SetValue(FrameworkElement.MarginProperty, new Thickness(4, 2, 4, 2));
        var itemPresenter = new FrameworkElementFactory(typeof(ContentPresenter));
        itemBd.AppendChild(itemPresenter);
        itemTemplate.VisualTree = itemBd;

        var hoverTrigger = new Trigger { Property = UIElement.IsMouseOverProperty, Value = true };
        hoverTrigger.Setters.Add(new Setter(Border.BackgroundProperty, HoverBrush, "ItemBd"));
        itemTemplate.Triggers.Add(hoverTrigger);

        var selTrigger = new Trigger { Property = ListBoxItem.IsSelectedProperty, Value = true };
        selTrigger.Setters.Add(new Setter(Border.BackgroundProperty, SoftBrush, "ItemBd"));
        itemTemplate.Triggers.Add(selTrigger);

        var itemStyle = new Style(typeof(ComboBoxItem));
        itemStyle.Setters.Add(new Setter(Control.ForegroundProperty, TextBrush));
        itemStyle.Setters.Add(new Setter(Control.FontFamilyProperty, AppFont));
        itemStyle.Setters.Add(new Setter(Control.FontSizeProperty, 13.0));
        itemStyle.Setters.Add(new Setter(Control.TemplateProperty, itemTemplate));

        var toggleTemplate = new ControlTemplate(typeof(ToggleButton));
        var toggleBd = new FrameworkElementFactory(typeof(Border), "ToggleBd");
        toggleBd.SetValue(Border.BackgroundProperty, GlassInputBrush);
        toggleBd.SetValue(Border.BorderBrushProperty, GlassSubtleRimBrush);
        toggleBd.SetValue(Border.BorderThicknessProperty, new Thickness(1));
        toggleBd.SetValue(Border.CornerRadiusProperty, new CornerRadius(ControlRadius));
        var arrow = new FrameworkElementFactory(typeof(TextBlock));
        arrow.SetValue(TextBlock.TextProperty, "▾");
        arrow.SetValue(TextBlock.FontSizeProperty, 12.0);
        arrow.SetValue(TextBlock.ForegroundProperty, MutedBrush);
        arrow.SetValue(FrameworkElement.HorizontalAlignmentProperty, HorizontalAlignment.Right);
        arrow.SetValue(FrameworkElement.VerticalAlignmentProperty, VerticalAlignment.Center);
        arrow.SetValue(FrameworkElement.MarginProperty, new Thickness(0, 0, 12, 0));
        toggleBd.AppendChild(arrow);
        toggleTemplate.VisualTree = toggleBd;

        var toggleHover = new Trigger { Property = UIElement.IsMouseOverProperty, Value = true };
        toggleHover.Setters.Add(new Setter(Border.BorderBrushProperty, AccentBrush, "ToggleBd"));
        toggleTemplate.Triggers.Add(toggleHover);

        var comboTemplate = new ControlTemplate(typeof(ComboBox));
        var rootGrid = new FrameworkElementFactory(typeof(Grid));

        var toggleBtn = new FrameworkElementFactory(typeof(ToggleButton), "ToggleButton");
        toggleBtn.SetValue(Control.TemplateProperty, toggleTemplate);
        toggleBtn.SetValue(UIElement.FocusableProperty, false);
        toggleBtn.SetValue(ToggleButton.ClickModeProperty, ClickMode.Press);
        toggleBtn.SetBinding(ToggleButton.IsCheckedProperty, new System.Windows.Data.Binding("IsDropDownOpen")
        {
            Mode = System.Windows.Data.BindingMode.TwoWay,
            RelativeSource = new System.Windows.Data.RelativeSource(System.Windows.Data.RelativeSourceMode.TemplatedParent)
        });
        rootGrid.AppendChild(toggleBtn);

        var contentSite = new FrameworkElementFactory(typeof(ContentPresenter), "ContentSite");
        contentSite.SetValue(UIElement.IsHitTestVisibleProperty, false);
        contentSite.SetValue(ContentPresenter.ContentProperty, new TemplateBindingExtension(ComboBox.SelectionBoxItemProperty));
        contentSite.SetValue(ContentPresenter.ContentTemplateProperty, new TemplateBindingExtension(ComboBox.SelectionBoxItemTemplateProperty));
        contentSite.SetValue(FrameworkElement.MarginProperty, new Thickness(12, 8, 28, 8));
        contentSite.SetValue(FrameworkElement.VerticalAlignmentProperty, VerticalAlignment.Center);
        contentSite.SetValue(FrameworkElement.HorizontalAlignmentProperty, HorizontalAlignment.Left);
        rootGrid.AppendChild(contentSite);

        var popup = new FrameworkElementFactory(typeof(Popup), "Popup");
        popup.SetValue(Popup.PlacementProperty, PlacementMode.Bottom);
        popup.SetValue(Popup.AllowsTransparencyProperty, true);
        popup.SetValue(UIElement.FocusableProperty, false);
        popup.SetValue(Popup.PopupAnimationProperty, PopupAnimation.Fade);
        popup.SetBinding(Popup.IsOpenProperty, new System.Windows.Data.Binding("IsDropDownOpen")
        {
            RelativeSource = new System.Windows.Data.RelativeSource(System.Windows.Data.RelativeSourceMode.TemplatedParent)
        });

        var dropBorder = new FrameworkElementFactory(typeof(Border));
        dropBorder.SetValue(Border.BackgroundProperty, SurfaceBrush);
        dropBorder.SetValue(Border.BorderBrushProperty, GlassRimBrush);
        dropBorder.SetValue(Border.BorderThicknessProperty, new Thickness(1));
        dropBorder.SetValue(Border.CornerRadiusProperty, new CornerRadius(11));
        dropBorder.SetValue(Border.PaddingProperty, new Thickness(0, 4, 0, 4));
        dropBorder.SetValue(FrameworkElement.MarginProperty, new Thickness(0, 4, 0, 0));
        dropBorder.SetBinding(FrameworkElement.MinWidthProperty, new System.Windows.Data.Binding("ActualWidth")
        {
            RelativeSource = new System.Windows.Data.RelativeSource(System.Windows.Data.RelativeSourceMode.TemplatedParent)
        });
        dropBorder.SetValue(FrameworkElement.MaxHeightProperty, 280.0);

        var dropScroll = new FrameworkElementFactory(typeof(ScrollViewer));
        dropScroll.SetValue(ScrollViewer.VerticalScrollBarVisibilityProperty, ScrollBarVisibility.Auto);
        var itemsHost = new FrameworkElementFactory(typeof(StackPanel));
        itemsHost.SetValue(Panel.IsItemsHostProperty, true);
        dropScroll.AppendChild(itemsHost);
        dropBorder.AppendChild(dropScroll);
        popup.AppendChild(dropBorder);
        rootGrid.AppendChild(popup);

        comboTemplate.VisualTree = rootGrid;

        var disabledTrigger = new Trigger { Property = UIElement.IsEnabledProperty, Value = false };
        disabledTrigger.Setters.Add(new Setter(UIElement.OpacityProperty, 0.5));
        comboTemplate.Triggers.Add(disabledTrigger);

        return new ComboBox
        {
            Template = comboTemplate,
            ItemContainerStyle = itemStyle,
            FontFamily = AppFont,
            FontSize = 13.5,
            Foreground = TextBrush,
            Cursor = Cursors.Hand
        };
    }

    public static ComboBoxItem AddComboItem(ComboBox combo, string label, object? tag = null)
    {
        var item = new ComboBoxItem
        {
            Content = new TextBlock
            {
                Text = label,
                FontFamily = AppFont,
                FontSize = 13.0,
                Foreground = TextBrush,
                TextTrimming = TextTrimming.CharacterEllipsis
            },
            Tag = tag
        };
        combo.Items.Add(item);
        return item;
    }

    /// <summary>
    /// Animated 40x22 Liquid Glass pill toggle switch (140ms CubicEase thumb slide).
    /// </summary>
    public static Button ToggleSwitch(bool initial, Action<bool> onToggled, bool enabled = true)
    {
        var isOn = initial;
        var translate = new TranslateTransform(isOn ? 18.0 : 0.0, 0.0);
        var knob = new Border
        {
            Width = 17,
            Height = 17,
            CornerRadius = new CornerRadius(8.5),
            HorizontalAlignment = HorizontalAlignment.Left,
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(2.5, 0, 0, 0),
            Background = isOn ? OnAccentBrush : SurfaceBrush,
            RenderTransform = translate,
            Effect = new DropShadowEffect
            {
                Color = Colors.Black,
                BlurRadius = 5,
                ShadowDepth = 1,
                Direction = 270,
                Opacity = 0.22
            }
        };

        var pill = new Border
        {
            Width = 40,
            Height = 22,
            CornerRadius = new CornerRadius(11),
            Background = isOn ? AccentBrush : GlassTrackBrush,
            BorderBrush = isOn ? AccentBrush : GlassSubtleRimBrush,
            BorderThickness = new Thickness(1),
            SnapsToDevicePixels = true,
            Opacity = enabled ? 1.0 : 0.45,
            Child = knob
        };

        var btn = BareButton(pill, () =>
        {
            isOn = !isOn;
            pill.Background = isOn ? AccentBrush : GlassTrackBrush;
            pill.BorderBrush = isOn ? AccentBrush : GlassSubtleRimBrush;
            knob.Background = isOn ? OnAccentBrush : SurfaceBrush;
            var anim = new DoubleAnimation(isOn ? 18.0 : 0.0, TimeSpan.FromMilliseconds(140))
            {
                EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut }
            };
            translate.BeginAnimation(TranslateTransform.XProperty, anim);
            onToggled(isOn);
        }, enabled);
        return btn;
    }

    /// <summary>
    /// Executive settings row with crisp title, optional muted subtitle, and right-docked animated ToggleSwitch.
    /// </summary>
    public static Border SettingToggleRow(string title, string? subtitle, bool isChecked, Action<bool> onToggled, bool enabled = true)
    {
        var dock = new DockPanel { LastChildFill = true };
        var toggle = ToggleSwitch(isChecked, onToggled, enabled);
        toggle.VerticalAlignment = VerticalAlignment.Center;
        toggle.Margin = new Thickness(14, 0, 0, 0);
        DockPanel.SetDock(toggle, Dock.Right);
        dock.Children.Add(toggle);

        var left = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        left.Children.Add(new TextBlock
        {
            Text = title,
            FontFamily = AppFont,
            FontSize = 13.5,
            FontWeight = FontWeights.Medium,
            Foreground = TextBrush,
            TextWrapping = TextWrapping.Wrap
        });
        if (!string.IsNullOrWhiteSpace(subtitle))
        {
            left.Children.Add(new TextBlock
            {
                Text = subtitle,
                FontFamily = AppFont,
                FontSize = 11.5,
                Foreground = MutedBrush,
                TextWrapping = TextWrapping.Wrap,
                Margin = new Thickness(0, 2, 0, 0)
            });
        }
        dock.Children.Add(left);

        return new Border
        {
            Background = GlassTrackBrush,
            BorderBrush = GlassSubtleRimBrush,
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(11),
            Padding = new Thickness(13, 9.5, 13, 9.5),
            Margin = new Thickness(0, 0, 0, 7),
            SnapsToDevicePixels = true,
            Child = dock
        };
    }

    /// <summary>
    /// Stepless interactive Liquid Glass slider with sub-pixel smooth drag, click-to-seek, scroll-wheel fine-tuning,
    /// and programmatic SetValueSilently callback for syncing with preset buttons.
    /// </summary>
    public static (FrameworkElement Control, Action<double> SetValueSilently) SteplessSlider(
        double min,
        double max,
        double initial,
        Action<double> onLiveChange,
        Action<double>? onCommit = null)
    {
        var currentVal = Math.Clamp(initial, min, max);
        const double thumbSize = 18.0;
        const double halfThumb = thumbSize / 2.0;

        var host = new Grid
        {
            Height = 28,
            Background = Brushes.Transparent,
            Cursor = Cursors.Hand,
            SnapsToDevicePixels = true
        };

        var trackBg = new Border
        {
            Height = 6,
            CornerRadius = new CornerRadius(3),
            Margin = new Thickness(halfThumb, 0, halfThumb, 0),
            VerticalAlignment = VerticalAlignment.Center,
            Background = GlassTrackBrush,
            BorderBrush = GlassSubtleRimBrush,
            BorderThickness = new Thickness(1)
        };
        host.Children.Add(trackBg);

        var fillBar = new Border
        {
            Height = 6,
            CornerRadius = new CornerRadius(3),
            HorizontalAlignment = HorizontalAlignment.Left,
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(halfThumb, 0, 0, 0),
            Background = AccentBrush,
            IsHitTestVisible = false
        };
        host.Children.Add(fillBar);

        var thumbTranslate = new TranslateTransform(0, 0);
        var thumb = new Border
        {
            Width = thumbSize,
            Height = thumbSize,
            CornerRadius = new CornerRadius(halfThumb),
            HorizontalAlignment = HorizontalAlignment.Left,
            VerticalAlignment = VerticalAlignment.Center,
            Background = SurfaceBrush,
            BorderBrush = AccentBrush,
            BorderThickness = new Thickness(2),
            RenderTransform = thumbTranslate,
            IsHitTestVisible = false,
            Effect = new DropShadowEffect
            {
                Color = Colors.Black,
                BlurRadius = 8,
                ShadowDepth = 1.5,
                Direction = 270,
                Opacity = IsDark ? 0.38 : 0.18
            }
        };
        host.Children.Add(thumb);

        void LayoutVisual()
        {
            var travel = Math.Max(0.0, host.ActualWidth - thumbSize);
            var ratio = max > min ? Math.Clamp((currentVal - min) / (max - min), 0.0, 1.0) : 0.0;
            var x = ratio * travel;
            thumbTranslate.X = x;
            fillBar.Width = Math.Max(0.0, x);
        }

        host.SizeChanged += (_, _) => LayoutVisual();
        host.Loaded += (_, _) => LayoutVisual();

        void UpdateFromMouseX(double mouseX, bool fireLive)
        {
            var travel = Math.Max(1.0, host.ActualWidth - thumbSize);
            var ratio = Math.Clamp((mouseX - halfThumb) / travel, 0.0, 1.0);
            currentVal = min + ratio * (max - min);
            LayoutVisual();
            if (fireLive)
                onLiveChange(currentVal);
        }

        var dragging = false;
        host.MouseLeftButtonDown += (_, e) =>
        {
            dragging = true;
            host.CaptureMouse();
            UpdateFromMouseX(e.GetPosition(host).X, fireLive: true);
            e.Handled = true;
        };
        host.MouseMove += (_, e) =>
        {
            if (!dragging) return;
            UpdateFromMouseX(e.GetPosition(host).X, fireLive: true);
        };
        host.MouseLeftButtonUp += (_, e) =>
        {
            if (!dragging) return;
            dragging = false;
            host.ReleaseMouseCapture();
            onCommit?.Invoke(currentVal);
            e.Handled = true;
        };
        host.LostMouseCapture += (_, _) =>
        {
            if (!dragging) return;
            dragging = false;
            onCommit?.Invoke(currentVal);
        };
        host.MouseWheel += (_, e) =>
        {
            var step = (max - min) * 0.03 * (e.Delta > 0 ? 1.0 : -1.0);
            currentVal = Math.Clamp(currentVal + step, min, max);
            LayoutVisual();
            onLiveChange(currentVal);
            onCommit?.Invoke(currentVal);
            e.Handled = true;
        };
        host.MouseEnter += (_, _) => thumb.BorderBrush = AccentHoverBrush;
        host.MouseLeave += (_, _) => thumb.BorderBrush = AccentBrush;

        void SetSilently(double val)
        {
            currentVal = Math.Clamp(val, min, max);
            LayoutVisual();
        }

        return (host, SetSilently);
    }
}
