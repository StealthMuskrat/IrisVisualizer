"""Reject default, choice, validator and native dispatch drift."""
import sys
from pathlib import Path
from extract_native_jigsaw_fields import extract
sources=[Path(path).read_bytes() for path in sys.argv[1:]];result=extract(*sources).decode()
for token in ['maxDepth.minimum=0\n','maxDepth.maximum=20\n','maxDistanceHorizontal.maximum=128\n','maxDistanceVertical.maximum=4064\n','dimensionPaddingTop.maximum=2147483647\n','reset.policy=removeField\n','integer.null=inherit\n','other.null=repairOrRemove\n','execution.policy=disabled\n']:assert token in result,token
for index,old,new in [(0,b'private Integer maxDepth = null;',b'private Integer maxDepth = 7;'),(0,b'private String startPool = "";',b'private String startPool = "mod:pool";'),(0,b'|| useExpansionHack != null',b'|| false'),(0,b'@MaxNumber(20)',b'@MaxNumber(21)'),(1,b'return this == NONE;',b'return false;'),(2,b'    IGNORE_WATERLOGGING,',b'    DRY,'),(3,b'"maxDistanceHorizontal", 1, 128',b'"maxDistanceHorizontal", 1, 256'),(3,b'"startJigsawName", true',b'"startJigsawName", false'),(3,b'instanceof Boolean',b'instanceof String'),(3,b'if (assemblySpan > 128L)',b'if (assemblySpan > 256L)'),(4,b'!object.has(field) || object.opt(field) == JSONObject.NULL',b'!object.has(field)'),(5,b'return source.getJigsaw();',b'return null;')]:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try:extract(*changed)
    except ValueError:continue
    raise AssertionError('Accepted native jigsaw drift: '+str(old))
print('PASS: native jigsaw defaults, inheritance/null distinctions, assembly bounds, connector NONE, projection/liquid choices and start dispatch reject drift')
