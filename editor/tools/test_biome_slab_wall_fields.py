"""Fail on contextual palette, placement gate and slope consumer source drift."""
import sys
from pathlib import Path
from extract_biome_slab_wall_fields import extract
sources=[Path(path).read_bytes() for path in sys.argv[1:]]
result=extract(*sources).decode()
for value in ['palette.contextDefault=[]\n','palette.classBlock=GRASS_BLOCK\n','palette.minimum=0\n','slope.context=slabOnly\n','thickness.context=readOnly\n']:
    assert value in result
mutations=[(0,b'private IrisBiomePaletteLayer slab = new IrisBiomePaletteLayer().zero();',b'private IrisBiomePaletteLayer slab = new IrisBiomePaletteLayer();'),
           (0,b'private IrisBiomePaletteLayer wall = new IrisBiomePaletteLayer().zero();',b'private IrisBiomePaletteLayer wall = null;'),
           (1,b'palette.clear();',b'palette.add(new IrisBlockData("STONE"));'),
           (1,b'new IrisBlockData("GRASS_BLOCK")',b'new IrisBlockData("STONE")'),
           (1,b'if (localBlockData.isEmpty())',b'if (localBlockData.size() == 1)'),
           (2,b'dimension.isPostProcessingWalls()',b'dimension.isPostProcessingSlabs()'),
           (2,b'if (walls && !river && !biome.isShore())',b'if (walls)'),
           (2,b'biome.getSlab().getSlopeCondition()',b'biome.getWall().getSlopeCondition()'),
           (2,b'sc.isValid(getComplex().getSlopeStream().get(x, z))',b'true'),
           (3,b'biome.getWall().get(rng, worldX, yy, worldZ, wallData)',b'biome.getSlab().get(rng, worldX, yy, worldZ, wallData)'),
           (4,b'biome.getWall().get(random, x, worldY - minimumY, z, data)',b'biome.getSlab().get(random, x, worldY - minimumY, z, data)')]
for index,old,new in mutations:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try: extract(*changed)
    except ValueError: continue
    raise AssertionError('Accepted changed slab/wall binding: '+str(old))
for index,addition in [(2,b'biome.getWall().getSlopeCondition();'),(3,b'biome.getWall().getMinHeight();'),(4,b'biome.getWall().getMaxHeight();')]:
    changed=sources.copy();changed[index]+=addition
    try: extract(*changed)
    except ValueError: continue
    raise AssertionError('Accepted new consumer')
print('PASS: slab/wall zero versus GRASS class defaults, empty palette behavior, post gates, slab slope and wall material consumers reject drift')
