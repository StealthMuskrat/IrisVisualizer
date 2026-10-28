"""Pinned block-alias state merge, backup and primitive-property contracts."""
import hashlib,json,re,sys
from pathlib import Path
from extract_river_material_fields import compact
FILES=['IrisBlockData','IrisMaterialPalette','IrisBiomePaletteLayer']
FIELDS={'block':('String','"air"'),'backup':('IrisBlockData','null'),'data':('KMap<String, Object>','new KMap<>()'),'debug':('boolean','false'),'tileData':('KMap<String, Object>','new KMap<>()'),'weight':('int','1')}
def read_sources(root):return {name:(Path(root)/(name+'.java')).read_bytes() for name in FILES}
def extract(sources):
    texts={name:raw.decode('utf-8') for name,raw in sources.items()};b=texts['IrisBlockData'];values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for name in FILES:values[name+'SourceSha256']=hashlib.sha256(sources[name]).hexdigest()
    def require(text,*items):
        for item in items:
            if compact(item) not in compact(text):raise ValueError('Changed block alias contract: '+item)
    for field,(t,default) in FIELDS.items():
        match=re.search(r'(?P<annotations>(?:\s*@[^\n]+\n)+)\s*private '+re.escape(t)+' '+field+r' = (?P<default>[^;]+);',b)
        if not match or match['default']!=default:raise ValueError('Changed block field/default: '+field)
        desc=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)',match['annotations'])
        if not desc:raise ValueError('Missing block field description')
        values[field+'.type']=t;values[field+'.default']=json.loads(default) if field=='block' else default;values[field+'.description']=json.loads(desc[1])
    require(b,'@RegistryMapBlockState("block")','private int weight = 1;','@MinNumber(1)','@MaxNumber(1000)','return "blocks";',
        'IrisBlockData customData = data.getBlockLoader() == null ? null : data.getBlockLoader().load(getBlock(), false);',
        'NativeBlockState customState = customData.getBlockData(data);','if (getData().isEmpty()) { return customState; }',
        'IrisBlockData merged = IrisBlockData.from(customState.key());','merged.getData().putAll(getData());','NativeBlockState bx = resolve(data, sx); if (bx != null) { return bx; } return customState;',
        'String ss = stateKey();','NativeBlockState resolved = resolve(data, ss);','if (resolved != null) { return resolved; }',
        'if (backup != null) { return backup.getBlockData(data); }','return B.getAirState();',
        'return keyify(getBlock()) + computeProperties();','return computeProperties(getData());','entry.getKey() + "=" + filter(entry.getValue().toString())',
        'if (string.equals("true")) { return true; }','if (string.equals("false")) { return false; }','return Integer.parseInt(string);','double value = Double.parseDouble(string);',
        'if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE && value == Math.rint(value)) { return (int) value; }','return value;',
        'this.data.containsKey("entitySpawn")','return TileData.of(state, this.tileData);')
    resolve=b[b.index('public NativeBlockState getBlockData(IrisData data)'):b.index('public NativeBlockState getBlockDataOrPlaceholder')]
    order=[resolve.index(item) for item in ['customData.getBlockData(data)','merged.getData().putAll(getData())','String ss = stateKey()','if (backup != null)','return B.getAirState()']]
    if order!=sorted(order) or 'getWeight()' in resolve:raise ValueError('Changed alias precedence or weight scope')
    require(texts['IrisMaterialPalette'],'for (int i = 0; i < ix.getWeight(); i++) { blockData.add(bx); }')
    require(texts['IrisMaterialPalette'],'private KList<IrisBlockData> palette = new KList<IrisBlockData>().qadd(new IrisBlockData("STONE"));','NativeBlockState bx = ix.getBlockData(rdata);')
    require(texts['IrisBiomePaletteLayer'],'private KList<IrisBlockData> palette = new KList<IrisBlockData>().qadd(new IrisBlockData("GRASS_BLOCK"));','NativeBlockState bx = ix.getBlockData(data);')
    values.update({'palette.policy':'materialAndBiomeLayerUseBlockData','folder':'blocks','resolution.policy':'aliasThenStateThenBackupThenAir','data.policy':'callerOverridesAliasProperties','invalidMergedState.policy':'keepAliasState','backup.default':'null','weight.policy':'consumingPaletteOnly','tileData.policy':'readOnly','execution.policy':'disabled'})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')
if __name__=='__main__':Path(sys.argv[2]).write_bytes(extract(read_sources(sys.argv[1])))
