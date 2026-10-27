"""Reject drift in regional primitive defaults, power-of-two semantics and enum/list syntax."""
from extract_routing_extras import FIELDS, BOOLEANS, extract

routing=b'''@Description("Regional")
private IrisRiverRegionalConfig regional = new IrisRiverRegionalConfig();
@ArrayType(type = IrisRiverInlandOutlet.class)
@Description("Outlets")
private KList<IrisRiverInlandOutlet> inlandOutlets = new KList<>();'''
lines=['@NoArgsConstructor','public class IrisRiverRegionalConfig {']
for name in FIELDS:
    kind='boolean' if name in BOOLEANS else 'double' if name=='coastalChannelChance' else 'int'
    if kind!='boolean': lines+=['    @MinNumber('+('128' if name=='sampleSpacing' else '0')+')','    @MaxNumber('+('1024' if name=='sampleSpacing' else '1048576')+')']
    lines+=['    @Description("'+('Spacing; a power of two.' if name=='sampleSpacing' else 'Setting')+'")',
            '    private '+kind+' '+name+' = '+('true' if kind=='boolean' else '0.25D' if kind=='double' else '256')+';']
lines+=['}']; regional='\n'.join(lines).encode()
outlets=b'public enum IrisRiverInlandOutlet { SINKHOLE_GROTTO }'
data=extract(routing,regional,outlets).decode('ascii')
for item in ['regionalDefault={}\n','outletsDefault=[]\n','outletChoices=SINKHOLE_GROTTO\n','enabled.default=true\n','coastalChannelChance.default=0.25\n',
             'sampleSpacing.rule=positivePowerOfTwo\n','sampleSpacing.options=128,256,512,1024\n']:
    assert item in data


def rejects(r=routing,s=regional,e=outlets):
    try:
        extract(r,s,e)
    except ValueError:
        return
    raise AssertionError('Changed regional/outlet contract silently accepted')


rejects(r=routing.replace(b'new IrisRiverRegionalConfig()',b'null'))
rejects(r=routing.replace(b'new KList<>()',b'new KList<>().qadd(IrisRiverInlandOutlet.SINKHOLE_GROTTO)'))
rejects(r=routing.replace(b'type = IrisRiverInlandOutlet.class',b'type = String.class'))
rejects(s=regional.replace(b'@NoArgsConstructor',b''))
rejects(s=regional.replace(b'int sampleSpacing',b'double sampleSpacing'))
rejects(s=regional.replace(b'a power of two',b'any integer'))
rejects(s=regional.replace(b'sampleSpacing = 256',b'sampleSpacing = 257'))
rejects(e=outlets.replace(b'SINKHOLE_GROTTO',b'SINKHOLE_GROTTO, SINKHOLE_GROTTO'))
rejects(e=outlets.replace(b'SINKHOLE_GROTTO',b'SINKHOLE_GROTTO(1)'))
print('PASS: regional parent/scalar defaults, documented spacing rule and inland enum/list syntax reject drift')
