"""Audit surface-pool list/scalar defaults, bounds, IDs, radius relation and selector keywords."""
import hashlib
import json
import re
import sys
from decimal import Decimal
from pathlib import Path
from extract_river_material_fields import compact

FIELDS=['density','spacing','minimumRadius','maximumRadius','depth']

def extract(hydrology,pool,validator,query):
    h,p,v,q=(raw.decode('utf-8') for raw in [hydrology,pool,validator,query])
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text):
                raise ValueError('Changed surface pool contract: '+contract)
    require(h,'@NoArgsConstructor');require(p,'@NoArgsConstructor')
    parent=re.search(r'@ArrayType\(type = IrisSurfacePoolConfig.class, min = (\d+)\)\s+@Description\(("(?:[^"\\]|\\.)*")\)\s+private KList<IrisSurfacePoolConfig> surfacePools = new KList<>\(\);',h)
    identifier=re.search(r'@Required\s+@Description\(("(?:[^"\\]|\\.)*")\)\s+private String id = ("(?:[^"\\]|\\.)*");',p)
    fluid=re.search(r'@Required\s+@Description\(("(?:[^"\\]|\\.)*")\)\s+private IrisMaterialPalette fluidPalette = new IrisMaterialPalette\(\).qclear\(\).qadd\(("(?:[^"\\]|\\.)*")\);',p)
    biome=re.search(r'@RegistryListResource\(IrisBiome.class\)\s+@Description\(("(?:[^"\\]|\\.)*")\)\s+private String biome = ("(?:[^"\\]|\\.)*");',p)
    maximum=re.search(r'private static final int MAX_DEEP_FLUIDS = (\d+);',v)
    pattern=re.search(r'private static final Pattern ID_PATTERN = Pattern.compile\(("[^"\n]+")\);',v)
    native=re.search(r'private static Set<String> validateSurfacePools\(.*?\n    \}',v,re.DOTALL)
    if not all([parent,identifier,fluid,biome,maximum,pattern,native]) or parent.group(1)!='0' or maximum.group(1)!='64':
        raise ValueError('Changed pool parent/default/type/list contracts')
    native=native.group(0)
    require(native,'if (!hydrology.has("surfacePools")) { return Set.of(); }',
            'if (!(rawPools instanceof JSONArray pools))','if (pools.length() > MAX_DEEP_FLUIDS)',
            'JSONObject pool = pools.optJSONObject(index);','if (pool == null)',
            'String id = requiredId(poolPath, pool, errors);','if (HydrologyFeatureQuery.isReservedKeyword(id))',
            'else if (riverProfileIds.contains(id))','else if (!ids.add(id))',
            'validateFluidPalette(poolPath, pool, true, errors);',
            'if (rawBiome instanceof String biome && !biome.isBlank() && !biomes.containsKey(biome))')
    require(v,'if (!(rawId instanceof String id) || !ID_PATTERN.matcher(id).matches())',
            'if (!rivers.has("profiles")) { return Set.of("default"); }')
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for name,raw in [('hydrology',hydrology),('pool',pool),('validator',validator),('query',query)]:
        values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    values.update({'list.minimum':parent.group(1),'list.maximum':maximum.group(1),'list.description':json.loads(parent.group(2)),
                   'id.default':json.loads(identifier.group(2)),'id.description':json.loads(identifier.group(1)),'id.pattern':json.loads(pattern.group(1)),
                   'fluid.default':json.loads(fluid.group(2)),'fluidPalette.description':json.loads(fluid.group(1)),
                   'biome.default':json.loads(biome.group(2)),'biome.description':json.loads(biome.group(1))})
    if values['id.default']!='lava_pool' or values['fluid.default']!='lava' or values['biome.default']!='' or values['id.pattern']!='[a-z0-9][a-z0-9_-]{0,63}':
        raise ValueError('Changed pool contextual ID/fluid/biome defaults')
    for name in FIELDS:
        kind='double' if name=='density' else 'int'
        field=re.search(r'((?:    @[^\n]+\n)+)    private '+kind+' '+name+r' = ([^;]+);',p)
        if not field:
            raise ValueError('Changed pool scalar type/default: '+name)
        annotations,default=field.groups();description=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)',annotations)
        call=re.search(r'validateOptional'+('Double' if kind=='double' else 'Integer')+r'Range\(poolPath, pool, "'+name+r'",\s*([^,]+),\s*([^,]+), errors\)',native)
        if not description or not call:
            raise ValueError('Missing pool scalar descriptions/native bounds')
        values[name+'.type']=kind;values[name+'.default']=str(Decimal(default.removesuffix('D')));values[name+'.description']=json.loads(description.group(1))
        for index,(annotation,key) in enumerate([('MinNumber','minimum'),('MaxNumber','maximum')],1):
            bound=re.search('@'+annotation+r'\(([^)]+)\)',annotations)
            if not bound or Decimal(bound.group(1))!=Decimal(call.group(index).strip().removesuffix('D')):
                raise ValueError('Pool annotation/native bounds differ: '+name)
            values[name+'.'+key]=str(Decimal(bound.group(1)))
    require(native,'if (integerValue(pool, "minimumRadius", '+values['minimumRadius.default']+') > integerValue(pool, "maximumRadius", '+values['maximumRadius.default']+'))')
    values['radius.rule']='minimumAtMostMaximum'
    require(q,'return value != null && !value.isBlank() && BUILT_INS.containsKey(normalizeKeyword(value.trim()));',
            "return value.toLowerCase(Locale.ROOT).replace('-', '_');")
    built=re.search(r'private static Map<String, Set<HydrologyFeatureType>> builtIns\(\)\s*\{(.*?)\n    \}',q,re.DOTALL)
    if not built:
        raise ValueError('Missing hydrology selector keywords')
    lines=[line.strip() for line in built.group(1).splitlines() if line.strip()]
    if not lines[0].startswith('LinkedHashMap<String, Set<HydrologyFeatureType>> builtIns = new LinkedHashMap<>();') or lines[-1]!='return Collections.unmodifiableMap(builtIns);':
        raise ValueError('Changed built-in selector initialization')
    keywords=[]
    for line in lines[1:-1]:
        match=re.fullmatch(r'builtIns.put\("([a-z_]+)", (?:[A-Z]+|Set.of\(HydrologyFeatureType.[A-Z_]+\))\);',line)
        if not match:
            raise ValueError('Unsupported selector keyword mapping')
        keywords.append(match.group(1))
    if not keywords or len(keywords)!=len(set(keywords)):
        raise ValueError('Empty/duplicate selector keywords')
    values['id.reserved']=','.join(keywords)
    return ''.join(key+'='+value.replace('\\','\\\\').replace('\n','\\n').replace('\r','\\r')+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__':
    Path(sys.argv[5]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:5])))
