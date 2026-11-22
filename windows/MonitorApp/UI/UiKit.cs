using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
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
    public static BitmapSource? ActiveWallpaperImage { get; private set; }

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
    public static Brush AmbientGlowPrimaryBrush { get; private set; } = null!;
    public static Brush AmbientGlowSecondaryBrush { get; private set; } = null!;

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

    public static void ApplyTheme(
        bool dark,
        string backgroundMode = "cloud",
        string wallpaperStyle = "balanced",
        BitmapSource? wallpaper = null)
    {
        IsDark = dark;
        ActiveBackgroundMode = backgroundMode ?? "cloud";
        ActiveWallpaperStyle = wallpaperStyle ?? "balanced";
        ActiveWallpaperImage = wallpaper;

        var (cTop, cMid, cBot) = (dark, ActiveBackgroundMode) switch
        {
            (true, "sand") => (Color.FromRgb(0x1B, 0x18, 0x16), Color.FromRgb(0x21, 0x1D, 0x1A), Color.FromRgb(0x18, 0x15, 0x13)),
            (true, "mist") => (Color.FromRgb(0x13, 0x1C, 0x19), Color.FromRgb(0x17, 0x22, 0x1F), Color.FromRgb(0x11, 0x19, 0x17)),
            (true, "blue") => (Color.FromRgb(0x11, 0x1A, 0x26), Color.FromRgb(0x15, 0x22, 0x31), Color.FromRgb(0x10, 0x18, 0x24)),
            (true, _) => (Color.FromRgb(0x12, 0x19, 0x21), Color.FromRgb(0x16, 0x20, 0x2A), Color.FromRgb(0x13, 0x1C, 0x26)),
            (false, "sand") => (Color.FromRgb(0xF6, 0xF1, 0xE8), Color.FromRgb(0xFA, 0xF7, 0xF2), Color.FromRgb(0xF2, 0xEB, 0xE0)),
            (false, "mist") => (Color.FromRgb(0xE8, 0xF2, 0xED), Color.FromRgb(0xF3, 0xF8, 0xF5), Color.FromRgb(0xE4, 0xEF, 0xE9)),
            (false, "blue") => (Color.FromRgb(0xE2, 0xED, 0xF8), Color.FromRgb(0xF0, 0xF6, 0xFC), Color.FromRgb(0xDE, 0xEA, 0xF7)),
            (false, _) => (Color.FromRgb(0xE9, 0xF0, 0xF7), Color.FromRgb(0xF4, 0xF7, 0xFB), Color.FromRgb(0xE6, 0xEE, 0xF6))
        };

        if (dark)
        {
            BgBrush = new SolidColorBrush(cMid);
            BgBrush.Freeze();
            HeaderBrush = Hex(0x18, 0x20, 0x29);
            SidebarBrush = Hex(0x1B, 0x24, 0x2E);
            SurfaceBrush = Hex(0x21, 0x2B, 0x35);
            SoftBrush = Hex(0x2B, 0x38, 0x46);
            HoverBrush = Hex(0x28, 0x35, 0x43);
            LineBrush = Hex(0x34, 0x44, 0x54);
            TextBrush = Hex(0xF2, 0xF5, 0xF8);
            MutedBrush = Hex(0x9E, 0xAC, 0xBA);
            AccentBrush = Hex(0xAF, 0xC8, 0xDF);
            AccentHoverBrush = Hex(0xC2, 0xD7, 0xEB);
            OnAccentBrush = Hex(0x14, 0x1B, 0x22);
            ClaudeInkBrush = Hex(0xCD, 0xA9, 0x91);
            GreenBrush = Hex(0x6C, 0xCB, 0x98);
            WarningBrush = Hex(0xE3, 0xBF, 0x86);
            DangerBrush = Hex(0xED, 0xAB, 0xAB);

            CanvasBrush = DiagonalGradient((0.0, cTop), (0.5, cMid), (1.0, cBot));
            MicaCanvasBrush = DiagonalGradient(
                (0.0, Color.FromArgb(0xCC, cTop.R, cTop.G, cTop.B)),
                (0.5, Color.FromArgb(0xDC, cMid.R, cMid.G, cMid.B)),
                (1.0, Color.FromArgb(0xCC, cBot.R, cBot.G, cBot.B)));
            GlassHeaderBrush = VerticalGradient(
                Color.FromArgb(0xC8, 0x1D, 0x27, 0x32),
                Color.FromArgb(0x9E, 0x16, 0x1F, 0x28));
            GlassRailBrush = VerticalGradient(
                Color.FromArgb(0xCE, 0x29, 0x37, 0x46),
                Color.FromArgb(0xDC, 0x1C, 0x26, 0x31));
            GlassCardBrush = VerticalGradient(
                (0.0, Color.FromArgb(0xDC, 0x2B, 0x39, 0x48)),
                (0.55, Color.FromArgb(0xC8, 0x21, 0x2C, 0x38)),
                (1.0, Color.FromArgb(0xD6, 0x1D, 0x27, 0x32)));
            GlassCardHoverBrush = VerticalGradient(
                Color.FromArgb(0xEA, 0x31, 0x41, 0x52),
                Color.FromArgb(0xD8, 0x25, 0x32, 0x40));
            GlassSelectedCardBrush = VerticalGradient(
                Color.FromArgb(0xEC, 0x35, 0x48, 0x5B),
                Color.FromArgb(0xDA, 0x28, 0x38, 0x48));
            GlassRimBrush = DiagonalGradient(
                (0.0, Color.FromArgb(0x78, 0x8D, 0xA5, 0xBC)),
                (0.55, Color.FromArgb(0x26, 0x4F, 0x62, 0x74)),
                (1.0, Color.FromArgb(0x4E, 0x68, 0x7C, 0x8D)));
            GlassSubtleRimBrush = DiagonalGradient(
                (0.0, Color.FromArgb(0x52, 0x84, 0x9B, 0xB2)),
                (1.0, Color.FromArgb(0x28, 0x4B, 0x5E, 0x70)));
            GlassTrackBrush = VerticalGradient(
                Color.FromArgb(0xAA, 0x17, 0x20, 0x29),
                Color.FromArgb(0xBE, 0x1D, 0x27, 0x32));
            GlassTrayBrush = VerticalGradient(
                Color.FromArgb(0xE6, 0x4C, 0x62, 0x78),
                Color.FromArgb(0xCC, 0x37, 0x49, 0x5B));
            GlassTrayRimBrush = HexA(0x75, 0x88, 0xA0, 0xB8);
            GlassButtonBrush = VerticalGradient(
                Color.FromArgb(0xD0, 0x2D, 0x3B, 0x4A),
                Color.FromArgb(0xBA, 0x24, 0x30, 0x3D));
            GlassButtonHoverBrush = VerticalGradient(
                Color.FromArgb(0xE6, 0x37, 0x48, 0x5A),
                Color.FromArgb(0xD4, 0x2C, 0x3B, 0x4A));
            GlassInputBrush = HexA(0xCC, 0x1A, 0x23, 0x2C);
            AmbientGlowPrimaryBrush = RadialGlow(Color.FromArgb(0x36, 0x4B, 0x79, 0xA6));
            AmbientGlowSecondaryBrush = RadialGlow(Color.FromArgb(0x24, 0x8E, 0x6B, 0x54));

            UpdateScrollResources(
                Color.FromArgb(0x40, 0xA9, 0xB8, 0xC6),
                Color.FromArgb(0x88, 0xAF, 0xC8, 0xDF));
        }
        else
        {
            BgBrush = new SolidColorBrush(cMid);
            BgBrush.Freeze();
            HeaderBrush = Hex(0xF6, 0xF9, 0xFC);
            SidebarBrush = Hex(0xEA, 0xF0, 0xF6);
            SurfaceBrush = Hex(0xFF, 0xFF, 0xFF);
            SoftBrush = Hex(0xE4, 0xEC, 0xF3);
            HoverBrush = Hex(0xEB, 0xF1, 0xF7);
            LineBrush = Hex(0xD8, 0xE1, 0xEA);
            TextBrush = Hex(0x1E, 0x27, 0x30);
            MutedBrush = Hex(0x5C, 0x69, 0x77);
            AccentBrush = Hex(0x46, 0x69, 0x87);
            AccentHoverBrush = Hex(0x37, 0x59, 0x74);
            OnAccentBrush = Hex(0xFF, 0xFF, 0xFF);
            ClaudeInkBrush = Hex(0x96, 0x66, 0x4F);
            GreenBrush = Hex(0x2B, 0x7A, 0x57);
            WarningBrush = Hex(0x8A, 0x60, 0x1E);
            DangerBrush = Hex(0xA4, 0x43, 0x43);

            CanvasBrush = DiagonalGradient((0.0, cTop), (0.48, cMid), (1.0, cBot));
            MicaCanvasBrush = DiagonalGradient(
                (0.0, Color.FromArgb(0xCE, cTop.R, cTop.G, cTop.B)),
                (0.5, Color.FromArgb(0xDC, cMid.R, cMid.G, cMid.B)),
                (1.0, Color.FromArgb(0xCE, cBot.R, cBot.G, cBot.B)));
            GlassHeaderBrush = VerticalGradient(
                Color.FromArgb(0xCC, 0xFF, 0xFF, 0xFF),
                Color.FromArgb(0x9A, 0xF1, 0xF6, 0xFB));
            GlassRailBrush = VerticalGradient(
                Color.FromArgb(0xCA, 0xFF, 0xFF, 0xFF),
                Color.FromArgb(0xB4, 0xEE, 0xF4, 0xFA));
            GlassCardBrush = VerticalGradient(
                (0.0, Color.FromArgb(0xEA, 0xFF, 0xFF, 0xFF)),
                (0.55, Color.FromArgb(0xD2, 0xF8, 0xFB, 0xFE)),
                (1.0, Color.FromArgb(0xDE, 0xF1, 0xF6, 0xFB)));
            GlassCardHoverBrush = VerticalGradient(
                Color.FromArgb(0xF6, 0xFF, 0xFF, 0xFF),
                Color.FromArgb(0xE6, 0xF3, 0xF8, 0xFC));
            GlassSelectedCardBrush = VerticalGradient(
                Color.FromArgb(0xF5, 0xE8, 0xF0, 0xF8),
                Color.FromArgb(0xE6, 0xDC, 0xE8, 0xF3));
            GlassRimBrush = DiagonalGradient(
                (0.0, Color.FromArgb(0xFA, 0xFF, 0xFF, 0xFF)),
                (0.52, Color.FromArgb(0x72, 0xFF, 0xFF, 0xFF)),
                (1.0, Color.FromArgb(0xB8, 0xC6, 0xD5, 0xE4)));
            GlassSubtleRimBrush = DiagonalGradient(
                (0.0, Color.FromArgb(0xEB, 0xFF, 0xFF, 0xFF)),
                (1.0, Color.FromArgb(0x9A, 0xCB, 0xD8, 0xE6)));
            GlassTrackBrush = VerticalGradient(
                Color.FromArgb(0x9E, 0xDC, 0xE5, 0xEF),
                Color.FromArgb(0x88, 0xE6, 0xEE, 0xF6));
            GlassTrayBrush = VerticalGradient(
                Color.FromArgb(0xF8, 0xFF, 0xFF, 0xFF),
                Color.FromArgb(0xE0, 0xE3, 0xEC, 0xF5));
            GlassTrayRimBrush = HexA(0xF0, 0xFF, 0xFF, 0xFF);
            GlassButtonBrush = VerticalGradient(
                Color.FromArgb(0xDC, 0xFF, 0xFF, 0xFF),
                Color.FromArgb(0xC2, 0xE6, 0xEE, 0xF6));
            GlassButtonHoverBrush = VerticalGradient(
                Color.FromArgb(0xF2, 0xFF, 0xFF, 0xFF),
                Color.FromArgb(0xDC, 0xDC, 0xE7, 0xF2));
            GlassInputBrush = HexA(0xD8, 0xFF, 0xFF, 0xFF);
            AmbientGlowPrimaryBrush = RadialGlow(Color.FromArgb(0x30, 0x6B, 0x9A, 0xC4));
            AmbientGlowSecondaryBrush = RadialGlow(Color.FromArgb(0x22, 0xC8, 0xA8, 0x8E));

            UpdateScrollResources(
                Color.FromArgb(0x36, 0x5C, 0x69, 0x77),
                Color.FromArgb(0x7A, 0x46, 0x69, 0x87));
        }
    }

    private static void UpdateScrollResources(Color idle, Color hover)
    {
        try
        {
            if (Application.Current?.Resources is { } res)
            {
                var idleBrush = new SolidColorBrush(idle);
                idleBrush.Freeze();
                var hoverBrush = new SolidColorBrush(hover);
                hoverBrush.Freeze();
                res["MonitorScrollThumbIdleBrush"] = idleBrush;
                res["MonitorScrollThumbHoverBrush"] = hoverBrush;
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

    /// <summary>
    /// Wraps a layout root in an atmospheric Liquid Glass light field (or Custom / Bing Daily Wallpaper with adaptive reading veil)
    /// while keeping foreground ClearType text 100% vector-sharp.
    /// </summary>
    public static Grid AmbientStage(UIElement content, bool compact = false)
    {
        var stage = new Grid();
        var backdropLayer = new Grid { IsHitTestVisible = false, ClipToBounds = true };

        if (ActiveWallpaperImage is not null && ActiveBackgroundMode is "bing" or "custom")
        {
            var blurRadius = ActiveWallpaperStyle switch
            {
                "clear" => 0.0,
                "frosted" => 26.0,
                _ => 9.0
            };
            var img = new Image
            {
                Source = ActiveWallpaperImage,
                Stretch = Stretch.UniformToFill,
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
                Margin = blurRadius > 0 ? new Thickness(-blurRadius * 1.2) : new Thickness(0)
            };
            RenderOptions.SetBitmapScalingMode(img, BitmapScalingMode.HighQuality);
            if (blurRadius > 0)
            {
                img.Effect = new BlurEffect { Radius = blurRadius, RenderingBias = RenderingBias.Performance };
            }
            backdropLayer.Children.Add(img);

            var veilAlpha = (IsDark, ActiveWallpaperStyle) switch
            {
                (true, "clear") => (byte)0x76,
                (true, "frosted") => (byte)0xBF,
                (true, _) => (byte)0x9E,
                (false, "clear") => (byte)0x72,
                (false, "frosted") => (byte)0xC2,
                (false, _) => (byte)0x9C
            };
            var baseCol = BgBrush.Color;
            backdropLayer.Children.Add(new Border
            {
                Background = HexA(veilAlpha, baseCol.R, baseCol.G, baseCol.B)
            });
        }
        else
        {
            var orb1 = new Border
            {
                Width = compact ? 260 : 560,
                Height = compact ? 220 : 440,
                HorizontalAlignment = HorizontalAlignment.Left,
                VerticalAlignment = VerticalAlignment.Top,
                Margin = compact ? new Thickness(-70, -60, 0, 0) : new Thickness(-120, -110, 0, 0),
                Background = AmbientGlowPrimaryBrush
            };
            var orb2 = new Border
            {
                Width = compact ? 240 : 520,
                Height = compact ? 220 : 420,
                HorizontalAlignment = HorizontalAlignment.Right,
                VerticalAlignment = VerticalAlignment.Bottom,
                Margin = compact ? new Thickness(0, 0, -70, -60) : new Thickness(0, 0, -110, -100),
                Background = AmbientGlowSecondaryBrush
            };
            backdropLayer.Children.Add(orb1);
            backdropLayer.Children.Add(orb2);
        }

        stage.Children.Add(backdropLayer);
        stage.Children.Add(content);
        return stage;
    }

    /// <summary>
    /// Wraps a Liquid Glass card/rail with a sibling GPU-cached ambient shadow layer so text inside stays 100% ClearType crisp.
    /// </summary>
    public static Grid ElevatedGlass(UIElement child, double padding = 20, double radius = CardRadius, Brush? fill = null, Brush? rim = null)
    {
        var wrapper = new Grid();
        var shadowCaster = new Border
        {
            IsHitTestVisible = false,
            CornerRadius = new CornerRadius(radius),
            Background = IsDark ? HexA(0x55, 0x0A, 0x10, 0x16) : HexA(0x38, 0xD8, 0xE3, 0xEE),
            Margin = new Thickness(1),
            Effect = new DropShadowEffect
            {
                Color = IsDark ? Colors.Black : Color.FromRgb(0x14, 0x2E, 0x48),
                BlurRadius = 22,
                ShadowDepth = 5,
                Direction = 270,
                Opacity = IsDark ? 0.28 : 0.08
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
            Child = child
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
            Child = child
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
}
