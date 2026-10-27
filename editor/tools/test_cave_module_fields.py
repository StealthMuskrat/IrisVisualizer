"""Reject source drift in module lists, scalar annotations and nested defaults."""
from extract_cave_module_fields import extract

profile = b'''    @ArrayType(type = IrisCaveFieldModule.class, min = 1)
    @Description("Layers")
    private KList<IrisCaveFieldModule> modules = new KList<>();'''
module = b'''@NoArgsConstructor
public class IrisCaveFieldModule {
    @Description("Distribution")
    private IrisGeneratorStyle style = NoiseStyle.CELLULAR_IRIS_DOUBLE.style();
    @MinNumber(0)
    @Description("Contribution")
    private double weight = 1;
    @MinNumber(-1)
    @MaxNumber(1)
    @Description("Cutoff")
    private double threshold = 0;
    @Description("Vertical bounds")
    private IrisRange verticalRange = new IrisRange(0, 384);
    @Description("Invert")
    private boolean invert = false;
}'''
range_source = b'''@NoArgsConstructor @AllArgsConstructor
public class IrisRange {
    @Description("Minimum")
    private double min = 16;
    @Description("Maximum")
    private double max = 32;
}'''
style = b'public IrisGeneratorStyle(NoiseStyle s) { this.style = s; }'
data = extract(profile,module,range_source,style).decode('ascii')
for item in ['minimumEntries=1\n','moduleDefault={}\n','listDefault=[]\n','weight.minimum=0\n','threshold.minimum=-1\n','threshold.maximum=1\n',
             'invert.default=false\n','verticalRange.min.default=0\n','verticalRange.max.default=384\n','range.min.default=16\n','range.max.default=32\n','style.default=CELLULAR_IRIS_DOUBLE\n']:
    assert item in data
assert 'weight.maximum=' not in data


def rejects(p=profile,m=module,r=range_source,s=style):
    try:
        extract(p,m,r,s)
    except ValueError:
        return
    raise AssertionError('Changed cave module source silently accepted')


rejects(p=profile.replace(b'min = 1',b'min = unknown'))
rejects(p=profile.replace(b'new KList<>()',b'new KList<>(new IrisCaveFieldModule())'))
rejects(m=module.replace(b'@NoArgsConstructor',b''))
rejects(m=module.replace(b'double weight',b'int weight'))
rejects(m=module.replace(b'boolean invert',b'double invert'))
rejects(m=module.replace(b'invert = false',b'invert = Boolean.FALSE'))
rejects(m=module.replace(b'NoiseStyle.CELLULAR_IRIS_DOUBLE.style()',b'new IrisGeneratorStyle(NoiseStyle.CELLULAR_IRIS_DOUBLE)'))
rejects(m=module.replace(b'new IrisRange(0, 384)',b'new IrisRange()'))
rejects(r=range_source.replace(b'@AllArgsConstructor',b''))
rejects(r=range_source.replace(b'min = 16',b'max = 16').replace(b'max = 32',b'min = 32'))
rejects(r=range_source.replace(b'@Description("Minimum")',b'@MinNumber(0)\n    @Description("Minimum")'))
rejects(s=style.replace(b'this.style = s;',b'this.style = NoiseStyle.STATIC;'))
print('PASS: cave module list/scalar declarations and nested style/range defaults reject drift')
