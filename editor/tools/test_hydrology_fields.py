"""Reject changes to explicit hydrology parents, disabled planning and routing scalar contracts."""
from extract_hydrology_fields import ROUTING, INTEGERS, extract

dimension=b'@NoArgsConstructor\n@Description("Hydrology")\nprivate IrisHydrology hydrology = new IrisHydrology();'
hydrology=b'@NoArgsConstructor\n@Description("Rivers")\nprivate IrisRiverHydrology rivers = new IrisRiverHydrology();'
rivers=b'''@NoArgsConstructor
@Description("Disabled default")
private boolean enabled = false;
@Description("Routing")
private IrisRiverRoutingConfig routing = new IrisRiverRoutingConfig();'''
lines=['@NoArgsConstructor','public class IrisRiverRoutingConfig {']
for name in ROUTING:
    kind='int' if name in INTEGERS else 'boolean' if name=='oceanOutlets' else 'double'
    if kind!='boolean': lines+=['    @MinNumber(0)','    @MaxNumber(8192)']
    lines+=['    @Description("Routing setting")','    private '+kind+' '+name+' = '+('true' if kind=='boolean' else '64' if kind=='int' else '1.5D')+';']
lines+=['}']; routing='\n'.join(lines).encode()
data=extract(dimension,hydrology,rivers,routing).decode('ascii')
for item in ['hydrology.default={}\n','rivers.default={}\n','routing.default={}\n','rivers.enabled.default=false\n',
             'routing.sampleSpacing.type=int\n','routing.oceanOutlets.default=true\n','routing.valleyPreference.default=1.5\n']:
    assert item in data
assert 'routing.oceanOutlets.minimum=' not in data


def rejects(d=dimension,h=hydrology,r=rivers,s=routing):
    try:
        extract(d,h,r,s)
    except ValueError:
        return
    raise AssertionError('Changed hydrology source silently accepted')


rejects(d=dimension.replace(b'@NoArgsConstructor',b''))
rejects(d=dimension.replace(b'new IrisHydrology()',b'null'))
rejects(h=hydrology.replace(b'new IrisRiverHydrology()',b'null'))
rejects(r=rivers.replace(b'enabled = false',b'enabled = true'))
rejects(r=rivers.replace(b'new IrisRiverRoutingConfig()',b'null'))
rejects(s=routing.replace(b'int sampleSpacing',b'double sampleSpacing'))
rejects(s=routing.replace(b'double valleyPreference',b'Double valleyPreference'))
rejects(s=routing.replace(b'oceanOutlets = true',b'oceanOutlets = Boolean.TRUE'))
print('PASS: hydrology parent creation, disabled default and routing primitive types/defaults/bounds reject drift')
