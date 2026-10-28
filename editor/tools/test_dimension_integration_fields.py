"""Reject borrowed type, URL-source and serialized mantle flag contract drift."""
import sys
from pathlib import Path
from extract_dimension_integration_fields import extract
sources=[Path(path).read_bytes() for path in sys.argv[1:]];result=extract(*sources).decode()
for token in ['borrowed.policy=moddedOnly\n','custom.minimum=64\n','custom.maximum=255\n','datapackImports.null=emptySources\n','source.policy=syntaxOnlyNoIO\n','registry.policy=unverified\n']:assert token in result,token
for index,old,new in [(0,b'private String borrowedDimensionType = "";',b'private String borrowedDimensionType = "minecraft:overworld";'),
                      (0,b'!disabledComponents.contains(ReservedFlag.CARVED)',b'!disabledComponents.contains(ReservedFlag.REAL)'),
                      (1,b'!RESOURCE_KEY.matcher(key).matches()',b'!RESOURCE_KEY.matcher(key.trim()).matches()'),
                      (2,b'maxHeight() <= actual.maxHeight()',b'maxHeight() == actual.maxHeight()'),
                      (2,b'activeBorrowed.equals(replacementBorrowed)',b'activeBorrowed.equalsIgnoreCase(replacementBorrowed)'),
                      (3,b'default boolean supportsBorrowedDimensionTypes() {\n        return false;',b'default boolean supportsBorrowedDimensionTypes() {\n        return true;'),
                      (4,b'public boolean supportsBorrowedDimensionTypes() {\n        return true;',b'public boolean supportsBorrowedDimensionTypes() {\n        return false;'),
                      (5,b'String normalized = source.trim();',b'String normalized = source;'),
                      (5,b'if (imports == null)',b'if (imports.isEmpty())'),
                      (6,b'source.getRawAuthority() != null',b'source.getHost() != null'),
                      (6,b'!"https".equalsIgnoreCase(current.getScheme())',b'!"ftp".equalsIgnoreCase(current.getScheme())'),
                      (7,b'new URI(source.trim())',b'new URI(source)'),
                      (8,b'disabled.addAll(getDimension().getDisabledComponents());',b'disabled.clear();'),
                      (9,b'.map(MantleFlag::toString)',b'.map(MantleFlag::name)'),
                      (10,b'int MIN_ORDINAL = 64;',b'int MIN_ORDINAL = 32;'),(10,b'int MAX_ORDINAL = 255;',b'int MAX_ORDINAL = 256;'),
                      (11,b'ReservedFlag.valueOf(s)',b'ReservedFlag.valueOf(s.toUpperCase())'),
                      (12,b'    RIVER_HYDROLOGY;',b'    HYDROLOGY;'),
                      (13,b'.registerTypeAdapter(MantleFlag.class, new MantleFlagAdapter())',b'.registerTypeAdapter(MantleFlag.class, null)')]:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try:extract(*changed)
    except ValueError:continue
    raise AssertionError('Accepted dimension integration drift: '+str(old))
print('PASS: borrowed type defaults/key/platform/height/hotload, trimmed/null/discovered datapack sources and URI protocol/file rules, live component discovery, reserved/custom flag adapter/ordinal and engine consumers reject drift')
