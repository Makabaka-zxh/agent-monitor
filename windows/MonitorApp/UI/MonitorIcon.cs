using System.Windows;
using System.Windows.Media;

namespace MonitorApp.UI;

/// <summary>
/// Code-owned 24x24 rounded line icons matching Android NativeUi.Icon 1:1 for Monitor family consistency.
/// </summary>
public sealed class MonitorIcon : FrameworkElement
{
    public string GlyphName { get; set; }
    public Brush Ink { get; set; }
    public double Stroke { get; set; }

    public MonitorIcon(string glyphName, Brush ink, double size = 18, double stroke = 1.8)
    {
        GlyphName = glyphName;
        Ink = ink;
        Width = size;
        Height = size;
        Stroke = stroke;
        IsHitTestVisible = false;
        SnapsToDevicePixels = true;
    }

    protected override void OnRender(DrawingContext dc)
    {
        base.OnRender(dc);
        var size = Math.Min(ActualWidth > 0 ? ActualWidth : Width, ActualHeight > 0 ? ActualHeight : Height);
        if (size <= 0) return;

        var scale = size / 24.0;
        var ox = ((ActualWidth > 0 ? ActualWidth : Width) - size) / 2.0;
        var oy = ((ActualHeight > 0 ? ActualHeight : Height) - size) / 2.0;

        dc.PushTransform(new TranslateTransform(ox, oy));
        dc.PushTransform(new ScaleTransform(scale, scale));

        var pen = new Pen(Ink, Stroke)
        {
            StartLineCap = PenLineCap.Round,
            EndLineCap = PenLineCap.Round,
            LineJoin = PenLineJoin.Round
        };

        switch (GlyphName)
        {
            case "tasks":
                dc.DrawRoundedRectangle(null, pen, new Rect(4.5, 3.5, 15, 17), 3, 3);
                Line(dc, pen, 8, 8, 8.2, 8); Line(dc, pen, 11.5, 8, 16, 8);
                Line(dc, pen, 8, 12, 8.2, 12); Line(dc, pen, 11.5, 12, 16, 12);
                Line(dc, pen, 8, 16, 8.2, 16); Line(dc, pen, 11.5, 16, 14.5, 16);
                break;

            case "devices":
            case "monitor":
                dc.DrawRoundedRectangle(null, pen, new Rect(3.5, 4, 17, 12), 2.5, 2.5);
                Line(dc, pen, 12, 16, 12, 20);
                Line(dc, pen, 8.5, 20, 15.5, 20);
                break;

            case "usage":
                dc.DrawRoundedRectangle(null, pen, new Rect(4, 13, 3.5, 7), 1.4, 1.4);
                dc.DrawRoundedRectangle(null, pen, new Rect(10.25, 8.5, 3.5, 11.5), 1.4, 1.4);
                dc.DrawRoundedRectangle(null, pen, new Rect(16.5, 4, 3.5, 16), 1.4, 1.4);
                break;

            case "account":
            case "user":
                dc.DrawEllipse(null, pen, new Point(12, 8), 3.5, 3.5);
                DrawGeometry(dc, pen, "M 5,19.5 C 5,14 19,14 19,19.5");
                break;

            case "search":
                dc.DrawEllipse(null, pen, new Point(10.5, 10.5), 6.2, 6.2);
                Line(dc, pen, 15.2, 15.2, 19.8, 19.8);
                break;

            case "scan":
                DrawGeometry(dc, pen, "M 8,4 L 6,4 Q 4,4 4,6 L 4,8 M 16,4 L 18,4 Q 20,4 20,6 L 20,8 M 20,16 L 20,18 Q 20,20 18,20 L 16,20 M 8,20 L 6,20 Q 4,20 4,18 L 4,16 M 7,12 L 17,12");
                break;

            case "refresh":
                DrawGeometry(dc, pen, "M 19.5,9 A 8,8 0 1 0 18.5,16.5 M 20,4.5 L 20,9.2 L 15.3,9.2");
                break;

            case "settings":
                Line(dc, pen, 4, 7.5, 7, 7.5); Line(dc, pen, 12, 7.5, 20, 7.5);
                dc.DrawEllipse(null, pen, new Point(9.5, 7.5), 2.3, 2.3);
                Line(dc, pen, 4, 16.5, 12, 16.5); Line(dc, pen, 17, 16.5, 20, 16.5);
                dc.DrawEllipse(null, pen, new Point(14.5, 16.5), 2.3, 2.3);
                break;

            case "bell":
                DrawGeometry(dc, pen, "M 6,10 C 6,4 18,4 18,10 L 18,14.5 L 19.5,16.8 L 4.5,16.8 L 6,14.5 Z M 10,17.2 A 2.4,2.4 0 0 0 14,17.2");
                break;

            case "archive":
                dc.DrawRoundedRectangle(null, pen, new Rect(3.5, 4, 17, 4.2), 1.4, 1.4);
                DrawGeometry(dc, pen, "M 5.2,8.2 L 5.2,18 Q 5.2,19.8 7,19.8 L 17,19.8 Q 18.8,19.8 18.8,18 L 18.8,8.2 M 9.5,12.8 L 14.5,12.8");
                break;

            case "restore":
                DrawGeometry(dc, pen, "M 5.2,9 A 7.5,7.5 0 1 1 6.5,16.8 M 4.5,4.5 L 4.5,9.2 L 9.2,9.2 M 12,8.5 L 12,12.5 L 14.8,14");
                break;

            case "shield":
                DrawGeometry(dc, pen, "M 12,3.5 L 19.5,6.5 L 19,12.5 C 18.5,16.5 15.5,19 12,20.8 C 8.5,19 5.5,16.5 5,12.5 L 4.5,6.5 Z");
                break;

            case "link":
                DrawGeometry(dc, pen, "M 9.5,16 L 8,16 A 4,4 0 0 1 8,8 L 10,8 M 14.5,8 L 16,8 A 4,4 0 0 1 16,16 L 14,16 M 8.8,12 L 15.2,12");
                break;

            case "globe":
                dc.DrawEllipse(null, pen, new Point(12, 12), 8.2, 8.2);
                Line(dc, pen, 3.8, 12, 20.2, 12);
                DrawGeometry(dc, pen, "M 12,3.8 C 8.5,7.2 8.5,16.8 12,20.2 M 12,3.8 C 15.5,7.2 15.5,16.8 12,20.2");
                break;

            case "plus":
                Line(dc, pen, 12, 5.5, 12, 18.5);
                Line(dc, pen, 5.5, 12, 18.5, 12);
                break;

            case "back":
            case "chevron-left":
                DrawGeometry(dc, pen, "M 14.5,5.8 L 8.2,12 L 14.5,18.2");
                break;

            case "chevron":
            case "chevron-right":
                DrawGeometry(dc, pen, "M 9.5,5.8 L 15.8,12 L 9.5,18.2");
                break;

            case "chevron-up":
                DrawGeometry(dc, pen, "M 5.8,14.5 L 12,8.2 L 18.2,14.5");
                break;

            case "chevron-down":
                DrawGeometry(dc, pen, "M 5.8,9.5 L 12,15.8 L 18.2,9.5");
                break;

            case "check":
                DrawGeometry(dc, pen, "M 5,12.5 L 9.5,17 L 19,7.5");
                break;

            case "close":
                Line(dc, pen, 6.5, 6.5, 17.5, 17.5);
                Line(dc, pen, 17.5, 6.5, 6.5, 17.5);
                break;

            case "help":
                dc.DrawEllipse(null, pen, new Point(12, 12), 8.5, 8.5);
                DrawGeometry(dc, pen, "M 9.6,9.2 C 9.6,6.5 14.8,6.5 14.5,9.6 C 14.2,11.2 12,11.4 12,13.2");
                Line(dc, pen, 12, 16.5, 12, 16.7);
                break;

            case "logout":
                DrawGeometry(dc, pen, "M 10,4.5 L 6.2,4.5 Q 4.5,4.5 4.5,6.2 L 4.5,17.8 Q 4.5,19.5 6.2,19.5 L 10,19.5 M 10,12 L 19.5,12 M 16,8.5 L 19.5,12 L 16,15.5");
                break;

            case "copy":
                dc.DrawRoundedRectangle(null, pen, new Rect(8.5, 8.5, 11, 11), 2, 2);
                DrawGeometry(dc, pen, "M 15.5,8.5 L 15.5,6.2 Q 15.5,4.5 13.8,4.5 L 6.2,4.5 Q 4.5,4.5 4.5,6.2 L 4.5,13.8 Q 4.5,15.5 6.2,15.5 L 8.5,15.5");
                break;

            case "download":
                Line(dc, pen, 12, 4.5, 12, 15.2);
                DrawGeometry(dc, pen, "M 8,11.5 L 12,15.5 L 16,11.5 M 5,19.2 L 19,19.2");
                break;

            case "mini":
                dc.DrawRoundedRectangle(null, pen, new Rect(4, 5, 16, 14), 2.5, 2.5);
                dc.DrawRoundedRectangle(null, pen, new Rect(11.5, 10.5, 6.5, 6.5), 1.5, 1.5);
                break;

            case "pin":
                DrawGeometry(dc, pen, "M 9,4.5 L 15,4.5 L 14.2,10.5 L 17,13.2 L 7,13.2 L 9.8,10.5 Z M 12,13.2 L 12,20");
                break;

            case "minimize":
                Line(dc, pen, 6, 12, 18, 12);
                break;

            case "maximize":
                dc.DrawRoundedRectangle(null, pen, new Rect(6, 6, 12, 12), 1.5, 1.5);
                break;

            case "mac-minimize":
                Line(dc, pen, 6.2, 12, 17.8, 12);
                break;

            case "mac-zoom":
                DrawGeometry(dc, pen, "M 13.0,6.5 L 17.5,6.5 L 17.5,11.0 M 11.0,17.5 L 6.5,17.5 L 6.5,13.0");
                break;

            case "mac-restore":
                DrawGeometry(dc, pen, "M 13.2,6.2 L 13.2,10.8 L 17.8,10.8 M 10.8,17.8 L 10.8,13.2 L 6.2,13.2");
                break;

            case "mac-close":
                Line(dc, pen, 7.2, 7.2, 16.8, 16.8);
                Line(dc, pen, 16.8, 7.2, 7.2, 16.8);
                break;

            default:
                dc.DrawRoundedRectangle(null, pen, new Rect(4, 4, 16, 16), 4, 4);
                break;
        }

        dc.Pop();
        dc.Pop();
    }

    private static void Line(DrawingContext dc, Pen pen, double x1, double y1, double x2, double y2) =>
        dc.DrawLine(pen, new Point(x1, y1), new Point(x2, y2));

    private static void DrawGeometry(DrawingContext dc, Pen pen, string pathMarkup) =>
        dc.DrawGeometry(null, pen, Geometry.Parse(pathMarkup));
}
