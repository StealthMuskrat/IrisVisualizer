"""Reject changed effect defaults/bounds and contextual native semantics."""
import sys
from pathlib import Path
from extract_floating_placement_effects import extract
sources=[Path(path).read_bytes() for path in sys.argv[1:]]
result=extract(*sources).decode()
for token in ['fromBottom.default=false\n','boreExtendMinY.minimum=-1\n','boreExtendMaxY.minimum=0\n','boreExtendMinY.maximum=64\n','waterloggable.default=false\n']:
    assert token in result,token
mutations=[(0,b'private boolean fromBottom;',b'private boolean fromBottom = false;'),(0,b'private boolean bore = false;',b'private boolean bore = true;'),
           (0,b'@MinNumber(-1)',b'@Minimum(-1)'),(0,b'private int boreExtendMaxY = 0;',b'private double boreExtendMaxY = 0;'),(0,b'p.setMeld(meld);',b''),
           (1,b'minimumY - config.getBoreExtendMinY()',b'minimumY + config.getBoreExtendMinY()'),(1,b'config.isWaterloggable() || config.isUnderwater()',b'config.isWaterloggable()'),
           (1,b'config.isMeld() && !rawStructurePiece',b'config.isMeld()'),(1,b'(self.getH() + 1) + rty',b'self.getH() + rty'),
           (1,b'!config.isOnwater()',b'true'),(2,b'anchored.setBottom(false);',b''),
           (3,b'config.isSmartBore() ? IrisObjectShaping.smartBoredVariant(this) : this',b'this'),
           (4,b'IrisObject variant = self.copy();',b'IrisObject variant = self;'),
           (5,b'submerged && (enabled || authored)',b'submerged && enabled')]
for index,old,new in mutations:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try: extract(*changed)
    except ValueError: continue
    raise AssertionError('Accepted floating effect drift: '+str(old))
changed=sources.copy();changed[2]+=b'\n anchored.setBore(false);'
try: extract(*changed)
except ValueError: pass
else: raise AssertionError('Accepted new anchored effect override')
print('PASS: excavation/water/anchor defaults and bounds, implicit false, bore clearing, smart-bore copies, meld/raw gates, waterlogging and floating-specific anchor semantics reject drift')
