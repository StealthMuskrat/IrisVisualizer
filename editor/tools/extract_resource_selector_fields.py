"""Pin focus declarations, native priority/compatibility and carving reachability."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact

def extract(*sources):
    d,b,e,c,r,t=[raw.decode('utf-8') for raw in sources]
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for name,raw in zip(['dimension','biome','engine','complex','regionSelection','terrainContext'],sources):values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    for text,field,kind,family in [(d,'focus','IrisBiome','biomes'),(d,'focusRegion','IrisRegion','regions'),(b,'carvingBiome','IrisBiome','biomes')]:
        match=re.search(r'@RegistryListResource\('+kind+r'.class\)\s*@Description\(("(?:[^"\\]|\\.)*")\)\s*private String '+field+r' = "";',text)
        if not match:raise ValueError('Changed selector declaration: '+field)
        values[field+'.description']=json.loads(match.group(1));values[field+'.family']=family;values[field+'.default']=''
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text):raise ValueError('Changed selector contract: '+contract)
    require(d,'addReachableBiomeKey(pending, getFocus());','if (!visited.add(biomeKey)) { continue; }',
            'addReachableBiomeKey(pending, biome.getCarvingBiome());','if (biomeKey != null && !biomeKey.isBlank()) { pending.addLast(biomeKey); }',
            'if (region.getAllBiomeIds().contains(biomeKey)) { return region; }',
            'region.getLandBiomes().add(biomeKey);','region.getSeaBiomes().add(biomeKey);','region.getShoreBiomes().add(biomeKey);')
    require(e,'IrisBiome focus = getData().getBiomeLoader().load(getDimension().getFocus());',
            'IrisRegion focus = getData().getRegionLoader().load(getDimension().getFocusRegion());',
            'return focus == null || focus.isCompatExcluded() ? null : focus;')
    require(c,'boolean buffetActive = biomeBuffet != null && !biomeBuffet.cells().isEmpty();',
            'focusBiome = buffetActive ? null : compatUsable(engine.getFocus());',
            'focusRegion = buffetActive ? null : compatUsable(engine.getFocusRegion());',
            'if (focusBiome != null) { focusBiome = focusBiome.withInferredType(InferredType.LAND); focusRegion = engine.getDimension().resolveFocusRegion(focusBiome, engine); }')
    require(r,'if (options.focused() != null) { return options.focused(); }',
            'return dimension.resolveFocusRegion(focus.withInferredType(InferredType.LAND), () -> data);',
            'return focus == null || focus.isCompatExcluded() ? null : focus;')
    require(t,'return biome == null || biome.isCompatExcluded() ? null : biome;',
            'return region == null || region.isCompatExcluded() ? null : region;')
    values.update({'focus.policy':'usableBiomeBeforeRegion','carvingBiome.policy':'reachabilityOnly','buffet.policy':'overridesFocus','clear.policy':'emptyString'})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__':Path(sys.argv[7]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:7])))
