"""Exercise full shape metadata extraction and reject malformed/dispatch drift."""
import sys
from extract_procedural_shape_fields import extract,read_sources,FIELDS
sources=read_sources(sys.argv[1]);baseline=extract(sources).decode()
for token in ['shape.fields=131\n','trees.heightMin.default=8\n','trees.shapeBase.default=2.718281828\n','formations.spiralTurns.minimum=0.25\n','fungi.stemWavePeriods.minimum=0.0001\n','coral.branchCount.maximum=12\n','range.policy=nativeNormalizedOrderPreserved\n','trees.profile.MEGA_SPRUCE.description=','trees.trunkShape.EASE_IN_OUT.description=','crystals.distribution.choices=RANDOM,GOLDEN_ANGLE\n']:assert token in baseline,token
assert sum(len(v) for v in FIELDS.values())==131
# Legal source edits must be reflected in regenerated values, including changed bounds/defaults.
for name,old,new,token in [('IrisProceduralTree',b'private int heightMin = 8;',b'private int heightMin = 9;','trees.heightMin.default=9\n'),('IrisFormation',b'@MaxNumber(6)',b'@MaxNumber(7)','formations.hoodooCapHeight.maximum=7\n'),('IrisFungus',b'@MinNumber(0.0001)',b'@MinNumber(0.001)','fungi.stemWavePeriods.minimum=0.001\n')]:
    assert old in sources[name],old
    changed=sources.copy();changed[name]=changed[name].replace(old,new)
    result=extract(changed).decode();assert result!=baseline and token in result,token
for name,old,new in [('IrisProceduralTree',b'private int heightMin = 8;',b'private String heightMin = "8";'),('IrisRuin',b'private double mossiness = 0.45;',b'private double moss = 0.45;'),('IrisCoral',b'private IrisCoralForm form = IrisCoralForm.BRANCHING;',b'private IrisCoralForm form = IrisCoralForm.UNKNOWN;'),('IrisTreeProfile',b'@Description("Giant conifer cone for 2x2 mega spruce trunks.")',b''),('IrisCrystal',b'CrystalGenerator.generate(this,',b'CrystalGenerator.build(this,'),('CoralGenerator',b'public static IrisObject generate(IrisCoral ',b'private static IrisObject generate(IrisCoral '),('RuinGenerator',b'int min = Math.min(lo, hi);',b'int min = lo;'),('IrisFungus',b'Math.min(stemHeightMin, stemHeightMax)',b'stemHeightMin'),('CrystalShardBuilder',b'Math.max(crystal.getShardCountMin(), crystal.getShardCountMax())',b'crystal.getShardCountMax()')]:
    assert old in sources[name],old
    changed=sources.copy();changed[name]=changed[name].replace(old,new)
    try:extract(changed)
    except ValueError:continue
    raise AssertionError('Accepted malformed/dispatch shape drift '+name)
print('PASS: 131 native shape fields, extracted defaults/annotation bounds, twelve complete enums including final constants, source-regeneration changes and eleven normalized range/geometry dispatch contracts')
