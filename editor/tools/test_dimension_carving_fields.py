"""Reject carving declaration, range, priority, child resolution and clamp drift."""
import sys
from pathlib import Path
from extract_dimension_carving_fields import extract
sources=[Path(path).read_bytes() for path in sys.argv[1:]];result=extract(*sources).decode()
for token in ['childStyle.default=CELLULAR_IRIS_DOUBLE\n','range.context.min=-64\n','range.class.min=16\n','root.policy=lastUsableMatchingRoot\n','depth.maximum=32\n']:assert token in result,token
for index,old,new in [(0,b'index.put(entryId.trim(), entry);',b'index.putIfAbsent(entryId.trim(), entry);'),
                      (1,b'private boolean enabled = true;',b'private boolean enabled = false;'),
                      (1,b'new IrisRange(-64, 320)',b'new IrisRange(16, 32)'),
                      (1,b'private int childRecursionDepth = 3;',b'private int childRecursionDepth = 4;'),
                      (1,b'NoiseStyle.CELLULAR_IRIS_DOUBLE.style()',b'NoiseStyle.IRIS.style()'),
                      (1,b'biomeKey.trim()',b'biomeKey'),(1,b'loaded.isCompatExcluded()',b'false'),
                      (1,b'0.0001D',b'0.001D'),(2,b'MAX_CHILD_DEPTH = 32',b'MAX_CHILD_DEPTH = 64'),
                      (2,b'resolved = entry;',b'return entry;'),
                      (2,b'entryIndex.get(childId.trim())',b'entryIndex.get(childId)'),
                      (2,b'options.add(new CarvingChoice(parent, rarity(parentBiome)));',b''),
                      (2,b'if (childDepthLimit < depth)',b'if (false)'),
                      (3,b'private double min = 16;',b'private double min = 0;'),
                      (3,b'return v >= min && v <= max;',b'return v >= min && v < max;')]:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try:extract(*changed)
    except ValueError:continue
    raise AssertionError('Accepted carving drift: '+str(old))
print('PASS: dimension carving defaults, last matching root, inclusive world Y, ID lookup, rarity parent/children, depth/shrink clamps and compatibility reject drift')
