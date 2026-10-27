"""Reject changes that would invalidate ore defaults, pass lists or ordering."""
import sys
from pathlib import Path
from extract_ore_generator_fields import extract

sources=[Path(path).read_bytes() for path in sys.argv[1:]]
result=extract(*sources).decode()
for token in ['palette.contextDefault=empty\n','range.context.min=30\n','range.class.min=16\n','chanceStyle.default=STATIC\n','surface.policy=exclusivePassLists\n','order.policy=firstNonNull\n']:assert token in result,token
mutations=[(0,b'new IrisMaterialPalette().qclear()',b'new IrisMaterialPalette()'),
           (0,b'new IrisGeneratorStyle(NoiseStyle.STATIC)',b'new IrisGeneratorStyle(NoiseStyle.FLAT)'),
           (0,b'new IrisRange(30, 80)',b'new IrisRange(16, 32)'),(0,b'threshold = 0.5',b'threshold = 1'),
           (0,b'threshold <= 0',b'threshold < 0'),(0,b'palette.getPalette().isEmpty()',b'false'),
           (0,b'sample(x, y, z) > threshold',b'sample(x, y, z) >= threshold'),
           (1,b'private double min = 16;',b'private double min = 30;'),(1,b'v >= min && v <= max',b'v > min && v < max'),
           (2,b'new IrisBlockData("STONE")',b'new IrisBlockData("DIRT")'),
           (3,b'private KList<IrisOreGenerator> ores = new KList<>();',b'private KList<IrisOreGenerator> ores;'),
           (4,b'oreGenerator.isGenerateSurface() == surface',b'oreGenerator.isGenerateSurface() != surface'),
           (5,b'oreGenerator.isGenerateSurface() == surface',b'oreGenerator.isGenerateSurface() != surface'),
           (6,b'oreGenerator.isGenerateSurface() == surface',b'oreGenerator.isGenerateSurface() != surface'),
           (7,b'Math.ceil(min)',b'Math.floor(min)'),(7,b'candidate.generate(x, y, z, rng, data)',b'candidate.generate(x, 0, z, rng, data)'),
           (8,b'ore == null && regionSurfaceOres.contains(y)',b'regionSurfaceOres.contains(y)'),
           (8,b'ore == null && dimensionUndergroundOres.contains(y)',b'dimensionUndergroundOres.contains(y)'),
           (8,b'surfaceOre(realX, i, realZ)',b'surfaceOre(realX, i+1, realZ)')]
for index,old,new in mutations:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try:extract(*changed)
    except ValueError:continue
    raise AssertionError('Accepted ore drift: '+str(old))
print('PASS: ore/palette/range constructor defaults, empty/threshold gates, exclusive pass lists, inclusive local-Y bands and first-result biome/region/dimension dispatch reject drift')
