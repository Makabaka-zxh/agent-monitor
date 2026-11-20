using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace MonitorApp.UI;

public static class UiKit
{
    public static readonly FontFamily AppFont = new("MiSans, Microsoft YaHei UI, Segoe UI");
    public static readonly FontFamily MonoFont = new("Cascadia Mono, Consolas, Courier New");

    public static readonly SolidColorBrush BgBrush = Frozen(246, 247, 251);
    public static readonly SolidColorBrush SidebarBrush = Frozen(240, 242, 248);
    public static readonly SolidColorBrush SurfaceBrush = Frozen(255, 255, 255);
    public static readonly SolidColorBrush SoftBrush = Frozen(238, 242, 255);
    public static readonly SolidColorBrush MutedBgBrush = Frozen(243, 244, 246);
    public static readonly SolidColorBrush LineBrush = Frozen(229, 232, 240);
    public static readonly SolidColorBrush TextBrush = Frozen(17, 24, 39);
    public static readonly SolidColorBrush MutedBrush = Frozen(107, 114, 128);
    public static readonly SolidColorBrush AccentBrush = Frozen(37, 99, 235);
    public static readonly SolidColorBrush AccentHoverBrush = Frozen(29, 78, 216);
    public static readonly SolidColorBrush GreenBrush = Frozen(22, 163, 74);
    public static readonly SolidColorBrush OrangeBrush = Frozen(217, 119, 6);
    public static readonly SolidColorBrush RedBrush = Frozen(220, 38, 38);

    private static SolidColorBrush Frozen(byte r, byte g, byte b)
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
            var bmp = new BitmapImage(new Uri($"pack://application:,,,/Resources/{file}", UriKind.Absolute));
            img.Source = bmp;
        }
        catch { }
        return img;
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

    public static Border Card(UIElement child, double padding = 20, double radius = 16)
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
        TextWrapping = TextWrapping.Wrap
    };

    public static Button PrimaryButton(string label, Action onClick, bool enabled = true)
    {
        var text = new TextBlock
        {
            Text = label,
            FontFamily = AppFont,
            FontSize = 13.5,
            FontWeight = FontWeights.SemiBold,
            Foreground = Brushes.White,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center
        };
        var border = new Border
        {
            Background = enabled ? AccentBrush : MutedBrush,
            CornerRadius = new CornerRadius(10),
            Padding = new Thickness(16, 9, 16, 9),
            Child = text,
            Opacity = enabled ? 1.0 : 0.55
        };
        var btn = BareButton(border, onClick, enabled);
        if (enabled)
        {
            btn.MouseEnter += (_, _) => border.Background = AccentHoverBrush;
            btn.MouseLeave += (_, _) => border.Background = AccentBrush;
        }
        return btn;
    }

    public static Button SecondaryButton(string label, Action onClick, bool enabled = true, Brush? textBrush = null)
    {
        var text = new TextBlock
        {
            Text = label,
            FontFamily = AppFont,
            FontSize = 13,
            FontWeight = FontWeights.Medium,
            Foreground = textBrush ?? TextBrush,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center
        };
        var border = new Border
        {
            Background = SurfaceBrush,
            BorderBrush = LineBrush,
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(10),
            Padding = new Thickness(14, 8, 14, 8),
            Child = text,
            Opacity = enabled ? 1.0 : 0.5
        };
        var btn = BareButton(border, onClick, enabled);
        if (enabled)
        {
            btn.MouseEnter += (_, _) => border.Background = MutedBgBrush;
            btn.MouseLeave += (_, _) => border.Background = SurfaceBrush;
        }
        return btn;
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
            Foreground = selected ? AccentBrush : MutedBrush
        };
        var border = new Border
        {
            Background = selected ? SoftBrush : SurfaceBrush,
            BorderBrush = selected ? AccentBrush : LineBrush,
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(999),
            Padding = new Thickness(12, 5, 12, 5),
            Margin = new Thickness(0, 0, 6, 6),
            Child = text
        };
        return BareButton(border, onClick, true);
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
            Background = SurfaceBrush,
            BorderBrush = LineBrush,
            BorderThickness = new Thickness(1),
            Padding = new Thickness(10, 8, 10, 8),
            AcceptsReturn = multiline,
            TextWrapping = multiline ? TextWrapping.Wrap : TextWrapping.NoWrap,
            VerticalScrollBarVisibility = multiline ? ScrollBarVisibility.Auto : ScrollBarVisibility.Hidden
        };
        if (!string.IsNullOrEmpty(placeholder))
            box.ToolTip = placeholder;
        return box;
    }
}
