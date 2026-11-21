using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using MonitorApp.Core;

namespace MonitorApp.UI;

/// <summary>
/// Monitor Family Design System for Windows 11.
/// Shares exact typography (MiSans), geometry (22px card / 16px control), and Nordic Slate palette (#466987) with Android &amp; macOS.
/// </summary>
public static class UiKit
{
    public const double CardRadius = 20.0;
    public const double ControlRadius = 14.0;

    public static readonly FontFamily AppFont = CreateMiSansFamily();
    public static readonly FontFamily MonoFont = new("Cascadia Mono, Consolas, Courier New");

    public static bool IsDark { get; private set; }

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

    public static void ApplyTheme(bool dark)
    {
        IsDark = dark;
        if (dark)
        {
            BgBrush = Hex(0x16, 0x1D, 0x24);
            HeaderBrush = Hex(0x1A, 0x22, 0x2B);
            SidebarBrush = Hex(0x1B, 0x24, 0x2E);
            SurfaceBrush = Hex(0x21, 0x2A, 0x34);
            SoftBrush = Hex(0x2D, 0x3A, 0x48);
            HoverBrush = Hex(0x28, 0x34, 0x41);
            LineBrush = Hex(0x35, 0x44, 0x53);
            TextBrush = Hex(0xF2, 0xF5, 0xF8);
            MutedBrush = Hex(0xA9, 0xB5, 0xC2);
            AccentBrush = Hex(0xAF, 0xC8, 0xDF);
            AccentHoverBrush = Hex(0xC2, 0xD7, 0xEB);
            OnAccentBrush = Hex(0x16, 0x1D, 0x24);
            ClaudeInkBrush = Hex(0xCD, 0xA9, 0x91);
            GreenBrush = Hex(0x6C, 0xCB, 0x98);
            WarningBrush = Hex(0xE1, 0xBE, 0x88);
            DangerBrush = Hex(0xED, 0xAB, 0xAB);
        }
        else
        {
            BgBrush = Hex(0xF4, 0xF6, 0xF8);
            HeaderBrush = Hex(0xF8, 0xFA, 0xFB);
            SidebarBrush = Hex(0xED, 0xF1, 0xF5);
            SurfaceBrush = Hex(0xFF, 0xFF, 0xFF);
            SoftBrush = Hex(0xE7, 0xED, 0xF2);
            HoverBrush = Hex(0xEE, 0xF2, 0xF6);
            LineBrush = Hex(0xE2, 0xE6, 0xEA);
            TextBrush = Hex(0x20, 0x28, 0x31);
            MutedBrush = Hex(0x5F, 0x6B, 0x78);
            AccentBrush = Hex(0x46, 0x69, 0x87);
            AccentHoverBrush = Hex(0x37, 0x59, 0x74);
            OnAccentBrush = Hex(0xFF, 0xFF, 0xFF);
            ClaudeInkBrush = Hex(0x96, 0x66, 0x4F);
            GreenBrush = Hex(0x2B, 0x7A, 0x57);
            WarningBrush = Hex(0x86, 0x60, 0x22);
            DangerBrush = Hex(0xA4, 0x43, 0x43);
        }
    }

    private static SolidColorBrush Hex(byte r, byte g, byte b)
    {
        var brush = new SolidColorBrush(Color.FromRgb(r, g, b));
        brush.Freeze();
        return brush;
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
            Background = SurfaceBrush,
            BorderBrush = LineBrush,
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(radius),
            Padding = new Thickness(padding),
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
            CornerRadius = new CornerRadius(ControlRadius),
            Padding = new Thickness(16, 10, 16, 10),
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
    /// Family secondary button: quiet soft slate surface + accent ink, matching NativeUi.button(label, false, ...).
    /// </summary>
    public static Button SecondaryButton(string label, Action onClick, bool enabled = true, Brush? textBrush = null, string? glyph = null)
    {
        var fg = textBrush ?? AccentBrush;
        var content = ButtonLabelRow(label, fg, FontWeights.Medium, glyph);
        var bg = SoftBrush;
        var hover = LineBrush;
        var border = new Border
        {
            Background = bg,
            CornerRadius = new CornerRadius(ControlRadius),
            Padding = new Thickness(14, 9, 14, 9),
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

    public static Button IconButton(string glyph, string tooltip, Action onClick, bool selected = false, bool enabled = true)
    {
        var ink = selected ? AccentBrush : MutedBrush;
        var icon = new MonitorIcon(glyph, ink, 18);
        var bg = selected ? SoftBrush : Brushes.Transparent;
        var hover = SoftBrush;
        var border = new Border
        {
            Width = 34,
            Height = 34,
            CornerRadius = new CornerRadius(11),
            Background = bg,
            Child = icon,
            Opacity = enabled ? 1.0 : 0.4
        };
        var btn = BareButton(border, onClick, enabled);
        btn.ToolTip = tooltip;
        if (enabled)
        {
            btn.MouseEnter += (_, _) => border.Background = hover;
            btn.MouseLeave += (_, _) => border.Background = bg;
        }
        return btn;
    }

    private static UIElement ButtonLabelRow(string label, Brush fg, FontWeight weight, string? glyph)
    {
        var txt = new TextBlock
        {
            Text = label,
            FontFamily = AppFont,
            FontSize = 13.5,
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
        var icon = new MonitorIcon(glyph, fg, 15.5) { Margin = new Thickness(0, 0, 7, 0) };
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
            FontSize = 13,
            Foreground = color ?? AccentBrush,
            Opacity = enabled ? 1.0 : 0.5
        };
        return BareButton(text, onClick, enabled);
    }

    public static Button PillButton(string label, bool selected, Action onClick)
    {
        var text = new TextBlock
        {
            Text = label,
            FontFamily = AppFont,
            FontSize = 12.5,
            FontWeight = selected ? FontWeights.SemiBold : FontWeights.Normal,
            Foreground = selected ? OnAccentBrush : MutedBrush
        };
        var bg = selected ? AccentBrush : SoftBrush;
        var hover = selected ? AccentHoverBrush : LineBrush;
        var border = new Border
        {
            Background = bg,
            CornerRadius = new CornerRadius(999),
            Padding = new Thickness(13, 6, 13, 6),
            Margin = new Thickness(0, 0, 6, 6),
            Child = text
        };
        var btn = BareButton(border, onClick, true);
        btn.MouseEnter += (_, _) => border.Background = hover;
        btn.MouseLeave += (_, _) => border.Background = bg;
        return btn;
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

    public static TextBox StyledTextBox(string initial = "", string placeholder = "", bool multiline = false)
    {
        var box = new TextBox
        {
            Text = initial,
            FontFamily = AppFont,
            FontSize = 13.5,
            Foreground = TextBrush,
            CaretBrush = AccentBrush,
            Background = SurfaceBrush,
            BorderBrush = LineBrush,
            BorderThickness = new Thickness(1),
            Padding = new Thickness(11, 9, 11, 9),
            AcceptsReturn = multiline,
            TextWrapping = multiline ? TextWrapping.Wrap : TextWrapping.NoWrap,
            VerticalScrollBarVisibility = multiline ? ScrollBarVisibility.Auto : ScrollBarVisibility.Hidden
        };
        if (!string.IsNullOrEmpty(placeholder))
            box.ToolTip = placeholder;
        return box;
    }
}
