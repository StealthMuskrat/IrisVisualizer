"""Reject heightmap binding, serialization and guard changes."""
import sys
from pathlib import Path
from extract_floating_placement_overrides import extract
sources=[Path(path).read_bytes() for path in sys.argv[1:]]
result=extract(*sources).decode()
for token in ['force.keys=forcePlace,force\n','force.default=false\n','heightmap.policy=nullUnderlyingQueries\n','noise.styleDefault=IRIS\n']:
    assert token in result,token
mutations=[(0,b'private IrisNoiseGenerator heightmap;',b'private IrisNoiseGenerator heightmap = new IrisNoiseGenerator();'),
           (0,b'alternate = {"force"}',b'alternate = {"forced"}'),(0,b'p.setHeightmap(heightmap);',b''),
           (1,b'NoiseStyle.IRIS.style()',b'NoiseStyle.FLAT.style()'),(1,b'if (!enabled) {\n            return offsetY;',b'if (!enabled) {\n            return 0;'),
           (2,b'Math.round(config.getHeightmap().getNoise',b'Math.floor(config.getHeightmap().getNoise'),(2,b'rng.nextLong() + yv + z - x',b'rng.nextLong()'),
           (3,b'!config.isForcePlace() && !config.getSlopeCondition().isDefault()',b'!config.getSlopeCondition().isDefault()'),
           (3,b'config.getHeightmap() != null ? new HeightmapObjectPlacer',b'false ? new HeightmapObjectPlacer'),
           (4,b'anchored.setForcePlace(true);',b'anchored.setForcePlace(false);')]
for index,old,new in mutations:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try:extract(*changed)
    except ValueError:continue
    raise AssertionError('Accepted placement override drift: '+str(old))
changed=sources.copy();changed[4]+=b'\n floatingPlacement.setForcePlace(true);'
try:extract(*changed)
except ValueError:pass
else:raise AssertionError('Accepted forced floating override')
print('PASS: virtual height query binding/rounding/delegation, noise defaults, native force aliases and scoped guard/anchored override contracts reject drift')
