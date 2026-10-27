"""Reject drift in contextual/class range defaults, annotation bounds and constructors."""
from extract_cave_threshold_fields import extract

profile = b'''    @Description("Cutoff")
    private IrisStyledRange densityThreshold = new IrisStyledRange(-0.2, 0.2, NoiseStyle.CELLULAR_IRIS_DOUBLE.style());'''
styled = b'''@NoArgsConstructor @AllArgsConstructor
public class IrisStyledRange {
    @Description("Minimum")
    private double min = 16;
    @Description("Maximum")
    private double max = 32;
    @Description("Distribution")
    private IrisGeneratorStyle style = new IrisGeneratorStyle(NoiseStyle.STATIC);
}'''
style = b'public IrisGeneratorStyle(NoiseStyle s) { this.style = s; }'
data = extract(profile, styled, style).decode('ascii')
for item in ['context.min.default=-0.2\n', 'context.max.default=0.2\n', 'context.style.default=CELLULAR_IRIS_DOUBLE\n',
             'min.default=16\n', 'max.default=32\n', 'style.default=STATIC\n']:
    assert item in data


def rejects(p=profile, r=styled, s=style):
    try:
        extract(p, r, s)
    except ValueError:
        return
    raise AssertionError('Changed cave threshold source silently accepted')


rejects(p=profile.replace(b'new IrisStyledRange(-0.2, 0.2, NoiseStyle.CELLULAR_IRIS_DOUBLE.style())', b'new IrisStyledRange()'))
rejects(p=profile.replace(b'NoiseStyle.CELLULAR_IRIS_DOUBLE.style()', b'new IrisGeneratorStyle(NoiseStyle.STATIC)'))
rejects(r=styled.replace(b'@AllArgsConstructor', b''))
rejects(r=styled.replace(b'private double min', b'private int min'))
rejects(r=styled.replace(b'min = 16', b'max = 16').replace(b'max = 32', b'min = 32'))
rejects(r=styled.replace(b'@Description("Minimum")', b'@MinNumber(0)\n    @Description("Minimum")'))
rejects(r=styled.replace(b'new IrisGeneratorStyle(NoiseStyle.STATIC)', b'NoiseStyle.STATIC.style()'))
rejects(r=styled.replace(b'new IrisGeneratorStyle(NoiseStyle.STATIC)', b'new IrisGeneratorStyle(NoiseStyle.STATIC).zoomed(2)'))
rejects(r=styled.replace(b'\n}', b'\n    private double future = 1;\n}'))
rejects(s=style.replace(b'this.style = s;', b'this.style = NoiseStyle.STATIC;'))
print('PASS: threshold constructor order, contextual/class defaults, annotations and style constructor reject drift')
