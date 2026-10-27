"""Reject changed parent, primitive, bound and aspect-scope contracts."""
from extract_river_passage_fields import CHANNEL, DROPS, DROP_INTS, extract

geometry=b'''@NoArgsConstructor
@Description("Underground")
private IrisRiverChannelShapeConfig underground = new IrisRiverChannelShapeConfig();
@Description("Grottos")
private IrisRiverChannelShapeConfig grottos = new IrisRiverChannelShapeConfig();
@Description("Drops")
private IrisRiverDropShapeConfig drops = new IrisRiverDropShapeConfig();'''


def source(names,ints):
    lines=['@NoArgsConstructor']
    for name in names:
        kind='int' if name in ints else 'double'
        help_text='Applies to grottos only.' if name.startswith('aspect') else 'Shape setting'
        lines+=['    @MinNumber(0)','    @MaxNumber(128)','    @Description("'+help_text+'")','    private '+kind+' '+name+' = '+('11' if kind=='int' else '0.24D')+';']
    return '\n'.join(lines).encode()


channel=source(CHANNEL,{'roughnessWavelength'})
drops=source(DROPS,DROP_INTS)
data=extract(geometry,channel,drops).decode()
for token in ['underground.default={}\n','grottos.default={}\n','drops.default={}\n','grottos.roughnessWavelength.type=int\n','drops.maximumBasinDepth.type=int\n','underground.aspectRange.readOnlyReason=']:
    assert token in data
assert 'grottos.aspectRange.readOnlyReason=' not in data


def rejects(g=geometry,c=channel,d=drops):
    try:
        extract(g,c,d)
    except (ValueError,ArithmeticError):
        return
    raise AssertionError('Changed passage contract accepted')


for kind in [b'IrisRiverChannelShapeConfig',b'IrisRiverDropShapeConfig']:
    rejects(g=geometry.replace(b'new '+kind+b'()',b'null'))
rejects(c=channel.replace(b'@NoArgsConstructor',b''))
rejects(d=drops.replace(b'@NoArgsConstructor',b''))
rejects(c=channel.replace(b'int roughnessWavelength',b'double roughnessWavelength'))
rejects(c=channel.replace(b'double bedRoughness',b'Double bedRoughness'))
rejects(d=drops.replace(b'int maximumBasinDepth',b'double maximumBasinDepth'))
rejects(d=drops.replace(b'@MaxNumber(128)',b''))
rejects(c=channel.replace(b'Applies to grottos only.',b'Applies everywhere.'))
print('PASS: passage parent defaults, primitive types/bounds and grotto-only aspect scope reject drift')
