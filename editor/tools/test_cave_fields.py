"""Source extraction must cover every declared editable scalar and every profile owner."""
from extract_cave_fields import FIELDS, extract

booleans={'enabled':'false','adaptiveSampling':'true','allowSurfaceBreak':'true','allowFluid':'true','fluidRequiresFloor':'true','allowLava':'true'}
integers={'verticalEdgeFade','sampleStep','adaptiveSampleStep','surfaceClearance','surfaceBreakDepth','objectMinDepthBelowSurface','anchorScanStep','anchorSearchAttempts','fluidMinDepthBelowSurface'}
lines=['@NoArgsConstructor','public class IrisCaveProfile {']
for field in FIELDS:
    kind='boolean' if field in booleans else 'int' if field in integers else 'double'
    if kind!='boolean':
        lines+=['    @MinNumber(0)']
    lines+=['    @Description("Cave setting")','    private '+kind+' '+field+' = '+booleans.get(field,'1' if kind=='int' else '0.35')+';']
lines+=['}']
lines[-1:-1]=['    @Description("Base")','    private IrisGeneratorStyle baseDensityStyle = NoiseStyle.CELLULAR_IRIS_DOUBLE.style();',
             '    @Description("Detail")','    private IrisGeneratorStyle detailDensityStyle = new IrisGeneratorStyle(NoiseStyle.SIMPLEX);',
             '    @Description("Warp")','    private IrisGeneratorStyle warpStyle = new IrisGeneratorStyle(NoiseStyle.FLAT);',
             '    @Description("Break")','    private IrisGeneratorStyle surfaceBreakStyle = new IrisGeneratorStyle(NoiseStyle.SIMPLEX).zoomed(0.08);']
profile='\n'.join(lines).encode()
style=b'public IrisGeneratorStyle(NoiseStyle s) { this.style = s; } public IrisGeneratorStyle zoomed(double z) { this.zoom = z; return this; }'
owner=b'    @Description("Profile")\n    private IrisCaveProfile caveProfile = new IrisCaveProfile();'
owners={name:owner for name in ['dimensions','regions','biomes']}
data=extract(profile,owners,style).decode('ascii')
assert 'enabled.default=false\n' in data and 'detailWeight.default=0.35\n' in data
assert 'detailWeight.maximum=' not in data
assert 'surfaceBreakStyle.zoomOverride=0.08\n' in data and 'warpStyle.default=FLAT\n' in data


def rejects(source=profile,contexts=owners,style_source=style):
    try:
        extract(source,contexts,style_source)
    except ValueError:
        return
    raise AssertionError('Changed cave declaration was silently accepted')


rejects(source=profile.replace(b'boolean enabled = false',b'boolean enabled = Boolean.FALSE'))
rejects(source=profile.replace(b'int sampleStep',b'long sampleStep'))
rejects(source=profile.replace(b'@NoArgsConstructor',b''))
rejects(contexts=dict(owners,regions=owner.replace(b'new IrisCaveProfile()',b'null')))
rejects(contexts={'dimensions':owner,'biomes':owner})
rejects(style_source=style.replace(b'this.zoom = z;',b'this.zoom = 1;'))
rejects(source=profile.replace(b'.zoomed(0.08)',b'.zoomed(unknown)'))
rejects(source=profile.replace(b'new IrisGeneratorStyle(NoiseStyle.FLAT)',b'new IrisGeneratorStyle()'))
print('PASS: cave extraction covers owner defaults, exact scalar types and optional annotation bounds')
