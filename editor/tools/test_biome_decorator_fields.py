"""Reject decorator schema/default/weight/filter/selection and force/stack policy drift."""
import sys
from pathlib import Path
from extract_biome_decorator_fields import extract

sources=[Path(path).read_bytes() for path in sys.argv[1:]];result=extract(*sources).decode()
for token in ['palette.defaultBlock=grass\n','stackMin.minimum=1\n','stackMax.maximum=2032\n','chance.default=0.1\n','palette.policy=weightedMainAndTop\n','selection.policy=reservoirAfterChance\n','filters.policy=nullUnrestrictedEmptyWhitelistForbid\n']:assert token in result,token
mutations=[(0,b'private double chance = 0.1;',b'private double chance = 0.2;'),(0,b'@MaxNumber(2032)',b'@MaxNumber(2048)'),
           (0,b'private KList<IrisBlockData> whitelist;',b'private KList<IrisBlockData> whitelist = new KList<>();'),
           (0,b'private IrisGeneratorStyle variance = NoiseStyle.STATIC.style();',b'private IrisGeneratorStyle variance = NoiseStyle.FLAT.style();'),
           (0,b'n < i.getWeight()',b'n < 1'),(0,b'if (arr.length == 0) {\n            return pickBlockData(rng, data, x, z);',b'if (arr.length == 0) {\n            return null;'),
           (1,b'    CEILING,',b'    UNKNOWN,'),(2,b'staging.computeIfAbsent(d.getPartOf()',b'staging.computeIfAbsent(IrisDecorationPart.NONE'),
           (3,b'colRng.nextInt(count) == 0',b'count == 0'),(3,b'decorator.getWhitelist() != null',b'decorator.getWhitelist() != null && !decorator.getWhitelist().isEmpty()'),
           (3,b'((double) stack / 100)',b'((double) stack / 10)'),(3,b'threshold >= decorator.getTopThreshold()',b'threshold > decorator.getTopThreshold()'),
           (4,b'decorator.isForcePlace() || decorator.getSlopeCondition().isDefault()',b'decorator.getSlopeCondition().isDefault()'),
           (5,b'maximumSlope >= 10',b'maximumSlope > 10'),(6,b'@MaxNumber(1000)',b'@MaxNumber(2048)'),(7,b'private NoiseStyle style = NoiseStyle.FLAT;',b'private NoiseStyle style = NoiseStyle.STATIC;')]
for index,old,new in mutations:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try:extract(*changed)
    except ValueError:continue
    raise AssertionError('Accepted decorator source drift: '+str(old))
print('PASS: all decorator fields/defaults/bounds, weighted palettes/unweighted nullable filters, part buckets/chance/reservoir selection, stack percentages/top threshold and force/slope guards reject drift')
