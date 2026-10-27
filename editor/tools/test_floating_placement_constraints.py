"""Reject source drift in bounded guard and warp authoring contracts."""
import sys
from pathlib import Path
from extract_floating_placement_constraints import extract
sources=[Path(path).read_bytes() for path in sys.argv[1:]]
result=extract(*sources).decode()
for token in ['clamp.minimumHeight.minimum=-2048\n','clamp.maximumHeight.maximum=2048\n','slopeCondition.maximumSlope.default=10\n','surfaceSupportBuffer.minimum=0\n','surfaceSupportDepth.minimum=1\n','carvingSupport.choices=SURFACE_ONLY,CARVING_ONLY,ANYWHERE\n','warp.default=FLAT\n','warp.multiplier.minimum=0.00001\n']:
    assert token in result,token
mutations=[(0,b'private int surfaceSupportDepth = 2;',b'private double surfaceSupportDepth = 2;'),(0,b'@MinNumber(1)',b'@Minimum(1)'),
           (0,b'p.setWarp(warp);',b''),(0,b'p.setRequireSurfaceSupport(requireSurfaceSupport);',b''),
           (1,b'@MinNumber(-2048)',b'@Minimum(-2048)'),(1,b'private int maximumHeight = 2048;',b'private double maximumHeight = 2048;'),
           (2,b'minimumSlope <= 0 && maximumSlope >= 10',b'minimumSlope == 0 && maximumSlope == 10'),
           (3,b'    CARVING_ONLY,',b'    SURFACE_ONLY,'),
           (4,b'!config.isForcePlace() && !config.getSlopeCondition().isDefault()',b'!config.getSlopeCondition().isDefault()'),
           (4,b'config.getWarp().getMultiplier() / 2D',b'config.getWarp().getMultiplier()'),
           (4,b'case ANYWHERE -> false;',b'case ANYWHERE -> true;'),
           (5,b'anchored.setForcePlace(true);',b'anchored.setForcePlace(false);'),
           (6,b'@MinNumber(0.00001)',b'@Minimum(0.00001)'),(6,b'private double multiplier = 1;',b'private int multiplier = 1;')]
for index,old,new in mutations:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try: extract(*changed)
    except ValueError: continue
    raise AssertionError('Accepted placement constraint drift: '+str(old))
changed=sources.copy();changed[5]+=b'\n anchored.setWarp(null);'
try: extract(*changed)
except ValueError: pass
else: raise AssertionError('Accepted a new anchored warp override')
print('PASS: floating clamp/slope/support bounds, boolean/default/enum contracts, native force/mode bypasses and preserved coordinate warp/multiplier reject drift')
