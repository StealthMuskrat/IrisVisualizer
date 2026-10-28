"""Reject underground feature defaults, bounds, identities, family rules and native gating drift."""
import sys
from pathlib import Path
from extract_subterrain_feature_fields import extract
sources=[Path(path).read_bytes() for path in sys.argv[1:]];result=extract(*sources).decode()
for token in ['family.default=CENOTE\n','spacing.default=512\n','formationFraction.maximum=0.4\n','fluid.default=null\n','range.context.min=-48\n','id.policy=trimmedCaseSensitive\n']:assert token in result,token
for index,old,new in [(0,b'!disabledComponents.contains(ReservedFlag.CARVED)',b'true'),
                      (1,b'new IrisRange(-48, 48)',b'new IrisRange(16, 32)'),(1,b'private int spacing = 512;',b'private int spacing = 256;'),
                      (1,b'@MaxNumber(0.4)',b'@MaxNumber(1)'),(1,b'private IrisSubterrainFluid fluid;',b'private IrisSubterrainFluid fluid = IrisSubterrainFluid.WATER;'),
                      (2,b'    CENOTE',b'    CAVERN'),(3,b'return IrisSubterrainFluid.valueOf(value);',b'return IrisSubterrainFluid.valueOf(value.toUpperCase());'),
                      (4,b'private double min = 16;',b'private double min = -48;'),
                      (5,b'options.features().size() > 64',b'options.features().size() > 128'),
                      (5,b'feature.getSpacing() < Math.max(32, 2 * horizontalReach + 8)',b'feature.getSpacing() < 32'),
                      (5,b'feature.getFluidDepth() > Math.min(32, height / 3)',b'feature.getFluidDepth() > 32'),
                      (5,b'length < 200',b'length < 100'),(5,b'[a-zA-Z0-9_/-]+',b'[a-zA-Z0-9_./-]+'),
                      (5,b'Math.ceil(feature.getWorldYRange().getMin())',b'Math.floor(feature.getWorldYRange().getMin())'),
                      (5,b'family == IrisSubterrainFamily.LAVA_TUBE ? IrisSubterrainFluid.LAVA : IrisSubterrainFluid.WATER',b'IrisSubterrainFluid.WATER'),
                      (6,b'engine.getDimension().allowsSubterrainFeatures() ? engine.getDimension().getSubterrainFeatures() : List.of()',b'engine.getDimension().getSubterrainFeatures()')]:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try:extract(*changed)
    except ValueError:continue
    raise AssertionError('Accepted subterrain drift: '+str(old))
print('PASS: underground feature defaults/bounds/families/fluid adapter, IDs/references/material syntax, ordered world Y, coupled extent/depth constraints, native cap/priority and dimension gating reject drift')
