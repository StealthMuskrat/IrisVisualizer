"""Reject drift in fallback collection, alias overlap and native resolution semantics."""
import sys
from pathlib import Path
from extract_block_fallback_fields import extract

sources=[Path(path).read_bytes() for path in sys.argv[1:]]
result=extract(*sources).decode()
for token in ['scope.policy=packTopLevelDimensionMaps\n','source.policy=trimLowerBaseDefaultNamespace\n','resolution.policy=registryRenameSingleFallback\n','winner.policy=unspecifiedIteration\n']:assert token in result,token
mutations=[(0,b'private KMap<String, String> blockFallbacks = new KMap<>();',b'private KMap<String, String> blockFallbacks;'),
           (1,b'() -> readBlockFallbacks(folder)',b'() -> Map.of()'),(1,b'name.endsWith(".json")',b'name.endsWith(".JSON")'),
           (1,b'merged.putIfAbsent(key, state);',b'merged.put(key, state);'),(1,b'!state.isBlank()',b'!state.isEmpty()'),
           (1,b'for (File file : dimensions)',b'Arrays.sort(dimensions); for (File file : dimensions)'),
           (1,b'normalized.put(baseKey(key), entry.getValue());',b'normalized.put(key, entry.getValue());'),
           (1,b'toLowerCase(Locale.ROOT)',b'toLowerCase(Locale.US)'),(1,b'DEFAULT_NAMESPACE = "minecraft"',b'DEFAULT_NAMESPACE = "custom"'),
           (1,b'String trimmed = state.trim();',b'String trimmed = state;'),(1,b'lookup(normalizedFallback)',b'lookup(normalized)'),
           (1,b'BlockResolution renamed = resolveRename(normalized);',b'BlockResolution renamed = null;'),
           (2,b'return backup.getBlockData(data);',b'return B.getAirState();'),
           (3,b'gate.resolveBlockOrPlaceholder(key)',b'gate.resolveBlockOrPlaceholder("air")')]
for index,old,new in mutations:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try:extract(*changed)
    except ValueError:continue
    raise AssertionError('Accepted fallback source drift: '+str(old))
print('PASS: pack-wide top-level fallback collection, unspecified file order, blank-target filtering, exact/normalized collision semantics, registry/rename/single-target precedence and block/object consumers reject drift')
