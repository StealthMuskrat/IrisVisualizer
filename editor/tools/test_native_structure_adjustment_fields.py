"""Detect native structure adjustment defaults, bounds, matching and resolution drift."""
import sys
from pathlib import Path
from extract_native_structure_adjustment_fields import extract
sources=[Path(path).read_bytes() for path in sys.argv[1:]];result=extract(*sources).decode()
for token in ['yShift.default=0\n','stilt.maxDepth.default=64\n','terrain.mode.default=SOURCE\n','terrain.erosionFrequency.default=0.07\n','terrain.lobeFrequency.default=0\n','resolution.policy=stackShiftsLastNonNullOverrides\n','band.policy=sortedEndpoints\n','palettes.policy=readOnly\n']:assert token in result,token
for index,old,new in [(0,b'private IrisImportedStructureControl importedStructures = new IrisImportedStructureControl();',b'private IrisImportedStructureControl importedStructures;'),
                      (1,b'y += adjustment.getYShift();',b'y = adjustment.getYShift();'),(1,b'preserveSourceY |= adjustment.isPreserveSourceY();',b'preserveSourceY = adjustment.isPreserveSourceY();'),
                      (1,b'if (adjustment.getTerrain() != null)',b'if (terrain == null)'),(2,b'private int yShift = 0;',b'private int yShift = 1;'),
                      (2,b'@MinNumber(-512)',b'@MinNumber(-4064)'),(2,b'IrisImportedStructureControl.matchesKey(entry, key)',b'entry.equals(key)'),
                      (3,b'minecraft:cobblestone',b'minecraft:stone'),(3,b'private int spacing = 1;',b'private int spacing = 2;'),
                      (4,b'DEFAULT_EROSION_FREQUENCY = 0.07D',b'DEFAULT_EROSION_FREQUENCY = 0.1D'),(4,b'private int flattenRange = 64;',b'private int flattenRange = 0;'),
                      (4,b'resolvedErosionFrequency() * AUTO_LOBE_FREQUENCY_RATIO',b'resolvedErosionFrequency()'),(5,b'Math.min(min, max)',b'min'),
                      (6,b'"floorPadding", 0, 64',b'"floorPadding", 0, 128'),(7,b'"min", -4064, 4064',b'"min", 0, 4064'),
                      (8,b'    ENCASE\n',b'    FILL\n'),(9,b'    ERODED;',b'    ROUND;'),(10,b'!(rawValue instanceof String value)',b'(rawValue instanceof String value)')]:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try:extract(*changed)
    except ValueError:continue
    raise AssertionError('Accepted native adjustment drift: '+str(old))
print('PASS: native structure adjustment defaults/bounds, matching, stacked shifts/OR/last override resolution, sorted Y bands, terrain enums/erosion defaults and read-only palette contracts reject drift')
