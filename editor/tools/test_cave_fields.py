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
profile='\n'.join(lines).encode()
owner=b'    @Description("Profile")\n    private IrisCaveProfile caveProfile = new IrisCaveProfile();'
owners={name:owner for name in ['dimensions','regions','biomes']}
data=extract(profile,owners).decode('ascii')
assert 'enabled.default=false\n' in data and 'detailWeight.default=0.35\n' in data
assert 'detailWeight.maximum=' not in data


def rejects(source=profile,contexts=owners):
    try:
        extract(source,contexts)
    except ValueError:
        return
    raise AssertionError('Changed cave declaration was silently accepted')


rejects(source=profile.replace(b'boolean enabled = false',b'boolean enabled = Boolean.FALSE'))
rejects(source=profile.replace(b'int sampleStep',b'long sampleStep'))
rejects(source=profile.replace(b'@NoArgsConstructor',b''))
rejects(contexts=dict(owners,regions=owner.replace(b'new IrisCaveProfile()',b'null')))
rejects(contexts={'dimensions':owner,'biomes':owner})
print('PASS: cave extraction covers owner defaults, exact scalar types and optional annotation bounds')
