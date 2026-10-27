"""Pin pack-wide fallback collection, source normalization and nonrecursive resolution."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact

def extract(dimension,gate,block,object_io):
    sources=[dimension,gate,block,object_io]
    d,g,b,o=[raw.decode('utf-8') for raw in sources]
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text):raise ValueError('Changed block fallback contract: '+contract)
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for name,raw in zip(['dimension','gate','block','objectIO'],sources):values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    match=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private KMap<String, String> blockFallbacks = new KMap<>\(\);',d)
    if not match:raise ValueError('Changed fallback map declaration')
    values['description']=json.loads(match.group(1))
    require(g,'private static final String DEFAULT_NAMESPACE = "minecraft";', 'private static final String DIMENSIONS_FOLDER = "dimensions";', 'private static final String BLOCK_FALLBACKS_FIELD = "blockFallbacks";',
            'return new ContentGate(registries, () -> readBlockFallbacks(folder), report, customBlocks);',
            'File[] dimensions = new File(packFolder, DIMENSIONS_FOLDER).listFiles((dir, name) -> name.endsWith(".json"));',
            'for (File file : dimensions)', 'JSONObject fallbacks = json.getJSONObject(BLOCK_FALLBACKS_FIELD);',
            'if (value instanceof String state && !state.isBlank()) { merged.putIfAbsent(key, state); }',
            'String key = normalizeState(entry.getKey()); if (key != null && entry.getValue() != null) { normalized.put(baseKey(key), entry.getValue()); }',
            'String trimmed = state.trim(); if (trimmed.isEmpty()) { return null; }',
            'int props = trimmed.indexOf(\'[\'); String base = (props < 0 ? trimmed : trimmed.substring(0, props)).toLowerCase(Locale.ROOT);',
            "if (base.indexOf(':') < 0) { base = DEFAULT_NAMESPACE + ':' + base; }",
            "int props = normalizedState.indexOf('['); return props < 0 ? normalizedState : normalizedState.substring(0, props);",
            'if (normalized == null || !ready()) { return null; }',
            'NativeBlockState direct = lookup(normalized); if (direct != null) { return new BlockResolution(direct, normalized, false, null, BlockResolution.Source.REGISTRY); } BlockResolution renamed = resolveRename(normalized); if (renamed != null) { return renamed; } String fallback = fallbacks().get(baseKey(normalized));',
            'String normalizedFallback = normalizeState(fallback); NativeBlockState substitute = normalizedFallback == null ? null : lookup(normalizedFallback);',
            'if (substitute != null) { return new BlockResolution(substitute, normalizedFallback, true, normalized, BlockResolution.Source.FALLBACK); }')
    reader=g[g.index('public static Map<String, String> readBlockFallbacks('):g.index('public static String normalizeState(')]
    if re.search(r'\bsort\s*\(|\bsorted\s*\(',reader):raise ValueError('Fallback iteration is now explicitly sorted')
    require(b,'if (gate == null || !gate.ready()) { return B.getStateOrNull(state, false); } ContentGate.BlockResolution resolution = gate.resolveBlock(state); return resolution == null ? null : resolution.state();',
            'if (resolved != null) { return resolved; } if (backup != null) { return backup.getBlockData(data); }')
    require(o,'NativeBlockState direct = B.getStateOrNull(key, false); if (direct != null) { return direct; }',
            'if (gate != null && gate.ready()) { NativeBlockState viaGate = gate.resolveBlockOrPlaceholder(key); if (viaGate != null) { return viaGate; } }')
    values.update({'defaultNamespace':'minecraft','scope.policy':'packTopLevelDimensionMaps','resolution.policy':'registryRenameSingleFallback',
                   'source.policy':'trimLowerBaseDefaultNamespace','winner.policy':'unspecifiedIteration'})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__':Path(sys.argv[5]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:5])))
