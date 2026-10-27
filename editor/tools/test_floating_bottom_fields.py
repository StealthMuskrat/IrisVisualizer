"""Reject native bottom palette shape, gating and selection source drift."""
import sys
from pathlib import Path
from extract_floating_bottom_fields import extract
sources=[Path(path).read_bytes() for path in sys.argv[1:]]
result=extract(*sources).decode()
for token in ['list.default=[]\n','mode=CUSTOM\n','nullList=emptyFallback\n','nullSlope=noGate\n','block.default=GRASS_BLOCK\n','style.default=STATIC\n']:
    assert token in result
mutations=[(0,b'min = 1, type = IrisBiomePaletteLayer.class',b'min = 0, type = IrisBiomePaletteLayer.class'),
           (0,b'bottomPalette = new KList<>()',b'bottomPalette = null'),
           (1,b'entry.getBottomPaletteMode() != FloatingBottomPaletteMode.CUSTOM',b'entry.getBottomPaletteMode() != FloatingBottomPaletteMode.DEPTH'),
           (1,b'entry.getBottomPalette() == null',b'false'),
           (1,b'layer.getMinHeight(), layer.getMaxHeight()',b'1, 1'),
           (1,b'slopeClip != null && !slopeClip.isDefault()',b'!slopeClip.isDefault()'),
           (1,b'bottomDepth < topDepth',b'bottomDepth <= topDepth'),
           (1,b'paletteBlock(topBlocks, bottomDepth, fallbackSolid)',b'paletteBlock(topBlocks, topDepth, fallbackSolid)'),
           (1,b'(wx + j) / layer.getZoom()',b'(wx + j) * layer.getZoom()'),
           (2,b'new IrisBlockData("GRASS_BLOCK")',b'new IrisBlockData("STONE")'),
           (2,b'NoiseStyle.STATIC.style()',b'NoiseStyle.FLAT.style()'),
           (3,b'minimumSlope <= 0 && maximumSlope >= 10',b'minimumSlope == 0 && maximumSlope == 10')]
for index,old,new in mutations:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try: extract(*changed)
    except ValueError:continue
    raise AssertionError('Accepted bottom palette source drift: '+str(old))
print('PASS: floating underside list/defaults, CUSTOM/underside selection, empty/null fallbacks, thickness/zoom/style/block bindings and nullable slope gates reject drift')
