"""Reject lost endpoint defaults, constructor assignments and primitive/type/bound drift."""
from extract_surface_pond_fields import extract

surface=b'@NoArgsConstructor\n@Description("Ponds")\nprivate IrisSurfaceRiverPondsConfig ponds = new IrisSurfaceRiverPondsConfig();'
ponds=b'''@NoArgsConstructor
@Description("Source")
private IrisSurfaceRiverPondConfig source = new IrisSurfaceRiverPondConfig(6, 12, 3);
@Description("Terminal")
private IrisSurfaceRiverPondConfig terminal = new IrisSurfaceRiverPondConfig(4, 7, 3);'''
lines=['@NoArgsConstructor','    @Description("Dig pond")','    private boolean enabled = true;']
for name,value,maximum in [('minimumRadius',6,32),('maximumRadius',12,32),('depth',3,8)]:
    lines+=['    @MinNumber(1)','    @MaxNumber('+str(maximum)+')','    @Description("Setting")','    private int '+name+' = '+str(value)+';']
lines+=['    public IrisSurfaceRiverPondConfig(int minimumRadius, int maximumRadius, int depth) {',
        '        this.minimumRadius = minimumRadius;','        this.maximumRadius = maximumRadius;','        this.depth = depth;','    }']
pond='\n'.join(lines).encode()
data=extract(surface,ponds,pond).decode()
for token in ['ponds.default={}\n','minimumRadius.default=6\n','source.createToken={}\n','terminal.minimumRadius.default=4\n','terminal.maximumRadius.default=7\n','terminal.createToken={"minimumRadius":4,"maximumRadius":7}\n']:
    assert token in data


def rejects(s=surface,p=ponds,c=pond):
    try:
        extract(s,p,c)
    except ValueError:
        return
    raise AssertionError('Changed pond creation contract accepted')


rejects(s=surface.replace(b'new IrisSurfaceRiverPondsConfig()',b'null'))
rejects(p=ponds.replace(b'new IrisSurfaceRiverPondConfig(4, 7, 3)',b'new IrisSurfaceRiverPondConfig()'))
rejects(c=pond.replace(b'@NoArgsConstructor',b''))
rejects(c=pond.replace(b'int minimumRadius, int maximumRadius',b'int maximumRadius, int minimumRadius'))
rejects(c=pond.replace(b'this.minimumRadius = minimumRadius;',b'this.minimumRadius = maximumRadius;'))
rejects(c=pond.replace(b'this.depth = depth;',b'this.depth = 1;'))
rejects(c=pond.replace(b'int depth = 3',b'double depth = 3'))
rejects(c=pond.replace(b'boolean enabled = true',b'Boolean enabled = true'))
rejects(c=pond.replace(b'enabled = true',b'enabled = Boolean.TRUE'))
rejects(c=pond.replace(b'@MinNumber(1)',b''))
rejects(c=pond.replace(b'@MaxNumber(8)',b''))
rejects(p=ponds.replace(b'(4, 7, 3)',b'(0, 7, 3)'))
rejects(c=pond.replace(b'minimumRadius = 6;',b'minimumRadius = 33;'))
# Changes to constructor and class values must produce the corresponding minimal seed.
changed=extract(surface,ponds.replace(b'(4, 7, 3)',b'(6, 7, 4)'),pond).decode()
assert 'terminal.createToken={"maximumRadius":7,"depth":4}\n' in changed
print('PASS: pond endpoint/class defaults, minimal contextual seeds, exact constructor assignments and type/bound contracts reject drift')
