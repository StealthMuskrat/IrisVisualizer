"""Reject changes to floating entry shapes, defaults, runtime bindings and enum declarations."""
import sys
from pathlib import Path
from extract_floating_biome_fields import extract
sources=[Path(path).read_bytes() for path in sys.argv[1:]]
result=extract(*sources).decode()
for token in ['list.default=[]\n','edgeTaperWidth.default=10\n','edgeTaperExponent.default=1.0\n','minHeightAboveSurface.default=160\n','maxHeightAboveSurface.default=210\n','minAbsoluteY.default=null\n','localFluidHeight.default=null\n','topShapeMode.default=BIOME\n','height.context=absoluteWorldY\n','TopShapeMode.choices=BIOME,NOISE,FLAT\n']:
    assert token in result,token
mutations=[(0,b'min = 1, type = IrisFloatingChildBiomes.class',b'min = 2, type = IrisFloatingChildBiomes.class'),
           (1,b'@NoArgsConstructor',b''),(1,b'private Integer minAbsoluteY = null;',b'private int minAbsoluteY = 0;'),
           (1,b'private double footprintThreshold = 0.5;',b'private String footprintThreshold = "0.5";'),
           (1,b'@MinNumber(0.01)',b'@NoBound(0.01)'),
           (1,b'biome.equals(parent.getLoadKey())',b'false'),
           (1,b'data.getBiomeLoader().load(carving)',b'data.getBiomeLoader().load(biome)'),
           (1,b'case MERGE, REPLACE -> bottomObjectOverrides;',b'case MERGE, REPLACE -> extraObjects;'),
           (2,b'Math.min(clampedWidth - MIN_WIDTH, MAX_WIDTH - clampedWidth)',b'MAX_WIDTH'),
           (3,b'entry.getMinHeightAboveSurface() - worldMin',b'entry.getMinHeightAboveSurface()'),
           (3,b'Math.max(minAlt, entry.getMaxHeightAboveSurface() - worldMin)',b'entry.getMaxHeightAboveSurface()'),
           (3,b'Math.max(minDepth, entry.getBottomDepthMax())',b'entry.getBottomDepthMax()'),
           (3,b'botY = minAbsoluteY - worldMin;',b'botY = minAbsoluteY;'),
           (3,b'Math.max(1, entry.getMaxThickness())',b'entry.getMaxThickness()'),
           (4,b'    FLAT',b'    BIOME'),(5,b'    CUSTOM',b'    CUSTOM;'),(6,b'    MERGE,',b'    unsupported(),')]
for index,old,new in mutations:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try: extract(*changed)
    except (ValueError,KeyError): continue
    raise AssertionError('Accepted floating source drift: '+str(old))
print('PASS: floating default/list/type/enum metadata, absolute altitude/depth bounds, nullable limits, exact biome/carving references and runtime clamp/placement bindings reject drift')
