"""Mutation checks for surface-pool source/native contracts (four pinned source paths)."""
import sys
from pathlib import Path
from extract_surface_pool_fields import extract
sources=[Path(path).read_bytes() for path in sys.argv[1:]]
data=extract(*sources).decode()
for token in ['list.minimum=0\n','list.maximum=64\n','id.default=lava_pool\n','fluid.default=lava\n','density.default=0.75\n','spacing.default=384\n','minimumRadius.default=4\n','maximumRadius.default=7\n','depth.default=2\n','radius.rule=minimumAtMostMaximum\n','coastal_grotto']:
    assert token in data
mutations=[(0,b'min = 0',b'min = 1'),(0,b'new KList<>()',b'null'),(1,b'String id = "lava_pool"',b'String id = "pool"'),
           (1,b'.qadd("lava")',b'.qadd("water")'),(1,b'@Required',b''),(1,b'@MaxNumber(8192)',b'@MaxNumber(8193)'),
           (1,b'int depth = 2',b'double depth = 2'),(1,b'int minimumRadius = 4',b'int minimumRadius = 5'),
           (1,b'@RegistryListResource(IrisBiome.class)',b'@RegistryListResource(IrisRegion.class)'),
           (2,b'MAX_DEEP_FLUIDS = 64',b'MAX_DEEP_FLUIDS = 65'),(2,b'[a-z0-9][a-z0-9_-]{0,63}',b'[a-z]+'),
           (2,b'else if (riverProfileIds.contains(id))',b'else if (false)'),(2,b'else if (!ids.add(id))',b'else if (false)'),
           (2,b'if (HydrologyFeatureQuery.isReservedKeyword(id))',b'if (false)'),(2,b'validateFluidPalette(poolPath, pool, true, errors);',b'validateFluidPalette(poolPath, pool, false, errors);'),
           (2,b'"density", 0D, 64D',b'"density", 0D, 65D'),(2,b'"maximumRadius", 7)',b'"maximumRadius", 8)'),
           (2,b'!biomes.containsKey(biome)',b'biomes.containsKey(biome)'),(3,b"replace('-', '_')",b"replace('_', '-')"),
           (3,b'BUILT_INS.containsKey(normalizeKeyword(value.trim()))',b'BUILT_INS.containsKey(value)')]
for index,old,new in mutations:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try:
        extract(*changed)
    except (ValueError,ArithmeticError):
        continue
    raise AssertionError('Accepted source drift: '+str(old))
for index in [0,1]:
    changed=sources.copy();changed[index]=changed[index].replace(b'@NoArgsConstructor',b'')
    try:
        extract(*changed)
    except ValueError:
        continue
    raise AssertionError('Accepted changed constructors')
print('PASS: surface pool defaults/types/native bounds, radius defaults/order, required IDs/palettes, profile collisions, biome binding and reserved selectors reject drift')
