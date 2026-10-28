"""Reject selector defaults, registries, compatibility, priority and traversal drift."""
import sys
from pathlib import Path
from extract_resource_selector_fields import extract

sources=[Path(path).read_bytes() for path in sys.argv[1:]]
result=extract(*sources).decode()
for token in ['focus.family=biomes\n','focusRegion.family=regions\n','carvingBiome.policy=reachabilityOnly\n','focus.policy=usableBiomeBeforeRegion\n']:assert token in result,token
for index,old,new in [(0,b'private String focus = "";',b'private String focus = "test";'),
                      (0,b'private String focusRegion = "";',b'private Object focusRegion = "";'),
                      (1,b'private String carvingBiome = "";',b'private String carvingBiome = null;'),
                      (0,b'addReachableBiomeKey(pending, biome.getCarvingBiome());',b''),
                      (0,b'if (!visited.add(biomeKey))',b'if (false)'),
                      (0,b'region.getSeaBiomes().add(biomeKey);',b''),
                      (2,b'focus.isCompatExcluded()',b'false'),
                      (3,b'focusBiome = buffetActive ? null',b'focusBiome = false ? null'),
                      (3,b'focusRegion = engine.getDimension().resolveFocusRegion(focusBiome, engine);',b''),
                      (4,b'if (options.focused() != null)',b'if (false)'),
                      (5,b'biome.isCompatExcluded()',b'false')]:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try:extract(*changed)
    except ValueError:continue
    raise AssertionError('Accepted selector drift: '+str(old))
print('PASS: pinned selector declarations, native compatibility/priority/buffet, region fallback and visited carving reachability reject drift')
