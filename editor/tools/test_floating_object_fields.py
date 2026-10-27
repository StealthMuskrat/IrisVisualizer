"""Reject placement shape/bounds and contextual chance/mode/reference source drift."""
import sys
from pathlib import Path
from extract_floating_object_fields import extract
sources=[Path(path).read_bytes() for path in sys.argv[1:]]
result=extract(*sources).decode()
for token in ['place.minimum=1\n','allowedCollisions.minimum=0\n','density.maximum=2147483647\n','mode.default=CENTER_HEIGHT\n','floating.mode=configured\n','newFloatingPreset=FLOATING\n','chance.context=perAttemptWithJitter\n']:
    assert token in result,token
mutations=[(0,b'extraObjects = new KList<>()',b'extraObjects = null'),(0,b'merged.addAll(topObjectOverrides);',b'merged.addAll(extraObjects);'),
           (1,b'@NoArgsConstructor',b''),(1,b'private double chance = 1;',b'private String chance = "1";'),
           (1,b'@MinNumber(1)',b'@NoMinimum(1)'),(1,b'p.setMode(mode);',b'p.setMode(ObjectPlaceMode.FLOATING);'),
           (1,b'if (densityStyle == null)',b'if (densityStyle != null)'),(1,b'pool.get(random.nextInt(pool.size()))',b'pool.get(0)'),
           (2,b'    FLOATING,',b'    CENTER_HEIGHT,'),
           (3,b'rng.d(-0.005, 0.005)',b'0'),(3,b'entry.getExtraObjects()',b'entry.getFloatingObjects()'),
           (3,b'anchored.setTranslate(new IrisObjectTranslate());',b''),(3,b'inverted.setRotation(invertedRotation);',b''),
           (4,b'f.getName().endsWith(ext)',b'f.getName().toLowerCase().endsWith(ext)'),
           (5,b'int min() default 0;',b'int min() default 1;'),(6,b'private int y = 0;',b'private double y = 0;')]
for index,old,new in mutations:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try: extract(*changed)
    except ValueError:continue
    raise AssertionError('Accepted floating placement drift: '+str(old))
changed=sources.copy();changed[3]=changed[3].replace(b'IrisObjectPlacement floatingPlacement = placement.toPlacement(obj.getLoadKey());',b'IrisObjectPlacement floatingPlacement = placement.toPlacement(obj.getLoadKey()); floatingPlacement.setMode(ObjectPlaceMode.FLOATING);')
try: extract(*changed)
except ValueError: pass
else: raise AssertionError('Accepted new forced floating mode')
print('PASS: floating object list/reference/scalar/mode metadata, per-attempt chance and density override, configured floating mode, anchored transforms and exact .iob catalog bindings reject drift')
