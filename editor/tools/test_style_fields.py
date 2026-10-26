"""Noise enum parsing must preserve constructor boundaries and reject skipped declarations."""
from extract_style_fields import enum_choices, extract

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
    public IrisGeneratorStyle(NoiseStyle s) { this.style = s; }
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
print('PASS: noise enum boundaries, defaults and factory assumptions reject changed declarations')
