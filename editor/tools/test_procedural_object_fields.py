"""Reject collection, default, bounds, enum and placement dispatch drift."""
import sys
from pathlib import Path
from extract_procedural_object_fields import extract
sources=[Path(p).read_bytes() for p in sys.argv[1:]];result=extract(*sources).decode()
for token in ['collections=trees,ruins,formations,coral,fungi,crystals\n','trees.variants.default=8\n','ruins.mode.default=MIN_HEIGHT\n','coral.underwater.default=true\n','crystals.carvingSupport.default=CARVING_ONLY\n','formations.surfaceSupportBuffer.maximum=16\n','seed.policy=exactInt64\n','chance.policy=perChunkBeforeDensity\n']:assert token in result,token
for index,old,new in [(0,b'private IrisProceduralObjects proceduralObjects = new IrisProceduralObjects();',b'private IrisProceduralObjects proceduralObjects = null;'),(2,b'all.addAll(crystals);',b'all.addAll(trees);'),(3,b'private int variants = 8;',b'private int variants = 6;'),(4,b'private ObjectPlaceMode mode = ObjectPlaceMode.MIN_HEIGHT;',b'private ObjectPlaceMode mode = ObjectPlaceMode.CENTER_HEIGHT;'),(5,b'@MaxNumber(16)',b'@MaxNumber(17)'),(6,b'private boolean underwater = true;',b'private boolean underwater = false;'),(7,b'placement.setDensity(density);',b'placement.setDensity(1);'),(8,b'CarvingMode.CARVING_ONLY;',b'CarvingMode.SURFACE_ONLY;'),(9,b'    FLOATING,',b'    SKY,'),(10,b'    ANYWHERE;',b'    BOTH;'),(11,b'passesProceduralChance(rng, p.getChance())',b'passesProceduralChance(rng, 1)'),(12,b'IrisObjectPlacement asPlacement();',b'IrisObjectPlacement placement();')]:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try:extract(*changed)
    except ValueError:continue
    raise AssertionError('Accepted procedural placement drift '+str(old))
print('PASS: six procedural collections, host/default distinctions, shared placement bounds, exact seed contract, all modes/carvings and native per-chunk chance/density dispatch reject drift')
