"""Reject grotto parent/type/bound/default drift and changed enum/fallback contracts."""
from extract_grotto_fields import COASTAL, INLAND, extract

rivers=b'@NoArgsConstructor\n@Description("Grottos")\nprivate IrisRiverGrottoConfig grottos = new IrisRiverGrottoConfig();'
grottos=b'''@NoArgsConstructor
@Description("Coastal")
private IrisCoastalRiverGrottoConfig coastal = new IrisCoastalRiverGrottoConfig();
@Description("Inland")
private IrisInlandRiverGrottoConfig inland = new IrisInlandRiverGrottoConfig();'''
levels=b'public enum IrisGrottoPoolLevel { @Description("Sea") SEA_LEVEL }'
raws={}
for section,names in [('coastal',COASTAL),('inland',INLAND)]:
    lines=['@NoArgsConstructor']
    for name in names:
        kind='IrisGrottoPoolLevel' if name=='poolLevel' else 'boolean' if name in ['enabled','connectSurfaceRivers'] else 'Integer' if name=='cliffMinimumHeight' else 'double' if name=='cliffSlopeFactor' else 'int'
        if kind not in ['boolean','IrisGrottoPoolLevel']:
            lines+=['    @MinNumber(0)','    @MaxNumber(1048576)']
        help_text='Null uses the larger of 4 and verticalRadius.' if name=='cliffMinimumHeight' else 'Setting'
        default='true' if kind=='boolean' else 'IrisGrottoPoolLevel.SEA_LEVEL' if name=='poolLevel' else 'null' if kind=='Integer' else '0.5D' if kind=='double' else '12'
        lines+=['    @Description("'+help_text+'")','    private '+kind+' '+name+' = '+default+';']
    raws[section]='\n'.join(lines).encode()
data=extract(rivers,grottos,raws['coastal'],raws['inland'],levels).decode()
for token in ['grottos.default={}\n','coastal.default={}\n','inland.default={}\n','coastal.poolLevel.choices=SEA_LEVEL\n','coastal.cliffMinimumHeight.default=null\n','coastal.cliffMinimumHeight.type=Integer\n','coastal.cliffSlopeFactor.default=0.5\n']:
    assert token in data


def rejects(r=rivers,g=grottos,c=raws['coastal'],i=raws['inland'],l=levels):
    try:
        extract(r,g,c,i,l)
    except (ValueError,ArithmeticError):
        return
    raise AssertionError('Changed grotto contract accepted')


rejects(r=rivers.replace(b'new IrisRiverGrottoConfig()',b'null'))
rejects(g=grottos.replace(b'new IrisCoastalRiverGrottoConfig()',b'null'))
rejects(g=grottos.replace(b'new IrisInlandRiverGrottoConfig()',b'null'))
rejects(c=raws['coastal'].replace(b'@NoArgsConstructor',b''))
rejects(i=raws['inland'].replace(b'@NoArgsConstructor',b''))
rejects(c=raws['coastal'].replace(b'Integer cliffMinimumHeight',b'int cliffMinimumHeight'))
rejects(c=raws['coastal'].replace(b'cliffMinimumHeight = null',b'cliffMinimumHeight = 0'))
rejects(c=raws['coastal'].replace(b'Null uses the larger of 4 and verticalRadius.',b'Null means zero.'))
rejects(i=raws['inland'].replace(b'boolean enabled = true',b'boolean enabled = Boolean.TRUE'))
rejects(i=raws['inland'].replace(b'int maximumVolume',b'double maximumVolume'))
rejects(c=raws['coastal'].replace(b'@MinNumber(0)',b''))
rejects(i=raws['inland'].replace(b'@MaxNumber(1048576)',b''))
rejects(c=raws['coastal'].replace(b'IrisGrottoPoolLevel.SEA_LEVEL',b'IrisGrottoPoolLevel.UNKNOWN'))
rejects(l=levels.replace(b'SEA_LEVEL',b'SEA_LEVEL, @Description("Duplicate") SEA_LEVEL'))
rejects(l=levels.replace(b'@Description("Sea")',b''))
print('PASS: grotto defaults/types/bounds, exact pool enum and nullable cliff-height fallback contracts reject drift')
