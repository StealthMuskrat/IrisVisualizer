"""Detect scheduling, identity, source selection and backend contract drift."""
import sys
from pathlib import Path
from extract_structure_placement_fields import extract
sources=[Path(path).read_bytes() for path in sys.argv[1:]];result=extract(*sources).decode()
for token in ['spacing.default=32\n','salt.default=165745296\n','density.default=0.02\n','anchor.default=LEGACY\n','backend.policy=exactlyOneBackend\n','advanced.policy=jigsawReadOnly\n']:assert token in result,token
for index,old,new in [(0,b'private KList<IrisStructurePlacement> structures = new KList<>();',b'private KList<IrisStructurePlacement> structures;'),
                      (3,b'private int spacing = 32;',b'private int spacing = 16;'),(3,b'private int ringCount = 128;',b'private int ringCount = 64;'),
                      (3,b'@MaxNumber(4096)',b'@MaxNumber(8192)'),(3,b'private double density = 0.02;',b'private double density = 0.01;'),
                      (3,b'underground ? IrisStructureAnchorMode.HEIGHT_BAND : IrisStructureAnchorMode.SURFACE',b'underground ? IrisStructureAnchorMode.SURFACE : IrisStructureAnchorMode.HEIGHT_BAND'),
                      (4,b'private int weight = 1;',b'private int weight = 2;'),(5,b'    DENSITY,',b'    DENSE,'),(6,b'    CAVE_FLOOR,',b'    CAVE_BOTTOM,'),
                      (7,b'    REPLACE_SOURCE\n',b'    REPLACE\n'),(8,b'separation >= spacing',b'separation > spacing'),
                      (8,b'Math.ceilDiv(ringCount, ringSpread)',b'Math.floorDiv(ringCount, ringSpread)'),
                      (8,b'String normalizedId = placementId.trim();',b'String normalizedId = placementId.toLowerCase();'),
                      (9,b'if (iris == nativeStructure)',b'if (iris && nativeStructure)'),(9,b'if (totalWeight > Integer.MAX_VALUE)',b'if (totalWeight < 1)'),
                      (10,b'STRUCTURES_FOLDER = "structures";',b'STRUCTURES_FOLDER = "assemblies";')]:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try:extract(*changed)
    except ValueError:continue
    raise AssertionError('Accepted structure placement drift: '+str(old))
print('PASS: structure placement ownership, defaults/types/bounds/enums, exact backend, grid/height/ring and ID constraints, native anchor/weight and pack source folder contracts reject drift')
