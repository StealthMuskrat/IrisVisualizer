"""General palette audits must retain constructor semantics and annotation-derived weights."""
import sys
from pathlib import Path
from extract_dimension_palette_fields import extract
sources=[Path(path).read_bytes() for path in sys.argv[1:]]
data=extract(*sources).decode()
for value in ['rockPalette.block=stone\n','fluidPalette.block=water\n','palette.classBlock=STONE\n','palette.minimum=1\n','weight.default=1\n','weight.minimum=1\n','weight.maximum=1000\n']:
    assert value in data
mutations=[(0,b'private IrisMaterialPalette rockPalette = new IrisMaterialPalette().qclear().qadd("stone");',b'private IrisMaterialPalette rockPalette = null;'),
           (1,b'palette.clear();',b'palette.size();'),(1,b'palette.add(new IrisBlockData(b));',b'palette.clear();'),
           (1,b'min = 1, type = IrisBlockData.class',b'min = 0, type = IrisBlockData.class'),(1,b'@Required',b''),
           (2,b'this.block = b;',b'this.block = "stone";'),(2,b'private int weight = 1;',b'private double weight = 1;'),(2,b'@RegistryListBlockType',b'')]
for index,old,new in mutations:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try: extract(*changed)
    except ValueError: continue
    raise AssertionError('Accepted source drift: '+str(old))
for index in range(3):
    changed=sources.copy();changed[index]=changed[index].replace(b'@NoArgsConstructor',b'')
    try: extract(*changed)
    except ValueError: continue
    raise AssertionError('Accepted changed constructors')
changed=sources.copy();changed[2]=changed[2].replace(b'@MaxNumber(1000)',b'@MaxNumber(64)')
assert 'weight.maximum=64\n' in extract(*changed).decode()
print('PASS: general dimension palette defaults, list/entry constructors and source annotation weight bounds reject drift')
