"""Reject changed optional defaults, constraints and stilt/vacuum consumers."""
import sys
from pathlib import Path
from extract_floating_placement_deformation import extract
sources=[Path(path).read_bytes() for path in sys.argv[1:]]
result=extract(*sources).decode()
for token in ['stiltSettings.yMax.default=0\n','stiltSettings.organicScratch.default=0.55\n','stiltSettings.organicMaxScan.maximum=256\n','vacuumSettings.radius.minimum=0\n','vacuumSettings.falloff.minimum=0.25\n','vacuumSettings.waveScale.minimum=0.1\n','stiltSettings.exclude.minimum=1\n']:
    assert token in result,token
mutations=[(0,b'private IrisStiltSettings stiltSettings;',b'private IrisStiltSettings stiltSettings = new IrisStiltSettings();'),
           (0,b'p.setVacuumSettings(vacuumSettings);',b''),(1,b'@MinNumber(1)',b'@Minimum(1)'),
           (1,b'private int yMax;',b'private String yMax;'),(1,b'@ArrayType(min = 1, type = String.class)',b'@ArrayType(type = String.class)'),
           (1,b'block.trim().toLowerCase(Locale.ROOT)',b'block.toLowerCase(Locale.ROOT)'),
           (2,b'@MinNumber(0.25)',b'@Minimum(0.25)'),(2,b'private double waveScale = 5.0;',b'private int waveScale = 5;'),
           (3,b'if (settings.getYMax() != 0)',b'if (settings.getYMax() > 0)'),
           (3,b'settings.getOrganicMaxScan()) : 48',b'settings.getOrganicMaxScan()) : 64'),
           (3,b'vacuumLowest != Integer.MAX_VALUE && placer.getEngine() != null',b'placer.getEngine() != null'),
           (4,b'case STILT -> ObjectPlaceMode.MAX_HEIGHT;',b'case STILT -> ObjectPlaceMode.STILT;'),
           (5,b'case VACUUM_HIGH -> 20;',b'case VACUUM_HIGH -> 24;'),(5,b'settings.getRadius() > 0',b'settings.getRadius() >= 0'),
           (6,b'!state.isStorage() && !state.hasTileEntity()',b'!state.isStorage()')]
for index,old,new in mutations:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try:extract(*changed)
    except ValueError:continue
    raise AssertionError('Accepted deformation drift: '+str(old))
changed=sources.copy();changed[4]+=b'\n anchored.setVacuumSettings(null);'
try:extract(*changed)
except ValueError:pass
else:raise AssertionError('Accepted new anchored settings reset')
print('PASS: stilt/vacuum optional defaults, scalar bounds, exclusion material/container rules, runtime fallback and anchored mode consumers reject drift')
