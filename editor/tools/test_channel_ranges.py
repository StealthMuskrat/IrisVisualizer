"""Changed range constructor order/helper/zoom semantics must not silently materialize defaults."""
from extract_channel_ranges import extract

channel=b'''@NoArgsConstructor
    @Description("Width")
    private IrisStyledRange width = range(4D, 8D, 1024D);
    @Description("Depth")
    private IrisStyledRange depth = range(2D, 4D, 768D);
    private static IrisStyledRange range(double min, double max, double zoom) {
        IrisGeneratorStyle style = new IrisGeneratorStyle(NoiseStyle.IRIS).zoomed(zoom);
        return new IrisStyledRange(min, max, style);
    }'''
ranges=b'''@NoArgsConstructor
@AllArgsConstructor
    @Description("Minimum")
    private double min = 16;
    @Description("Maximum")
    private double max = 32;
    @Description("Style")
    private IrisGeneratorStyle style = new IrisGeneratorStyle(NoiseStyle.STATIC);'''
style=b'''public IrisGeneratorStyle(NoiseStyle s) { this.style = s; }
public IrisGeneratorStyle zoomed(double z) { this.zoom = z; return this; }'''
data=extract(channel,ranges,style).decode()
for token in ['width.min.default=4\n','depth.zoom.default=768\n','width.style.default=IRIS\n','min.default=16\n','style.default=STATIC\n']:
    assert token in data


def rejects(c=channel,r=ranges,s=style):
    try:
        extract(c,r,s)
    except ValueError:
        return
    raise AssertionError('Changed channel range defaults accepted')


rejects(c=channel.replace(b'@NoArgsConstructor',b''))
rejects(r=ranges.replace(b'@AllArgsConstructor',b''))
rejects(r=ranges.replace(b'private double min',b'private double max').replace(b'private double max = 32',b'private double min = 32'))
rejects(c=channel.replace(b'new IrisStyledRange(min, max, style)',b'new IrisStyledRange(max, min, style)'))
rejects(c=channel.replace(b'NoiseStyle.IRIS',b'NoiseStyle.STATIC'))
rejects(c=channel.replace(b'.zoomed(zoom)',b'.zoomed(1)'))
rejects(c=channel.replace(b'private IrisStyledRange width',b'private IrisRange width'))
rejects(r=ranges.replace(b'new IrisGeneratorStyle(NoiseStyle.STATIC)',b'new IrisGeneratorStyle()'))
rejects(s=style.replace(b'this.style = s',b'this.style = NoiseStyle.IRIS'))
rejects(s=style.replace(b'this.zoom = z',b'this.zoom = 1'))
rejects(s=style.replace(b'return this',b'return new IrisGeneratorStyle()'))
print('PASS: channel contextual ranges, class defaults, constructor order and style/zoom helper contracts reject drift')
