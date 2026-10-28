"""Reject backend restrictions, absent/null defaults and native dispatch drift."""
import sys
from pathlib import Path
from extract_structure_placement_integration_fields import extract
sources=[Path(path).read_bytes() for path in sys.argv[1:]];result=extract(*sources).decode()
for token in ['iris.modes=SOURCE,PRESERVE,BORE,FORCE_CARVE\n','terrain.absentOrNull=defaultSource\n','stilt.absentOrNull=noFoundation\n','palette.scope=hostAndPlacementOwner\n','runtime.policy=notEvaluated\n']:assert token in result,token
for index,old,new in [(0,b'private IrisStructureTerrain terrain = new IrisStructureTerrain();',b'private IrisStructureTerrain terrain = null;'),
                      (0,b'terrain == null ? new IrisStructureTerrain() : terrain',b'terrain'),
                      (1,b'private StructureTerrainMode mode = StructureTerrainMode.SOURCE;',b'private StructureTerrainMode mode = StructureTerrainMode.PRESERVE;'),
                      (2,b'minecraft:cobblestone',b'minecraft:stone'),(2,b'private int spacing = 1;',b'private int spacing = 2;'),
                      (3,b'"VACUUM".equals(mode) || "FLATTEN".equals(mode) || "ENCASE".equals(mode)',b'"VACUUM".equals(mode)'),
                      (3,b'stilt, "maxDepth", 1, 4064',b'stilt, "maxDepth", 1, 4096'),
                      (4,b'placement.getStilt(),',b'null,')]:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try:extract(*changed)
    except ValueError:continue
    raise AssertionError('Accepted placement integration drift: '+str(old))
print('PASS: placement terrain/source defaults, optional foundations, Iris/native terrain mode distinction, foundation bounds and native dispatch reject drift')
