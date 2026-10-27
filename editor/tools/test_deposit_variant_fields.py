"""Fail on changes to world-Y remapping, scope precedence or surface-host inheritance."""
import sys
from pathlib import Path
from extract_deposit_variant_fields import extract

sources=[Path(path).read_bytes() for path in sys.argv[1:]]
result=extract(*sources).decode()
for token in ['minHeight.minimum=-2048\n','maxHeight.default=0\n','height.policy=inclusiveWorldY\n','source.policy=materialIdentity\n','hosts.policy=nullInheritEmptyForbid\n']:assert token in result,token
mutations=[(0,b'private int minHeight = 0;',b'private int minHeight = 1;'),(0,b'@MinNumber(-2048)',b'@MinNumber(-2049)'),
           (0,b'map.get(IrisProceduralBlocks.materialKey(ore))',b'map.get(ore.toString())'),
           (0,b'out.put(IrisProceduralBlocks.materialKey(source), target);',b'out.put(entry.getKey(), target);'),
           (0,b'source == null || target == null',b'source == null'),
           (1,b'absoluteWorldY(getEngine().getMinHeight(), localY)',b'localY'),
           (1,b'matchDepositVariant(biome.getDepositVariants(), ore, worldY)',b'matchDepositVariant(dimension.getDepositVariants(), ore, worldY)'),
           (1,b'y < variant.getMinHeight() || y > variant.getMaxHeight()',b'y <= variant.getMinHeight() || y >= variant.getMaxHeight()'),
           (1,b'if (swapped != null) {\n                return swapped;',b'if (swapped != null) {\n                continue;'),
           (1,b'B.toDeepSlateOre(current, ore)',b'ore'),
           (2,b'return surfaceBiome.canReplaceSurfaceOre(state);',b'return canReplaceSurface(state);'),
           (3,b'return biome.getSurfaceOreReplaceableBlocks() != null;',b'return biome.getSurfaceOreReplaceableBlocks() != null && !biome.getSurfaceOreReplaceableBlocks().isEmpty();'),
           (3,b'.contains(IrisProceduralBlocks.materialKey(state))',b'.contains(state.toString())'),
           (4,b'private KList<IrisDepositVariant> depositVariants = new KList<>();',b'private KList<IrisDepositVariant> depositVariants;'),
           (5,b'private KList<IrisDepositVariant> depositVariants = new KList<>();',b'private KList<IrisDepositVariant> depositVariants;'),
           (6,b'private KList<String> surfaceOreReplaceableBlocks = null;',b'private KList<String> surfaceOreReplaceableBlocks = new KList<>();')]
for index,old,new in mutations:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try:extract(*changed)
    except ValueError:continue
    raise AssertionError('Accepted variant/host drift: '+str(old))
print('PASS: variant world-Y defaults/bounds, inclusive first-remap cave-biome/region/dimension precedence, material-only keys, fallback and null/empty biome exterior-host semantics reject drift')
