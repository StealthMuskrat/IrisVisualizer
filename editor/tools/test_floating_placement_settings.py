"""Reject changed scale/density defaults, bounds, choices and consumer semantics."""
import sys
from pathlib import Path
from extract_floating_placement_settings import extract

sources=[Path(path).read_bytes() for path in sys.argv[1:]]
result=extract(*sources).decode()
for token in ['scale.size.minimum=0.01\n','scale.size.maximum=50\n','scale.variations.maximum=32\n','densityStyle.min.default=16\n','densityStyle.max.default=32\n','densityStyle.style.default=STATIC\n','scale.interpolation.choices=NONE,TRILINEAR,TRICUBIC,TRIHERMITE\n']:
    assert token in result,token
mutations=[(0,b'private IrisObjectScale scale;',b'private IrisObjectScale scale = new IrisObjectScale();'),
           (0,b'dimension.getAllObjectScaleFactor()',b'1D'),(0,b'Math.round(densityStyle.get(rng, x, z, data))',b'Math.floor(densityStyle.get(rng, x, z, data))'),
           (1,b'@MinNumber(0.01)',b'@Minimum(0.01)'),(1,b'@MaxNumber(32)',b'@Maximum(32)'),
           (1,b'private int variations = 7;',b'private double variations = 7;'),(1,b'private double size = 1;',b'private double size = 2;'),
           (1,b'factor < MINIMUM_FACTOR',b'factor <= MINIMUM_FACTOR'),(1,b'/ variantCount()',b'/ (variantCount() - 1)'),
           (1,b'interpolation == null',b'interpolation != null'),(2,b'    TRILINEAR,',b'    NONE,'),
           (3,b'private double min = 16;',b'private double min = 1;'),(3,b'NoiseStyle.STATIC',b'NoiseStyle.FLAT'),
           (4,b'placement.scaleObject(rng, raw, getDimension())',b'raw'),(4,b'entry.hasObjectShrink()',b'false'),
           (5,b'private NoiseStyle style = NoiseStyle.FLAT;',b'private NoiseStyle style = NoiseStyle.SIMPLEX;')]
for index,old,new in mutations:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try: extract(*changed)
    except ValueError: continue
    raise AssertionError('Accepted placement settings drift: '+str(old))
print('PASS: floating density/scale declarations, annotated bounds, exact interpolation choices, range sampling, dimension fallback and all three floating shrink consumers reject drift')
