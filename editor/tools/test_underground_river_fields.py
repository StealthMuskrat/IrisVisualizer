"""Reject underground parent, scalar and range-helper contract drift."""
from extract_underground_river_fields import ROOT, SOURCES, RANGES, extract

rivers=b'@NoArgsConstructor\n@Description("Underground")\nprivate IrisUndergroundRiverConfig underground = new IrisUndergroundRiverConfig();'
raws={}
for section,names in [('root',ROOT),('sources',SOURCES)]:
    lines=['@NoArgsConstructor']
    if section=='root':
        lines+=['@Description("Sources")','private IrisUndergroundRiverSourceConfig sources = new IrisUndergroundRiverSourceConfig();']
    for name in names:
        kind='boolean' if name in ['enabled','connectToExistingCaves'] else 'double' if name=='density' else 'int'
        if kind!='boolean':
            lines+=['    @MinNumber(0)','    @MaxNumber(8192)']
        lines+=['    @Description("Setting")','    private '+kind+' '+name+' = '+('true' if kind=='boolean' else '0.25D' if kind=='double' else '8')+';']
    raws[section]='\n'.join(lines).encode()
for name in RANGES:
    raws['root']+=('\n    @Description("Range")\n    private IrisStyledRange '+name+' = range(-48D, 50D, 1024D);').encode()
raws['root']+=b'\n    private static IrisStyledRange range(double min, double max, double zoom) { return new IrisStyledRange(min, max, new IrisGeneratorStyle(NoiseStyle.IRIS).zoomed(zoom)); }'
data=extract(rivers,raws['root'],raws['sources']).decode()
for token in ['underground.default={}\n','sources.default={}\n','root.enabled.default=true\n','root.minimumRockCover.type=int\n','sources.density.default=0.25\n','underground.fluidLevel.min.default=-48\n','underground.headroom.style.default=IRIS\n']:
    assert token in data


def rejects(r=rivers,u=raws['root'],s=raws['sources']):
    try:
        extract(r,u,s)
    except (ValueError,ArithmeticError):
        return
    raise AssertionError('Changed underground contract accepted')


rejects(r=rivers.replace(b'new IrisUndergroundRiverConfig()',b'null'))
rejects(u=raws['root'].replace(b'new IrisUndergroundRiverSourceConfig()',b'null'))
rejects(u=raws['root'].replace(b'@NoArgsConstructor',b''))
rejects(s=raws['sources'].replace(b'@NoArgsConstructor',b''))
rejects(u=raws['root'].replace(b'boolean enabled = true',b'boolean enabled = Boolean.TRUE'))
rejects(u=raws['root'].replace(b'int tributaries',b'double tributaries'))
rejects(s=raws['sources'].replace(b'double density',b'Double density'))
rejects(s=raws['sources'].replace(b'@MinNumber(0)',b''))
rejects(u=raws['root'].replace(b'@MaxNumber(8192)',b''))
rejects(u=raws['root'].replace(b'new IrisStyledRange(min, max,',b'new IrisStyledRange(max, min,'))
rejects(u=raws['root'].replace(b'NoiseStyle.IRIS',b'NoiseStyle.STATIC'))
rejects(u=raws['root'].replace(b'.zoomed(zoom)',b'.zoomed(1)'))
rejects(u=raws['root'].replace(b'private IrisStyledRange fluidLevel',b'private IrisRange fluidLevel'))
print('PASS: underground parents, primitive defaults/types/bounds and contextual range helper contracts reject drift')
