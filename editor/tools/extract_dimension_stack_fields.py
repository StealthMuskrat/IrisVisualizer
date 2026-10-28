"""Pin stack/layer/upper declarations and native pack/runtime contracts without terrain evaluation."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact

def extract(dimension,stack,blend,validator,runtime):
    sources=[dimension,stack,blend,validator,runtime];d,s,b,v,r=[raw.decode('utf-8') for raw in sources]
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for name,raw in zip(['dimension','stack','blend','validator','runtime'],sources):values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text):raise ValueError('Changed dimension-stack contract: '+contract)
    def declaration(text,field,kind,default,prefix=''):
        match=re.search(prefix+r'\s*@Description\(("(?:[^"\\]|\\.)*")\)\s*private '+re.escape(kind)+' '+field+' = '+re.escape(default)+r';',text)
        if not match:raise ValueError('Changed stack declaration '+field)
        values[field+'.description']=json.loads(match.group(1))
    declaration(d,'dimensionStack','IrisDimensionStack','null');declaration(d,'upperDimension','String','"none"',r'@RegistryListResource\(IrisDimension.class\)');values['upperDimension.default']='none'
    declaration(s,'dimensions','KList<String>','new KList<>()',r'@Required\s*@ArrayType\(min = 2, type = String.class\)\s*@RegistryListResource\(IrisDimension.class\)')
    declaration(s,'spacer','int','32',r'@MinNumber\(0\)\s*@MaxNumber\(256\)');declaration(s,'blend','IrisDimensionStackBlend','null')
    declaration(b,'amplitude','int','8',r'@MinNumber\(0\)\s*@MaxNumber\(256\)');declaration(b,'style','IrisGeneratorStyle','NoiseStyle.SIMPLEX.style()')
    require(d,'return upperDimension != null && !upperDimension.isEmpty() && !upperDimension.equalsIgnoreCase("none");')
    require(v,'if (!dimension.has("dimensionStack")) { return; }', 'JSONObject stack = dimension.optJSONObject("dimensionStack"); if (stack == null)',
            'if (upperDimension != null && !upperDimension.isEmpty() && !upperDimension.equalsIgnoreCase("none"))',
            'if (dimensions.length() < 2)', 'if (!(rawKey instanceof String key) || key.isBlank())', 'if (!dimensionKeys.contains(key))',
            'if (dimensionKey.equals(key)) { hostOccurrences++; }','if (hostOccurrences != 1)',
            'if (rawLast instanceof String lastKey && !dimensionKey.equals(lastKey))',
            'validateInteger(stack, "spacer", 0, 256, context, blockingErrors);',
            'if (!stack.has("blend")) { return; } JSONObject blend = stack.optJSONObject("blend"); if (blend == null)',
            'validateInteger(blend, "amplitude", 0, 256, context + ".blend", blockingErrors);',
            'if (!dimension.has("upperDimension") || dimension.isNull("upperDimension")) { return; }',
            'if (!key.isEmpty() && !key.equalsIgnoreCase("none") && !dimensionKeys.contains(key))')
    require(r,'if (dimensionKeys == null || dimensionKeys.size() < 2)',
            'if (!hostKey.equals(dimensionKeys.get(dimensionKeys.size() - 1)))', 'if (hostReferences != 1)',
            'for (int keyIndex = dimensionKeys.size() - 1; keyIndex >= 0; keyIndex--)',
            'layersBottomToTop.add(DimensionTerrainContext.forStack(engine, dimension));',
            'if (stack.getSpacer() < 0 || stack.getSpacer() > 256)',
            'if (blend == null || blend.getAmplitude() == 0)', 'if (blend.getAmplitude() < 0 || blend.getAmplitude() > 256)',
            'IrisGeneratorStyle style = blend.getStyle(); if (style == null)',
            'long salt = seamSalt(seamIndex, lower.getDimension().getLoadKey(), upper.getDimension().getLoadKey());',
            'RNG seamRng = new RNG(engine.getSeedManager().getTerrain() ^ salt);',
            'CNG seamNoise = style.create(seamRng, engine.getData(), engine);',
            'seamNoise.fitDouble(-amplitude, amplitude, x, z), amplitude)',
            'if (!Double.isFinite(sampledOffset)) { return 0; } double clamped = Math.max(-amplitude, Math.min(amplitude, sampledOffset)); return (int) Math.round(clamped);')
    values.update({'dimensions.minimum':'2','layers.policy':'hostExactlyOnceAtEnd','spacer.default':'32','amplitude.default':'8',
                   'spacer.minimum':'0','spacer.maximum':'256','amplitude.minimum':'0','amplitude.maximum':'256','style.default':'SIMPLEX',
                   'optional.policy':'removeFieldForPackValidation','mutualExclusion.policy':'upperAndStack','seam.policy':'independentClampedRoundedNoise'})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__':Path(sys.argv[6]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:6])))
