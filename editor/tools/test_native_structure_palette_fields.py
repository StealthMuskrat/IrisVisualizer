"""Reject default, ownership, weighted expansion and palette sampling contract drift."""
import sys
from pathlib import Path
from extract_native_structure_palette_fields import extract
sources=[Path(path).read_bytes() for path in sys.argv[1:]];result=extract(*sources).decode()
for token in ['stilt.absent=minecraft:cobblestone\n','terrain.absent=environmentFallback\n','palette.classBlock=STONE\n','palette.classStyle=STATIC\n','palette.classZoom=5\n','weight.maximum=1000\n','registry.policy=unverified\n']:assert token in result,token
for index,old,new in [(0,b'minecraft:cobblestone',b'minecraft:stone'),(1,b'private IrisMaterialPalette encasePalette = null;',b'private IrisMaterialPalette encasePalette = new IrisMaterialPalette();'),
                      (2,b'NoiseStyle.STATIC.style()',b'NoiseStyle.IRIS.style()'),(2,b'private double zoom = 5;',b'private double zoom = 1;'),
                      (2,b'new IrisBlockData("STONE")',b'new IrisBlockData("AIR")'),(2,b'@MinNumber(0.0001)',b'@MinNumber(0)'),
                      (2,b'@ArrayType(min = 1',b'@ArrayType(min = 0'),(2,b'i < ix.getWeight()',b'i <= ix.getWeight()'),
                      (2,b'double scaledY = y / zoom;',b'double scaledY = y;'),(3,b'@MaxNumber(1000)',b'@MaxNumber(2000)'),
                      (4,b'private IrisStructureStiltSettings stilt = null;',b'private IrisStructureStiltSettings stilt = new IrisStructureStiltSettings();'),
                      (5,b'if (adjustment.getTerrain() != null)',b'if (terrain == null)'),(6,b'StructurePalette getPalette();',b'String getPalette();'),(7,b'StructurePalette getEncasePalette();',b'String getEncasePalette();')]:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try:extract(*changed)
    except ValueError:continue
    raise AssertionError('Accepted structure palette drift: '+str(old))
print('PASS: native foundation/context/class palette defaults, weighted entry and zoom limits/sampling, optional owner and last-override semantics and native palette interfaces reject drift')
