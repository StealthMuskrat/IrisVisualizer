"""Fail on floating style default, constructor, optional-state and consumer drift."""
import sys
from pathlib import Path
from extract_floating_noise_fields import extract,FIELDS
sources=[Path(path).read_bytes() for path in sys.argv[1:]]
result=extract(*sources).decode()
for value in ['edgeTaperVariationStyle.zoomOverride=0.18\n','classStyle=FLAT\n','wallWarpStyle.default=null\n','wallWarpStyle.enableStyle=FLAT\n','carveStyle.default=null\n','optional.disable=null\n']:
    assert value in result,value
for field in FIELDS:
    assert field+'.description=' in result
mutations=[(0,b'@NoArgsConstructor',b''),(0,b'private IrisGeneratorStyle footprintStyle',b'private String footprintStyle'),
           (0,b'private IrisGeneratorStyle wallWarpStyle = null;',b'private IrisGeneratorStyle wallWarpStyle = NoiseStyle.SIMPLEX.style();'),
           (0,b'getFootprintStyle().create',b'getPickerStyle().create'),
           (0,b'style.create(baseSeed ^ 0xED6E7A9E5L, data)',b'style.create(baseSeed, data)'),
           (0,b'getCarveStyle()',b'getWallWarpStyle()'),(1,b'this.zoom = z;',b'this.zoom = 1D / z;'),
           (1,b'this.style = s;',b'this.style = NoiseStyle.FLAT;'),
           (2,b'reference.getPickerCng(baseSeed, data)',b'reference.getAltitudeCng(baseSeed, data)'),
           (2,b'!entry.hasCarvingReference()',b'true'),(2,b'carveThreshold < 1.0',b'carveThreshold <= 1.0'),
           (3,b'Math.min(clampedWidth - MIN_WIDTH, MAX_WIDTH - clampedWidth)',b'MAX_WIDTH')]
for index,old,new in mutations:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try: extract(*changed)
    except ValueError:continue
    raise AssertionError('Accepted floating noise drift: '+str(old))
print('PASS: eight floating style contexts, null-disabled states, class/context zoom defaults, constructors and seeded/gated native consumers reject drift')
