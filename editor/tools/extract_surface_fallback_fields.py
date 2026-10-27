"""Audit enum help, dimension/biome defaults and runtime inheritance/fallback binding."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact


def extract(dimension,biome,enum,generator):
    d,b,e,g=(raw.decode('utf-8') for raw in [dimension,biome,enum,generator])
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for name,raw in [('dimension',dimension),('biome',biome),('enum',enum),('generator',generator)]: values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    for group,text in [('dimension',d),('biome',b)]:
        if '@NoArgsConstructor' not in text: raise ValueError('Changed fallback owner constructor')
        field=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private IrisSurfaceLayerFallback surfaceLayerFallback = ([^;]+);',text)
        if not field: raise ValueError('Changed fallback owner declaration')
        expression=field.group(2)
        if group=='biome':
            if expression!='null': raise ValueError('Changed biome inheritance default')
            values[group+'.default']='null'
        else:
            default=re.fullmatch(r'IrisSurfaceLayerFallback.([A-Z_]+)',expression)
            if not default: raise ValueError('Changed dimension fallback default')
            values[group+'.default']=default.group(1)
        values[group+'.description']=json.loads(field.group(1))
    body=re.search(r'public enum IrisSurfaceLayerFallback\s*\{(.*?)\}',e,re.DOTALL)
    if not body: raise ValueError('Missing fallback enum')
    remaining=body.group(1);choices=[]
    while remaining.strip():
        choice=re.match(r'\s*@Description\(("(?:[^"\\]|\\.)*")\)\s+([A-Z_]+)\s*(,|$)',remaining)
        if not choice or choice.group(2) in choices: raise ValueError('Unsupported/duplicate fallback enum entry')
        choices.append(choice.group(2));values[choice.group(2)+'.description']=json.loads(choice.group(1));remaining=remaining[choice.end():]
    if not choices or values['dimension.default'] not in choices: raise ValueError('Invalid enum/default binding')
    runtime=compact(g)
    for contract in ['IrisSurfaceLayerFallback fallback = biome.getSurfaceLayerFallback();','if (fallback == null) { fallback = dim.getSurfaceLayerFallback(); }',
                     'if (fallback == IrisSurfaceLayerFallback.TOP_LAYER) { appendLayer(data, biome.getLayers().get(0), 0, 1, wx, wz, random, maxDepth, rdata); }']:
        if compact(contract) not in runtime: raise ValueError('Changed runtime fallback inheritance/top-layer semantics')
    values['choices']=','.join(choices)
    return ''.join(key+'='+value.replace('\\','\\\\').replace('\n','\\n').replace('\r','\\r')+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__': Path(sys.argv[5]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:5])))
