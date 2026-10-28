"""Detect drift in defaults, bounds, mode factories and generation/Studio consumers."""
import sys
from pathlib import Path
from extract_dimension_generation_fields import extract
sources=[Path(path).read_bytes() for path in sys.argv[1:]]
result=extract(*sources).decode()
for token in ['useMantle.default=true\n','type.default=OVERWORLD\n','studioMode.default=NORMAL\n','debugCrossSectionsMod.minimum=2\n','explodeBiomePaletteSize.maximum=16\n','studio.policy=serverOnly\n']:assert token in result,token
for index,old,new in [(0,b'private boolean useMantle = true;',b'private boolean useMantle = false;'),
                      (0,b'private int debugCrossSectionsMod = 3;',b'private int debugCrossSectionsMod = 0;'),
                      (0,b'@MinNumber(2)',b'@MinNumber(1)'),(0,b'@MaxNumber(16)',b'@MaxNumber(32)'),
                      (0,b'mode.getType() == IrisDimensionModeType.OVERWORLD',b'mode.getType() != IrisDimensionModeType.OVERWORLD'),
                      (1,b'IrisDimensionModeType.OVERWORLD',b'IrisDimensionModeType.SUPERFLAT'),
                      (2,b'ISLANDS(ModeIslands::new)',b'ISLANDS(ModeOverworld::new)'),
                      (3,b'case BIOME_BUFFET_3x3 -> 3;',b'case BIOME_BUFFET_3x3 -> 9;'),
                      (3,b'case NORMAL, REGION_BUFFET -> c.setStudioGenerator(null);',b'case NORMAL -> c.setStudioGenerator(null);'),
                      (4,b'if (!getEngine().getDimension().isUseMantle())',b'if (getEngine().getDimension().isUseMantle())'),
                      (5,b'(x >> 4) % getDimension().getDebugCrossSectionsMod()',b'x % getDimension().getDebugCrossSectionsMod()'),
                      (6,b'j < dim.getExplodeBiomePaletteSize()',b'j <= dim.getExplodeBiomePaletteSize()'),
                      (7,b'hideOres ? IrisOreBands.EMPTY',b'false ? IrisOreBands.EMPTY')]:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try:extract(*changed)
    except ValueError:continue
    raise AssertionError('Accepted generation contract drift: '+str(old))
print('PASS: generation defaults/bounds, mode factories, Studio enum/grid/injection, mantle, cross-section, palette and ore consumers reject drift')
