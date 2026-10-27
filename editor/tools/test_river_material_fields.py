"""Mutation tests for the pinned material and palette source audit (seven source paths)."""
import sys
from pathlib import Path
from extract_river_material_fields import extract

sources=[Path(path).read_bytes() for path in sys.argv[1:]]
data=extract(*sources).decode()
for token in ['depth.maximum=8\n','weight.maximum=64\n','weight.sourceAnnotationMaximum=1000\n','palette.blocks=STONE\n','shore.depth=2\n','shore.blocks=sand\n','padding.blocks=clay,dirt\n']:
    assert token in data
mutations=[(0,b'boolean enabled = false',b'boolean enabled = true'),(0,b'int depth = 1',b'int depth = 2'),
           (0,b'@MaxNumber(8)',b'@MaxNumber(9)'),(0,b'new IrisMaterialPalette()',b'null'),
           (1,b'new IrisBlockData("STONE")',b'new IrisBlockData("sand")'),(1,b'min = 1',b'min = 0'),
           (1,b'palette.clear();',b'palette.size();'),(1,b'palette.add(new IrisBlockData(b));',b'palette.clear();'),
           (2,b'int weight = 1',b'int weight = 2'),(2,b'@MaxNumber(1000)',b'@MaxNumber(64)'),
           (2,b'this.block = b;',b'this.block = "stone";'),(3,b'.setDepth(2)',b'.setDepth(3)'),
           (3,b'.qadd("sand")',b'.qadd("stone")'),(4,b'.qadd("dirt")',b'.qadd("sand")'),
           (5,b'new IrisRiverMaterialConfig()',b'null'),(6,b'"weight", 1, 64',b'"weight", 1, 1000'),
           (6,b'"depth", 1, 8',b'"depth", 0, 8'),(6,b'entries.length() == 0',b'entries.length() < 0'),
           (6,b'|| block.isBlank()',b'|| block.isEmpty()'),(6,b'!"water".equals(key)',b'!"stone".equals(key)'),
           (6,b'trim().toLowerCase(Locale.ROOT)',b'toLowerCase(Locale.ROOT)'),
           (6,b'validateMaterial(path, banks, "shoreMaterial", errors);',b'')]
for index,old,new in mutations:
    assert old in sources[index],old
    changed=sources.copy(); changed[index]=changed[index].replace(old,new)
    try:
        extract(*changed)
    except ValueError:
        continue
    raise AssertionError('Accepted source drift: '+str(old))
for index in range(6):
    changed=sources.copy(); changed[index]=changed[index].replace(b'@NoArgsConstructor',b'')
    try:
        extract(*changed)
    except ValueError:
        continue
    raise AssertionError('Accepted changed constructor')
print('PASS: river material contextual defaults, weighted palette shape and native namespace/fluid/bound contracts reject drift')
