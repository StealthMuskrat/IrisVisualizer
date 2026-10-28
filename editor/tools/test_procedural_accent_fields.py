"""Reject native accent/default/weight and dispatch drift."""
import sys
from extract_procedural_accent_fields import extract,read_sources
sources=read_sources(sys.argv[1]);baseline=extract(sources).decode()
for token in ['decorators.target.default=BRANCH_TIP\n','accents.target.default=TOP\n','weightedSecondaryLeaves.weight.minimum=0\n','decorators.length.minimum=1\n','decorators.axisAware.default=false\n','weight.policy=longSumNonnegative\n','secondary.policy=secondaryPaletteThenWeightedThenSingle\n','allZero.policy=primaryLeafFallback\n','decorators.target.GROUND_SCATTER.description=','accents.target.BASE_SCATTER.description=']:assert token in baseline,token
for name,old,new in [('IrisTreeDecorator',b'private IrisMaterialPalette palette = null;',b'private IrisMaterialPalette palette = new IrisMaterialPalette();'),('IrisTreeSecondaryLeaf',b'private int weight = 1;',b'private double weight = 1;'),('IrisRuinDecorator',b'private double chance = 0.4;',b'private String chance = "0.4";'),('IrisProceduralTree',b'private KList<IrisTreeDecorator> decorators = new KList<>();',b'private KList<IrisTreeDecorator> decorators = null;'),('TreeBlockResolver',b'long total = 0;',b'int total = 0;'),('TreeBlockResolver',b'total += Math.max(0, s.getWeight());',b'total += s.getWeight();'),('TreeDecoratorApplier',b'switch (dec.getTarget())',b'switch (null)'),('RuinDecoratorApplier',b'rng.chance(decorator.getChance())',b'rng.chance(1)'),('ProceduralTreeGenerator',b'TreeDecoratorApplier.apply(canvas, tree, baseSeed, branchEndpoints);',b''),('RuinGenerator',b'RuinDecoratorApplier.apply(canvas, decorator, data,',b'RuinDecoratorApplier.apply(canvas, null, data,')]:
    assert old in sources[name],old
    changed=sources.copy();changed[name]=changed[name].replace(old,new)
    try:extract(changed)
    except ValueError:continue
    raise AssertionError('Accepted accent contract drift '+name)
print('PASS: tree decorators/weighted secondary foliage/ruin accents, complete scoped target enums, native default/types/bounds, nullable collection/palette policy, nonnegative long weight accumulation and secondary palette/list/single precedence, native applier dispatch')
