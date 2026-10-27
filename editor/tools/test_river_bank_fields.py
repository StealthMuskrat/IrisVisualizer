"""Bank controls must reject drift in parent/default/native range and style contracts."""
from extract_river_bank_fields import extract

geometry=b'@NoArgsConstructor\n@Description("Bank geometry")\nprivate IrisRiverBank3DConfig banks3D = new IrisRiverBank3DConfig();'
fields=[('enabled','boolean','true',None,None),('seed','long','9007199254740993L',None,None),
        ('amplitude','double','8','0','32'),('horizontalScale','double','48','4','4096'),
        ('verticalScale','double','8','4','256'),('maximumOverhang','int','8','1','16')]
lines=['@NoArgsConstructor','public class IrisRiverBank3DConfig {']
for name,kind,default,minimum,maximum in fields:
    if minimum is not None:
        lines+=['    @MinNumber('+minimum+')','    @MaxNumber('+maximum+')']
    lines+=['    @Description("Setting")','    private '+kind+' '+name+' = '+default+';']
lines+=['    @Description("Bank density")','    private IrisGeneratorStyle densityStyle = new IrisGeneratorStyle(NoiseStyle.SIMPLEX);',
        '    public void validate() {','        if (densityStyle == null) { throw new IllegalArgumentException(); }','        validateStyle(densityStyle, 0);']
for name,kind,default,minimum,maximum in fields:
    if minimum is not None:
        lines+=['        requireRange("'+name+'", '+name+', '+minimum+'D, '+maximum+'D);']
lines+=['    }','    private static void validateStyle(IrisGeneratorStyle style, int depth) {',
        '        if (depth > 32 || style.getStyle() == null) { throw new IllegalArgumentException(); }']
for name,minimum,maximum in [('zoom','0.00001D','Double.MAX_VALUE'),('cellularFrequency','0D','Double.MAX_VALUE'),
                              ('cellularZoom','0.00001D','Double.MAX_VALUE'),('multiplier','0.00001D','Double.MAX_VALUE'),
                              ('exponent','0.01562D','64D'),('cacheSize','0D','8192D')]:
    lines+=['        requireRange("densityStyle.'+name+'", style.get'+name[0].upper()+name[1:]+'(), '+minimum+', '+maximum+');']
lines+=['        if (style.getFracture() != null) { validateStyle(style.getFracture(), depth + 1); }','    }',
        '    private static void requireRange(String name, double value, double minimum, double maximum) {',
        '        if (!Double.isFinite(value) || value < minimum || value > maximum) { throw new IllegalArgumentException(); }','    }','}']
bank='\n'.join(lines).encode()
data=extract(geometry,bank).decode()
for token in ['banks3D.default={}\n','seed.default=9007199254740993\n','maximumOverhang.type=int\n',
              'densityStyle.default=SIMPLEX\n','style.cellularZoom.minimum=0.00001\n','style.cacheSize.maximum=8192\n']:
    assert token in data


def rejects(g=geometry,b=bank):
    try:
        extract(g,b)
    except (ValueError,ArithmeticError):
        return
    raise AssertionError('Changed bank contract silently accepted')


rejects(g=geometry.replace(b'new IrisRiverBank3DConfig()',b'null'))
rejects(g=geometry.replace(b'@NoArgsConstructor',b''))
rejects(b=bank.replace(b'@NoArgsConstructor',b''))
rejects(b=bank.replace(b'boolean enabled = true',b'boolean enabled = Boolean.TRUE'))
rejects(b=bank.replace(b'int maximumOverhang',b'double maximumOverhang'))
rejects(b=bank.replace(b'long seed',b'double seed'))
rejects(b=bank.replace(b'requireRange("amplitude", amplitude, 0D, 32D)',b'requireRange("amplitude", amplitude, 0D, 33D)'))
rejects(b=bank.replace(b'@MinNumber(4)',b'@MinNumber(8)'))
rejects(b=bank.replace(b'!Double.isFinite(value) || ',b''))
rejects(b=bank.replace(b'new IrisGeneratorStyle(NoiseStyle.SIMPLEX)',b'new IrisGeneratorStyle()'))
rejects(b=bank.replace(b'validateStyle(densityStyle, 0);',b''))
rejects(b=bank.replace(b'if (densityStyle == null)',b'if (false)'))
rejects(b=bank.replace(b'depth > 32',b'depth > 64'))
rejects(b=bank.replace(b'depth + 1',b'depth'))
rejects(b=bank.replace(b'style.getCellularZoom()',b'style.getZoom()'))
print('PASS: bank defaults, exact seed/type extraction, native/annotation bounds and strict density-style validation reject drift')
