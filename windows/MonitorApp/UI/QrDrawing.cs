using System.Windows;
using System.Windows.Media;
using QRCoder;

namespace MonitorApp.UI;

public static class QrDrawing
{
    public static ImageSource? CreateImage(string payload)
    {
        if (string.IsNullOrEmpty(payload) || payload.Length > 1024) return null;
        using var generator = new QRCodeGenerator();
        using var data = generator.CreateQrCode(payload, QRCodeGenerator.ECCLevel.M);
        var matrix = data.ModuleMatrix;
        var count = matrix.Count;
        if (count <= 0) return null;

        var group = new DrawingGroup();
        RenderOptions.SetEdgeMode(group, EdgeMode.Aliased);
        group.Children.Add(new GeometryDrawing(
            Brushes.White,
            null,
            new RectangleGeometry(new Rect(0, 0, count, count))));

        var darkGeometry = new StreamGeometry();
        using (var ctx = darkGeometry.Open())
        {
            for (var y = 0; y < count; y++)
            {
                var row = matrix[y];
                for (var x = 0; x < count; x++)
                {
                    if (!row[x]) continue;
                    ctx.BeginFigure(new Point(x, y), isFilled: true, isClosed: true);
                    ctx.LineTo(new Point(x + 1, y), isStroked: false, isSmoothJoin: false);
                    ctx.LineTo(new Point(x + 1, y + 1), isStroked: false, isSmoothJoin: false);
                    ctx.LineTo(new Point(x, y + 1), isStroked: false, isSmoothJoin: false);
                }
            }
        }
        darkGeometry.Freeze();
        var ink = new SolidColorBrush(Color.FromRgb(17, 24, 39));
        ink.Freeze();
        group.Children.Add(new GeometryDrawing(ink, null, darkGeometry));
        group.Freeze();

        var img = new DrawingImage(group);
        img.Freeze();
        return img;
    }
}
