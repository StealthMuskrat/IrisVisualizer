"""Pin nullable style references, resource folders and native cache/source precedence."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact

def extract(style,expression,image_map,data,loader):
    sources=[style,expression,image_map,data,loader];s,e,i,d,l=[raw.decode('utf-8') for raw in sources]
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text):raise ValueError('Changed style source contract: '+contract)
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for name,raw in zip(['style','expression','imageMap','data','loader'],sources):values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    for name,kind in [('expression','IrisExpression'),('imageMap','IrisImageMap')]:
        match=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+@RegistryListResource\('+kind+r'.class\)\s+private String '+name+r' = null;',s)
        if not match:raise ValueError('Changed nullable style reference '+name)
        values[name+'.description']=json.loads(match.group(1));values[name+'.default']='null'
    cache=re.search(r'@MinNumber\((0)\)\s+@MaxNumber\((8192)\)\s+@Description\(("(?:[^"\\]|\\.)*")\)\s+private int cacheSize = (0);',s)
    if not cache:raise ValueError('Changed cache size bounds/default')
    values['cacheSize.minimum'],values['cacheSize.maximum']=cache.group(1),cache.group(2);values['cacheSize.description']=json.loads(cache.group(3));values['cacheSize.default']=cache.group(4)
    require(e,'public String getFolderName() { return "expressions"; }');require(i,'public String getFolderName() { return "image-maps"; }')
    require(d,'this.expressionLoader = registerLoader(IrisExpression.class, replacement);','this.imageMapLoader = registerLoader(IrisImageMap.class, replacement);')
    require(l,'for (File j : matchAllFiles(i, (f) -> f.getName().endsWith(".json"))) { String path = i.toURI().relativize(j.toURI()).getPath(); m.add(path.substring(0, path.length() - ".json".length())); }')
    require(s,'if (getExpression() != null) { IrisExpression e = data.getExpressionLoader().load(getExpression()); if (e != null) { cng = new CNG(rng, new ExpressionNoise(rng, e), 1D, 1).bake(); sourceStamp = Integer.toUnsignedLong(Objects.hash(getExpression(), e.getLoadFile() == null ? 0L : e.getLoadFile().lastModified())); } } else if (getImageMap() != null) { ImageNoise imageNoise = new ImageNoise(data, getImageMap()); cng = new CNG(rng, imageNoise, 1D, 1).bake(); sourceStamp = Integer.toUnsignedLong(imageNoise.getContentHash().hashCode()); }',
            'if (cng == null) { cng = (style != null ? style : NoiseStyle.FLAT).create(rng).bake(); }',
            'int effectiveCacheSize = cacheSize > 0 ? cacheSize : Math.max(0, fallbackCacheSize);',
            'if (effectiveCacheSize > 0) { long engineStamp = engineStamp(engine); String key = cacheKey(rng, sourceStamp, effectiveCacheSize, engineStamp, octaveMultiplier); clearStaleCacheEntries(data, cachePrefix(rng, effectiveCacheSize, engineStamp, octaveMultiplier), key); cng = cng.cached(effectiveCacheSize, key, data.getDataFolder(), quietCacheLog); }')
    values.update({'expression.family':'expressions','imageMap.family':'image-maps','precedence.policy':'expressionThenImageThenBase','expression.fallback':'baseOnUnloadedExpression','cache.policy':'positiveOverrideOtherwiseCallerFallback'})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__':Path(sys.argv[6]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:6])))
