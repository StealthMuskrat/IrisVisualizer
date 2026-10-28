"""Reject direct definition defaults/bounds and authored native compiler constraint drift."""
import sys
from pathlib import Path
from extract_image_map_definition_fields import extract
sources=[Path(path).read_bytes() for path in sys.argv[1:]];result=extract(*sources).decode()
for token in ['type.default=GRAYSCALE_HEIGHT\n','blocksPerPixel.minimum=0.000001\n','colorTolerance.maximum=441.672956\n','minimumHeight.default=-64\n','originClass.z.default=0\n','legend.policy=exactRawRgbUnique\n']:assert token in result,token
for index,old,new in [(0,b'private String source = "";',b'private String source = "default";'),
                      (0,b'private double minimumHeight = -64D;',b'private double minimumHeight = 0D;'),
                      (0,b'private boolean clamp = true;',b'private boolean clamp = false;'),
                      (0,b'@MaxNumber(32)',b'@MaxNumber(64)'),
                      (1,b'private double z = 0D;',b'private double z = 1D;'),
                      (2,b'    COLOR_MAP',b'    COLORS'),(3,b'    DEG_270',b'    DEG_360'),
                      (4,b'    BICUBIC',b'    CUBIC'),(5,b'    MIRROR',b'    REFLECT'),
                      (6,b'    IGNORE',b'    SKIP'),(7,b'    FALLBACK',b'    DEFAULT'),
                      (8,b'!"png".equals(image.getFormat())',b'false'),
                      (8,b'definition.getMaximumHeight() < definition.getMinimumHeight()',b'false'),
                      (8,b'definition.getSampling() != IrisImageMapSampling.NEAREST',b'false'),
                      (8,b'decoded.putIfAbsent(rgb, encoded)',b'decoded.put(rgb, encoded)'),
                      (8,b'if (colors == null || colors.isEmpty())',b'if (false)'),
                      (8,b'if (fallbackRequired && (definition.getFallbackTarget() == null || definition.getFallbackTarget().isBlank()))',b'if (false)')]:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try:extract(*changed)
    except ValueError:continue
    raise AssertionError('Accepted definition drift: '+str(old))
print('PASS: all image-map definition/origin defaults/enums/bounds, PNG contract, finite ordered heights and typed sampling/alpha/legend/fallback native constraints reject drift')
