"""Reject changed shaping parents/types/bounds and enum syntax/default contracts."""
from extract_surface_shaping_fields import FIELDS, INTS, extract

surface=b'''@NoArgsConstructor
@Description("Erosion")
private IrisSurfaceRiverErosionConfig erosion = new IrisSurfaceRiverErosionConfig();
@Description("Bed")
private IrisSurfaceRiverBedConfig bed = new IrisSurfaceRiverBedConfig();'''
banks=b'@NoArgsConstructor\n@Description("Excavation")\nprivate IrisRiverExcavationConfig excavation = new IrisRiverExcavationConfig();'
raws={}
for section,names in FIELDS.items():
    lines=['@NoArgsConstructor']
    for name in names:
        kind='boolean' if name in ['enabled','allowGravityBlocks'] else 'int' if name in INTS else 'double'
        if kind!='boolean':
            lines+=['    @MinNumber(0)','    @MaxNumber(8192)']
        lines+=['    @Description("Setting")','    private '+kind+' '+name+' = '+('true' if kind=='boolean' else '8' if kind=='int' else '0.5D')+';']
    raws[section]='\n'.join(lines).encode()
raws['erosion']+=b'\n    @Description("Blend")\n    private IrisRiverBlendStyle style = IrisRiverBlendStyle.SMOOTH;\n    @Description("Bed")\n    private IrisRiverBedProfile bedProfile = IrisRiverBedProfile.BOWL;'
blend=b'public enum IrisRiverBlendStyle { @Description("Smooth") SMOOTH, @Description("Cliff") CLIFF }'
profile=b'public enum IrisRiverBedProfile { @Description("Bowl") BOWL, @Description("Flat") FLAT }'


def run(s=surface,b=banks,e=raws['erosion'],d=raws['bed'],x=raws['banks.excavation'],v=blend,p=profile):
    return extract(s,b,e,d,x,v,p)


data=run().decode()
for token in ['erosion.default={}\n','bed.default={}\n','banks.excavation.default={}\n','erosion.style.default=SMOOTH\n','erosion.style.choices=SMOOTH,CLIFF\n','erosion.bedProfile.choices=BOWL,FLAT\n','banks.excavation.maximumDepth.type=int\n']:
    assert token in data


def rejects(**changes):
    try:
        run(**changes)
    except (ValueError,ArithmeticError):
        return
    raise AssertionError('Changed surface shaping contract accepted')


rejects(s=surface.replace(b'new IrisSurfaceRiverErosionConfig()',b'null'))
rejects(s=surface.replace(b'new IrisSurfaceRiverBedConfig()',b'null'))
rejects(b=banks.replace(b'new IrisRiverExcavationConfig()',b'null'))
for key,source in [('s',surface),('b',banks),('e',raws['erosion']),('d',raws['bed']),('x',raws['banks.excavation'])]:
    rejects(**{key:source.replace(b'@NoArgsConstructor',b'')})
rejects(e=raws['erosion'].replace(b'boolean enabled = true',b'boolean enabled = Boolean.TRUE'))
rejects(x=raws['banks.excavation'].replace(b'int maximumDepth',b'double maximumDepth'))
rejects(d=raws['bed'].replace(b'@MinNumber(0)',b''))
rejects(e=raws['erosion'].replace(b'@MaxNumber(8192)',b''))
rejects(e=raws['erosion'].replace(b'IrisRiverBlendStyle style',b'String style'))
rejects(e=raws['erosion'].replace(b'IrisRiverBedProfile.BOWL',b'IrisRiverBedProfile.UNKNOWN'))
rejects(v=blend.replace(b'@Description("Smooth") SMOOTH,',b''))
rejects(v=blend.replace(b'CLIFF',b'SMOOTH'))
rejects(p=profile.replace(b'BOWL,',b'BOWL;'))
rejects(p=profile.replace(b'@Description("Flat")',b''))
print('PASS: shaping defaults/types/bounds and exact erosion enum syntax/defaults reject drift')
