"""Reject rotation default/constraint and native consumer drift."""
import sys
from pathlib import Path
from extract_floating_object_rotation import extract

sources=[Path(path).read_bytes() for path in sys.argv[1:]]
result=extract(*sources).decode()
for token in ['rotation.enabled.default=true\n','axis.enabled.default=false\n','axis.interval.default=0\n','axis.min.minimum=-360\n','axis.max.maximum=360\n','axis.interval.minimum=0\n','rotation.xAxis.default={}\n','rotation.yAxis.default={\\"enabled\\":true,\\"interval\\":90}\n','context=floatingObjectsOnly\n']:
    assert token in result,token
mutations=[(0,b'@NoArgsConstructor',b''),(0,b'new IrisAxisRotationClamp(true, false, 0, 0, 90)',b'new IrisAxisRotationClamp(true, false, 0, 0, 45)'),
           (0,b'return enabled && xAxis.isEnabled();',b'return xAxis.isEnabled();'),
           (1,b'private double interval = 0;',b'private int interval = 0;'),(1,b'@MinNumber(-360)',b'@Minimum(-360)'),(1,b'@MaxNumber(360)',b'@Maximum(360)'),
           (1,b'interval < 1 ? 1 : interval',b'interval <= 0 ? 1 : interval'),(1,b'interval <= 0 ? 1 : interval',b'interval < 0 ? 1 : interval'),
           (1,b'return min == max && min == 0;',b'return min == max;'),
           (2,b'p.setRotation(rotation);',b'p.setRotation(null);'),
           (3,b'anchored.setRotation(ROTATION_NONE);',b''),(3,b'inverted.setRotation(invertedRotation);',b''),
           (4,b'config.setRotation(slopeRotation);',b''),(4,b'SpinKernel spin = new SpinKernel(config.getRotation(), spinx, spiny, spinz);',b'')]
for index,old,new in mutations:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try: extract(*changed)
    except ValueError: continue
    raise AssertionError('Accepted floating rotation drift: '+str(old))
changed=sources.copy();changed[3]=changed[3].replace(b'IrisObjectPlacement floatingPlacement = placement.toPlacement(obj.getLoadKey());',b'IrisObjectPlacement floatingPlacement = placement.toPlacement(obj.getLoadKey()); floatingPlacement.setRotation(ROTATION_NONE);')
try: extract(*changed)
except ValueError: pass
else: raise AssertionError('Accepted a new floating rotation override')
print('PASS: floating rotation defaults, contextual Y constructor, axis bounds, interval resolution, transform preservation and native geometry/block-state consumers reject drift')
