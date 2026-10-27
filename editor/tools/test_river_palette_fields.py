"""Source mutation tests for palette noise declarations and runtime bindings."""
import sys
from pathlib import Path
from extract_river_palette_fields import extract

sources=[Path(path).read_bytes() for path in sys.argv[1:]]
data=extract(*sources).decode()
for token in ['style.default=STATIC\n','style.objectDefault=FLAT\n','zoom.default=5\n','zoom.minimum=0.0001\n']:
    assert token in data
mutations=[(0,b'@NoArgsConstructor',b''),(0,b'double zoom = 5',b'int zoom = 5'),
           (0,b'@MinNumber(0.0001)',b'@MinNumber(0)'),(0,b'@MinNumber(0.0001)',b'@MinNumber(6)'),
           (0,b'double zoom = 5',b'double zoom = Double.NaN'),(0,b'NoiseStyle.STATIC.style()',b'null'),
           (0,b'NoiseStyle.STATIC.style()',b'NoiseStyle.UNKNOWN_EDITOR_STYLE.style()'),
           (0,b'double scaledX = x / zoom;',b'double scaledX = x * zoom;'),
           (0,b'double scaledY = y / zoom;',b'double scaledY = y;'),
           (0,b'double scaledZ = z / zoom;',b'double scaledZ = z;'),
           (0,b'ignored -> style.create(new RNG(generatorSeed), rdata, engine)',b'ignored -> null'),
           (1,b'this.style = s;',b'this.style = NoiseStyle.STATIC;'),
           (2,b'return new IrisGeneratorStyle(this);',b'return new IrisGeneratorStyle();')]
for index,old,new in mutations:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try:
        extract(*changed)
    except (ValueError,ArithmeticError):
        continue
    raise AssertionError('Accepted source drift: '+str(old))
changed=sources.copy();changed[0]=changed[0].replace(b'NoiseStyle.STATIC.style()',b'NoiseStyle.SIMPLEX.style()')
assert 'style.default=SIMPLEX\n' in extract(*changed).decode()
changed=sources.copy();changed[0]=changed[0].replace(b'double zoom = 5',b'double zoom = 10').replace(b'@MinNumber(0.0001)',b'@MinNumber(0.01)')
assert 'zoom.default=10\n' in extract(*changed).decode() and 'zoom.minimum=0.01\n' in extract(*changed).decode()
print('PASS: palette zoom annotations/defaults, contextual/class style defaults, constructor/factory and coordinate/style bindings reject drift')
