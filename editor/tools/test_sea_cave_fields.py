"""Reject drift between sea-cave authoring bounds and the pinned validator/compiler contracts."""
from extract_sea_cave_fields import extract

coastal=b'''@NoArgsConstructor
@Description("Sea caves")
private IrisSeaCaveConfig seaCaves = new IrisSeaCaveConfig();
    @MinNumber(1)
    @MaxNumber(128)
    @Description("Radius")
    private int horizontalRadius = 12;'''
fields=[('enabled','boolean','true',None,None),('maximumPerTile','int','3','0','64'),('minimumSpacing','int','160','16','8192'),('minimumCoastHeight','int','8','1','128'),('depth','int','12','0','128'),('sweepJitterDegrees','double','25D','0','90')]
lines=['@NoArgsConstructor']
for name,kind,default,minimum,maximum in fields:
    if minimum is not None:
        lines+=['    @MinNumber('+minimum+')','    @MaxNumber('+maximum+')']
    help_text='Must be at least twice the coastal grotto horizontalRadius.' if name=='minimumSpacing' else 'Setting'
    lines+=['    @Description("'+help_text+'")','    private '+kind+' '+name+' = '+default+';']
sea='\n'.join(lines).encode()
lines=['private static final IrisCoastalRiverGrottoConfig DEFAULT_COASTAL_GROTTO = new IrisCoastalRiverGrottoConfig();',
       '    private static void validateGrotto(', '            String path, JSONObject grotto, boolean coastal, List<String> errors', '    ) {',
       '        PackJsonFieldChecks.validateOptionalIntegerRange(path, grotto, "horizontalRadius", 1, 128, errors);',
       '        if (seaCaves != null) {', '            PackJsonFieldChecks.validateOptionalBoolean(seaCavePath, seaCaves, "enabled", errors);']
for name,kind,default,minimum,maximum in fields:
    if kind!='boolean':
        lines+=['            PackJsonFieldChecks.validateOptional'+('Integer' if kind=='int' else 'Double')+'Range(seaCavePath, seaCaves, "'+name+'", '+minimum+('D' if kind=='double' else '')+', '+maximum+('D' if kind=='double' else '')+', errors);']
lines+=['            int horizontalRadius = integerValue(grotto, "horizontalRadius", DEFAULT_COASTAL_GROTTO.getHorizontalRadius());',
        '            int minimumSpacing = integerValue(seaCaves, "minimumSpacing", DEFAULT_COASTAL_GROTTO.getSeaCaves().getMinimumSpacing());',
        '            if (minimumSpacing < horizontalRadius * 2) { errors.add("Too close"); }', '        }','    }']
validator='\n'.join(lines).encode()
compiler=b'''private static HydrologyPlannerSettings.SeaCaves seaCaves(IrisCoastalRiverGrottoConfig coastal) {
        IrisSeaCaveConfig seaCaves = coastal.getSeaCaves();
        return new HydrologyPlannerSettings.SeaCaves(coastal.isEnabled() && seaCaves.isEnabled(), seaCaves.getMaximumPerTile(), seaCaves.getMinimumSpacing(), seaCaves.getMinimumCoastHeight(), seaCaves.getDepth(), seaCaves.getSweepJitterDegrees());
    }'''
data=extract(coastal,sea,validator,compiler).decode()
for token in ['seaCaves.default={}\n','radius.default=12\n','minimumSpacing.default=160\n','minimumSpacing.rule=twiceCoastalRadius\n','enabled.rule=coastalAndSeaEnabled\n','sweepJitterDegrees.maximum=90\n']:
    assert token in data


def rejects(c=coastal,s=sea,v=validator,p=compiler):
    try:
        extract(c,s,v,p)
    except (ValueError,ArithmeticError):
        return
    raise AssertionError('Changed native sea-cave contract accepted')


rejects(c=coastal.replace(b'new IrisSeaCaveConfig()',b'null'))
rejects(c=coastal.replace(b'int horizontalRadius',b'double horizontalRadius'))
rejects(s=sea.replace(b'@NoArgsConstructor',b''))
rejects(s=sea.replace(b'boolean enabled = true',b'boolean enabled = Boolean.TRUE'))
rejects(s=sea.replace(b'int depth',b'double depth'))
rejects(s=sea.replace(b'Must be at least twice the coastal grotto horizontalRadius.',b'Any spacing works.'))
rejects(v=validator.replace(b'"minimumSpacing", 16, 8192',b'"minimumSpacing", 32, 8192'))
rejects(v=validator.replace(b'"sweepJitterDegrees", 0D, 90D',b'"sweepJitterDegrees", 0D, 91D'))
rejects(v=validator.replace(b'horizontalRadius * 2',b'horizontalRadius * 3'))
rejects(v=validator.replace(b'DEFAULT_COASTAL_GROTTO.getHorizontalRadius()',b'DEFAULT_COASTAL_GROTTO.getVerticalRadius()'))
rejects(v=validator.replace(b'validateOptionalBoolean',b'validateRequiredBoolean'))
rejects(p=compiler.replace(b' && ',b' || '))
rejects(p=compiler.replace(b'seaCaves.getMinimumSpacing()',b'seaCaves.getDepth()'))
print('PASS: sea-cave defaults/types/bounds match native validation, spacing relation/defaults and compiler enablement gating reject drift')
