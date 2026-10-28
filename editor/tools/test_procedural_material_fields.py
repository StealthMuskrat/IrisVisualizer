"""Pin role defaults, source palette precedence and shared class defaults/bounds."""
import sys
from extract_procedural_material_fields import extract,read_sources
sources=read_sources(sys.argv[1]);baseline=extract(sources).decode()
for token in ['trees.trunk.default=minecraft:oak_log\n','trees.secondaryLeaves.default=null\n','formations.capPalette.default=null\n','fungi.spotBlock.default=null\n','crystals.baseBlock.default=minecraft:budding_amethyst\n','palette.classBlock=STONE\n','palette.classStyle=STATIC\n','palette.classZoom=5\n','palette.activation=nonempty\n','stilt.absentOrNull=objectLayerFallback\n','resolution.policy=notExecuted\n']:assert token in baseline,token
for name,old,new in [('IrisProceduralTree',b'private IrisMaterialPalette trunkPalette = null;',b'private IrisMaterialPalette trunkPalette = new IrisMaterialPalette();'),('IrisFungus',b'private String spotBlock = null;',b'private int spotBlock = 0;'),('IrisMaterialPalette',b'NoiseStyle.STATIC.style()',b'NoiseStyle.SIMPLEX.style()'),('IrisBlockData',b'private int weight = 1;',b'private int weight = 2;'),('IrisProceduralBlocks',b'!palette.getPalette().isEmpty()',b'true'),('IrisStiltSettings',b'private IrisMaterialPalette palette;',b'private String palette;'),('TreeBlockResolver',b'tree.getSecondaryLeavesPalette()',b'null'),('FormationBlockResolver',b'getStrataPalette()',b'getBlockPalette()'),('CoralGenerator',b'coral.getTipPalette()',b'coral.getBlockPalette()')]:
    assert old in sources[name],old
    changed=sources.copy();changed[name]=changed[name].replace(old,new)
    try:extract(changed)
    except ValueError:continue
    raise AssertionError('Accepted procedural material drift '+name)
print('PASS: eighteen single material roles/eighteen optional palettes, six stilt palette contexts, native role/nonempty precedence and resolver dispatch, class STONE/STATIC/zoom defaults and shared weight/zoom bounds')
