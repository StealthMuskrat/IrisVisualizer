"""Reject changed rule schema, palette bounds and material-pass consumers."""
import sys
from pathlib import Path
from extract_floating_object_materials import extract
sources=[Path(path).read_bytes() for path in sys.argv[1:]]
result=extract(*sources).decode()
for token in ['chance.minimum=0\n','chance.maximum=1\n','exact.default=false\n','find.minimum=1\n','palette.block=STONE\n','palette.style=STATIC\n','palette.zoom.minimum=0.0001\n','weight.maximum=1000\n']:
    assert token in result,token
mutations=[(0,b'p.setEdit(edit);',b''),(1,b'private IrisMaterialPalette palette;',b'private IrisMaterialPalette palette = new IrisMaterialPalette();'),
           (2,b'@Required',b'@Optional'),(2,b'private float chance = 1;',b'private double chance = 1;'),(2,b'getBlockDataOrPlaceholder(rdata)',b'getBlockData(rdata)'),
           (3,b'NoiseStyle.STATIC.style()',b'NoiseStyle.FLAT.style()'),(3,b'new IrisBlockData("STONE")',b'new IrisBlockData("DIRT")'),
           (4,b'@MaxNumber(1000)',b'@Maximum(1000)'),(5,b'rng.chance(j.getChance())',b'true'),
           (5,b'k.matches(data)',b'k.equals(data)'),(5,b'getPalette().get(rng, x, y, z, rdata)',b'getPalette().get(rng, xx, yy, zz, rdata)'),
           (6,b'!state.isStorage() && !state.hasTileEntity()',b'!state.isStorage()')]
for index,old,new in mutations:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try:extract(*changed)
    except ValueError:continue
    raise AssertionError('Accepted object material drift: '+str(old))
print('PASS: object replacement schema/defaults, required find entries, unweighted find versus weighted palette sampling, chance/state matching and stilt material/exclusion consumers reject drift')
