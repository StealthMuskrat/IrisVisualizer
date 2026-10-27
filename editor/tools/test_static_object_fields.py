"""Reject changes to native fixed-placement bounds, defaults, inheritance and ordering."""
import sys
from pathlib import Path
from extract_static_object_fields import extract

sources=[Path(path).read_bytes() for path in sys.argv[1:]]
result=extract(*sources).decode()
for token in ['position.policy=absoluteWorldYExclusiveMaximum\n','scale.minimum=0.01\n','order.policy=laterWins\n','interpolation.choices=NONE,TRILINEAR,TRICUBIC,TRIHERMITE\n']:assert token in result,token
mutations=[(0,b'29_999_984',b'30_000_000'),(0,b'private long seed = 0L;',b'private long seed = 1L;'),
           (0,b'private boolean bore = false;',b'private boolean bore = true;'),
           (0,b'position.getY() >= maxY',b'position.getY() > maxY'),
           (0,b'scale == null ? dimension.getAllObjectScaleFactor()',b'scale == null ? 1D'),
           (1,b'angle > 360',b'angle >= 360'),(2,b'private int x = 0;',b'private int x = 1;'),
           (3,b'private double allObjectScaleFactor = 1D;',b'private double allObjectScaleFactor = 2D;'),
           (4,b'MAXIMUM_FACTOR = 50D',b'MAXIMUM_FACTOR = 64D'),
           (5,b'position.getY() - dimension.getMinHeight()',b'position.getY()'),
           (5,b'new RNG(entry.getSeed())',b'new RNG(0)'),(5,b'.putAll(chunk.getValue().blocks())',b'.putAll(Map.of())'),
           (6,b'TRIHERMITE',b'UNKNOWN')]
for index,old,new in mutations:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try:extract(*changed)
    except ValueError:continue
    raise AssertionError('Accepted fixed-placement source drift: '+str(old))
print('PASS: static placement schema, absolute/exclusive world heights, scale fallback/bounds, fixed rotations, exact seeds and later-entry overlap order reject drift')
