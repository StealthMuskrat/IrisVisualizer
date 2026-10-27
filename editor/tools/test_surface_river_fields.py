"""Reject primitive, parent and annotation contract drift in the surface-river batch."""
from extract_surface_river_fields import FIELDS, INTS, CLASSES, extract

rivers=b'@NoArgsConstructor\n@Description("Surface")\nprivate IrisSurfaceRiverConfig surface = new IrisSurfaceRiverConfig();'
raws={}
for section,names in FIELDS.items():
    lines=['@NoArgsConstructor']
    if section=='root':
        for name,kind in CLASSES.items():
            lines+=['@Description("Section")','private '+kind+' '+name+' = new '+kind+'();']
    for name in names:
        kind='boolean' if name in ['enabled','exposeCutStrata'] else 'int' if name in INTS.get(section,set()) else 'double'
        if kind!='boolean':
            lines+=['    @MinNumber(-2048)','    @MaxNumber(8192)']
        lines+=['    @Description("Setting")','    private '+kind+' '+name+' = '+('true' if kind=='boolean' else '88' if kind=='int' else '0.5D')+';']
    raws[section]='\n'.join(lines).encode()


def run(parent=rivers,**changes):
    data=dict(raws,**changes)
    return extract(parent,*(data[name] for name in FIELDS))


data=run().decode()
for token in ['surface.default={}\n','root.enabled.default=true\n','banks.exposeCutStrata.type=boolean\n','sources.minimumElevation.type=int\n','sources.minimumElevation.minimum=-2048\n','channel.roughness.default=0.5\n']:
    assert token in data


def rejects(parent=rivers,**changes):
    try:
        run(parent,**changes)
    except (ValueError,ArithmeticError):
        return
    raise AssertionError('Changed surface-river contract accepted')


rejects(parent=rivers.replace(b'new IrisSurfaceRiverConfig()',b'null'))
for section,kind in CLASSES.items():
    rejects(root=raws['root'].replace(b'new '+kind.encode()+b'()',b'null'))
for section,source in raws.items():
    rejects(**{section:source.replace(b'@NoArgsConstructor',b'')})
for section,source in raws.items():
    for name in FIELDS[section]:
        kind='boolean' if name in ['enabled','exposeCutStrata'] else 'int' if name in INTS.get(section,set()) else 'double'
        rejects(**{section:source.replace(('private '+kind+' '+name).encode(),('private String '+name).encode())})
        if kind=='boolean':
            rejects(**{section:source.replace((name+' = true').encode(),(name+' = Boolean.TRUE').encode())})
        else:
            rejects(**{section:source.replace(b'@MinNumber(-2048)',b'')})
            rejects(**{section:source.replace(b'@MaxNumber(8192)',b'')})
print('PASS: surface-river parent/default/type/bound extraction rejects drift for every primitive control')
