"""Mutation checks for profile list, ID and native fluid constraints (five pinned source paths)."""
import sys
from pathlib import Path
from extract_river_profile_fields import extract
sources=[Path(path).read_bytes() for path in sys.argv[1:]]
data=extract(*sources).decode()
for token in ['profiles.minimum=1\n','profiles.maximum=64\n','id.default=default\n','fluid.default=water\n','weight.maximum=64\n','fluid.rule=invertedDefinitelyNotFluid\n']:
    assert token in data
mutations=[(0,b'min = 1, type = IrisRiverProfile.class',b'min = 0, type = IrisRiverProfile.class'),
           (0,b'.qadd(new IrisRiverProfile())',b''),(1,b'String id = "default"',b'String id = "other"'),
           (1,b'.qadd("water")',b'.qadd("lava")'),(1,b'@Required',b''),
           (2,b'palette.clear();',b'palette.size();'),(2,b'palette.add(new IrisBlockData(b));',b'palette.clear();'),
           (3,b'this.block = b;',b'this.block = "water";'),(3,b'int weight = 1',b'int weight = 2'),
           (4,b'MAX_PROFILES = 64',b'MAX_PROFILES = 65'),(4,b'[a-z0-9][a-z0-9_-]{0,63}',b'[a-z0-9_-]+'),
           (4,b'if (enabled && profileIds.isEmpty())',b'if (profileIds.isEmpty())'),
           (4,b'if (id != null && !profileIds.add(id))',b'if (id == null)'),
           (4,b'validateFluidPalette(profilePath, profile, true, errors);',b'validateFluidPalette(profilePath, profile, false, errors);'),
           (4,b'if (entries.length() > 64)',b'if (entries.length() > 65)'),(4,b'else if (definitelyNotFluid(block))',b'else if (!definitelyNotFluid(block))'),
           (4,b'"weight", 1, 64',b'"weight", 1, 1000'),(4,b'!"water".equals(key)',b'!"stone".equals(key)'),
           (4,b'|| !ID_PATTERN.matcher(id).matches()',b'|| id.isBlank()'),
           (4,b'entries.length() == 0',b'entries.length() < 0')]
for index,old,new in mutations:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try:
        extract(*changed)
    except ValueError:
        continue
    raise AssertionError('Accepted source drift: '+str(old))
for index in range(4):
    changed=sources.copy();changed[index]=changed[index].replace(b'@NoArgsConstructor',b'')
    try:
        extract(*changed)
    except ValueError:
        continue
    raise AssertionError('Accepted changed constructor')
print('PASS: river profile defaults, required IDs/palettes, native uniqueness/list/weight limits and fluid filter reject drift')
