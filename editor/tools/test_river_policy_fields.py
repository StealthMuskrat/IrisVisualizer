"""Reject drift in boxed scalar null defaults, annotations, enums and owner defaults."""
from extract_river_policy_fields import FIELDS, ENUMS, INTEGERS, BOOLEANS, BIOME_LISTS, extract

lines = ['@NoArgsConstructor', 'public class IrisRiverPolicy {']
for name in FIELDS:
    kind = ENUMS.get(name, 'Integer' if name in INTEGERS else 'Boolean' if name in BOOLEANS else 'Double')
    if kind in ['Integer','Double']:
        lines += ['    @MinNumber(0)', '    @MaxNumber(64)']
    lines += ['    @Description("Nullable override")', '    private '+kind+' '+name+' = null;']
for name in BIOME_LISTS:
    lines += ['    @RegistryListResource(IrisBiome.class)','    @ArrayType(type = String.class)',
              '    @Description("Biome selection. An empty list explicitly clears the inherited selection.")','    private KList<String> '+name+' = null;']
lines += ['}']
policy = '\n'.join(lines).encode()
owner = b'    @Description("Policy")\n    private IrisRiverPolicy riverPolicy = '
owners = {context:owner+(b'new IrisRiverPolicy();' if context=='dimensions' else b'null;') for context in ['dimensions','regions','biomes']}
placement = b'public enum IrisRiverPlacementMode { @Description("Natural") NATURAL, @Description("Transit") TRANSIT_ONLY }'
routing = b'public enum IrisRiverRoutingMode { @Description("Allow") ALLOW, @Description("Block") BLOCK }'
data = extract(policy,owners,placement,routing).decode('ascii')
for item in ['dimensions.default={}\n','regions.default=null\n','biomes.default=null\n','surfaceSourceSpacing.type=Integer\n','widthMultiplier.type=Double\n',
             'confined.type=Boolean\n','outletAdmission.default=null\n','placement.choices=NATURAL,TRANSIT_ONLY\n','routing.choices=ALLOW,BLOCK\n']:
    assert item in data
assert 'confined.minimum=' not in data
for name in BIOME_LISTS:
    assert name+'.default=null\n' in data and name+'.referenceFamily=biomes\n' in data and name+'.emptyClearsInheritance=true\n' in data


def rejects(p=policy,o=owners,a=placement,r=routing):
    try:
        extract(p,o,a,r)
    except ValueError:
        return
    raise AssertionError('Changed river policy declaration silently accepted')


rejects(p=policy.replace(b'@NoArgsConstructor',b''))
rejects(p=policy.replace(b'Double widthMultiplier',b'double widthMultiplier'))
rejects(p=policy.replace(b'Integer surfaceSourceSpacing',b'Double surfaceSourceSpacing'))
rejects(p=policy.replace(b'Boolean confined',b'Double confined'))
rejects(p=policy.replace(b'widthMultiplier = null',b'widthMultiplier = 1.0'))
rejects(p=policy.replace(b'placement = null',b'placement = IrisRiverPlacementMode.NATURAL'))
rejects(o=dict(owners,dimensions=owner+b'null;'))
rejects(o=dict(owners,regions=owner+b'new IrisRiverPolicy();'))
rejects(o={'dimensions':owners['dimensions'],'biomes':owners['biomes']})
rejects(a=placement.replace(b'TRANSIT_ONLY',b'NATURAL'))
rejects(r=routing.replace(b'@Description("Block")',b''))
rejects(r=routing.replace(b'BLOCK',b'BLOCK(1)'))
rejects(p=policy.replace(b'surfaceBiomes = null',b'surfaceBiomes = new KList<>()'))
rejects(p=policy.replace(b'@RegistryListResource(IrisBiome.class)',b'@RegistryListResource(IrisRegion.class)',1))
rejects(p=policy.replace(b'@ArrayType(type = String.class)',b'@ArrayType(type = String.class, min = 1)',1))
rejects(p=policy.replace(b'@ArrayType(type = String.class)',b'@Required\n    @ArrayType(type = String.class)',1))
rejects(p=policy.replace(b'An empty list explicitly clears the inherited selection.',b'An empty list inherits.',1))
print('PASS: river nullable types/defaults, owner defaults and exact enum boundaries reject drift')
