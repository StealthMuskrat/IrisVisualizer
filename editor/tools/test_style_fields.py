"""Noise enum parsing must preserve constructor boundaries and reject skipped declarations."""
from extract_style_fields import ROOTS, enum_choices, extract

noise = b'private IrisGeneratorStyle style = NoiseStyle.IRIS.style();'
style = b'''    @Description("Base style")
    private NoiseStyle style = NoiseStyle.FLAT;
    @MinNumber(0.00001)
    @Description("Zoom")
    private double zoom = 1;
    @MinNumber(0.01562)
    @MaxNumber(64)
    @Description("Exponent")
    private double exponent = 1;
    @Description("Frequency")
    private double cellularFrequency = 0;
    @Description("Cell zoom")
    private double cellularZoom = 1;
    public IrisGeneratorStyle(NoiseStyle s) { this.style = s; }
    private CNG createNoCache(RNG rng) {
        if (!Double.isFinite(cellularFrequency) || cellularFrequency < 0D) {
            throw new IllegalArgumentException("cellularFrequency must be finite and nonnegative");
        }
        if (cellularFrequency > 0D) {
            requirePositiveFinite("cellularZoom", cellularZoom);
        }
        CNG cng = null;
        return cng;
    }
    private static void requirePositiveFinite(String field, double value) {
        if (!Double.isFinite(value) || value <= 0D || !Double.isFinite(1D / value)) {
            throw new IllegalArgumentException(field + " must be finite and positive");
        }
    }
'''
enum = b'''public enum NoiseStyle {
    @Description("Iris") IRIS(rng -> nested(")", (rng))),
    @Description("Flat") FLAT(rng -> factory('(', "escaped\\\"quote")),
    ;
    public IrisGeneratorStyle style() { return new IrisGeneratorStyle(this); }
}'''
assert enum_choices(enum.decode()) == {'IRIS':'Iris', 'FLAT':'Flat'}
result = extract(noise, style, enum).decode('ascii')
assert 'noiseDefault=IRIS\n' in result and 'objectDefault=FLAT\n' in result
assert 'choices=IRIS,FLAT\n' in result and 'zoom.minimum=1e-05\n' in result
assert 'cellularFrequency.minimum=0.0\n' in result and 'cellularZoom.runtimeRule=positiveFiniteReciprocalWhenActive\n' in result


def rejects(noise_source=noise, style_source=style, enum_source=enum):
    try:
        extract(noise_source, style_source, enum_source)
    except ValueError:
        return
    raise AssertionError('Changed noise declaration was silently accepted')


rejects(enum_source=enum.replace(b' FLAT(', b' IRIS('))
rejects(enum_source=enum.replace(b'@Description("Flat") ', b''))
rejects(enum_source=enum.replace(b'    ;', b'    NEW_STYLE(factory()),\n    ;'))
rejects(enum_source=enum.replace(b'nested(")", (rng)))', b'nested(")", (rng))'))
rejects(enum_source=enum.replace(b'new IrisGeneratorStyle(this)', b'new IrisGeneratorStyle()'))
rejects(noise_source=noise.replace(b'NoiseStyle.IRIS.style()', b'new IrisGeneratorStyle()'))
rejects(style_source=style.replace(b'NoiseStyle.FLAT', b'NoiseStyle.MISSING'))
rejects(style_source=style.replace(b'this.style = s;', b'this.style = NoiseStyle.FLAT;'))
rejects(style_source=style.replace(b'cellularFrequency < 0D', b'cellularFrequency <= 0D'))
rejects(style_source=style.replace(b'cellularFrequency > 0D', b'cellularFrequency >= 0D'))
rejects(style_source=style.replace(b' || !Double.isFinite(1D / value)', b''))
rejects(style_source=style.replace(b'value <= 0D', b'value < 0D'))
print('PASS: noise enum boundaries, defaults and factory assumptions reject changed declarations')

roots = {group: ''.join('    @Description("Placement")\n    private IrisGeneratorStyle '+name+' = NoiseStyle.IRIS.style();\n' for name in names).encode() for group,names in ROOTS.items()}
data = extract(noise,style,enum,roots).decode('ascii')
assert 'dimensions.regionStyle.default=IRIS\n' in data and 'biomes.childStyle.default=IRIS\n' in data
for changed in [roots['dimensions'].replace(b'NoiseStyle.IRIS',b'NoiseStyle.MISSING'), roots['dimensions'].replace(b'IrisGeneratorStyle regionStyle',b'OtherType regionStyle')]:
    try:
        extract(noise,style,enum,dict(roots,dimensions=changed))
    except ValueError:
        pass
    else:
        raise AssertionError('Changed root style default/type was accepted')
print('PASS: contextual style defaults and types are extracted for each permitted root field')
