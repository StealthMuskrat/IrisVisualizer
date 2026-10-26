"""Terrain controls must reject drift between authoring annotations and native range checks."""
from extract_terrain3d_fields import extract

biome = b'    @Description("Optional terrain")\n    private IrisTerrain3D terrain3D = null;'
fields = [('enabled','boolean','true',None,None),('seed','long','9007199254740993L',None,None),
          ('amplitude','double','32','0','128'),('horizontalScale','double','96','8','4096'),
          ('verticalScale','double','24','8','4096'),('crackDepth','double','0','0','128'),
          ('crackWidth','double','4','0.25','64'),('crackScale','double','96','8','4096'),
          ('minimumSlope','double','0.15','0','16'),('slopeFade','double','0.35','0.001','16'),
          ('fluidClearance','double','8','0','128'),('fluidFade','double','24','1','128')]
lines = ['@NoArgsConstructor','public class IrisTerrain3D {']
for name,kind,default,minimum,maximum in fields:
    if minimum is not None:
        lines += ['    @MinNumber('+minimum+')','    @MaxNumber('+maximum+')']
    lines += ['    @Description("Setting")','    private '+kind+' '+name+' = '+default+';']
lines += ['    public void validate() {']
for name,kind,default,minimum,maximum in fields:
    if minimum is not None:
        lines += ['        requireRange("'+name+'", '+name+', '+minimum+'D, '+maximum+'D);']
lines += ['        validateStyle(densityStyle, "densityStyle", 0);','        validateStyle(crackStyle, "crackStyle", 0);']
lines += ['    }','    private static void requireRange(String name, double value, double minimum, double maximum) {',
          '        if (!Double.isFinite(value) || value < minimum || value > maximum) {',
          '            throw new IllegalArgumentException(name);','        }','    }','}']
lines[-1:-1] = ['    @Description("Density style")','    private IrisGeneratorStyle densityStyle = new IrisGeneratorStyle(NoiseStyle.SIMPLEX);',
               '    @Description("Crack style")','    private IrisGeneratorStyle crackStyle = new IrisGeneratorStyle(NoiseStyle.SIMPLEX);',
               '    private static void validateStyle(IrisGeneratorStyle style, String path, int depth) {',
               '        requireRange(path + ".zoom", style.getZoom(), 0.00001D, Double.MAX_VALUE);',
               '        requireRange(path + ".cellularFrequency", style.getCellularFrequency(), 0D, Double.MAX_VALUE);',
               '        requireRange(path + ".cellularZoom", style.getCellularZoom(), 0.00001D, Double.MAX_VALUE);',
               '        requireRange(path + ".exponent", style.getExponent(), 0.01562D, 64D);','    }']
terrain = '\n'.join(lines).encode()
data = extract(biome,terrain).decode('ascii')
assert 'seed.default=9007199254740993\n' in data and 'slopeFade.minimum=0.001\n' in data
assert 'densityStyle.default=SIMPLEX\n' in data and 'style.cellularZoom.minimum=0.00001\n' in data


def rejects(parent=biome,source=terrain):
    try:
        extract(parent,source)
    except ValueError:
        return
    raise AssertionError('Changed terrain contract was silently accepted')


rejects(source=terrain.replace(b'requireRange("amplitude", amplitude, 0D, 128D)',b'requireRange("amplitude", amplitude, 0D, 129D)'))
rejects(source=terrain.replace(b'@MinNumber(0.001)',b'@MinNumber(0.002)'))
rejects(source=terrain.replace(b'!Double.isFinite(value) || ',b''))
rejects(source=terrain.replace(b'@NoArgsConstructor',b''))
rejects(source=terrain.replace(b'boolean enabled = true',b'boolean enabled = Boolean.TRUE'))
rejects(parent=biome.replace(b'= null',b'= new IrisTerrain3D()'))
rejects(source=terrain.replace(b'new IrisGeneratorStyle(NoiseStyle.SIMPLEX)',b'new IrisGeneratorStyle()'))
rejects(source=terrain.replace(b'validateStyle(densityStyle, "densityStyle", 0);',b''))
rejects(source=terrain.replace(b'style.getCellularZoom()',b'style.getZoom()'))
print('PASS: terrain bounds match native validation, seed defaults stay exact, and changed contracts are rejected')
