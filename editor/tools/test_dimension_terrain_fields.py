"""Reject overlay defaults/bounds/addition and exact external provider identity/map drift."""
import sys
from pathlib import Path
from extract_dimension_terrain_fields import extract
sources=[Path(path).read_bytes() for path in sys.argv[1:]];result=extract(*sources).decode()
for token in ['overlay.generator.default=IRIS\n','overlay.min.minimum=-2032\n','overlay.max.maximum=2032\n','overlay.policy=sumFittedOverlays\n','transform.policy=exactIdentityStringMap\n']:assert token in result,token
for index,old,new in [(0,b'private IrisTerrainTransform terrainTransform;',b'private IrisTerrainTransform terrainTransform = new IrisTerrainTransform();'),
                      (1,b'new IrisGeneratorStyle(NoiseStyle.IRIS)',b'new IrisGeneratorStyle(NoiseStyle.FLAT)'),
                      (1,b'@MinNumber(-2032)',b'@MinNumber(0)'),(1,b'private int max = 0;',b'private int max = 32;'),
                      (1,b'fitDouble(min, max, x, z)',b'fitDouble(0, max, x, z)'),
                      (2,b'value += style.get(rng, getData(), x, z);',b'value = style.get(rng, getData(), x, z);'),
                      (2,b'Math.min(style.getMin(), style.getMax())',b'style.getMin()'),
                      (3,b'value += style.get(rng, dimensionData, x, z);',b'value -= style.get(rng, dimensionData, x, z);'),
                      (4,b'private String version;',b'private String version = "latest";'),
                      (4,b'KMap<String, String>',b'KMap<String, Object>'),
                      (5,b'providers.get(identity)',b'providers.values().iterator().next()'),
                      (5,b'if (descriptor == null) {',b'if (false) {'),
                      (5,b'return value;',b'return value.trim();'),
                      (5,b'radius > 16',b'radius > 32')]:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try:extract(*changed)
    except ValueError:continue
    raise AssertionError('Accepted terrain-control drift: '+str(old))
print('PASS: overlay required defaults/bounds, additive fit/height consumers, reversed bounds and exact external provider identity/settings/optional/radius contracts reject drift')
