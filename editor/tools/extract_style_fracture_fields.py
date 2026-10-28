"""Pin nullable style distortion, child defaults, multiplier and native evaluation order."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact

def extract(raw):
    source=raw.decode('utf-8');values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f','sourceSha256':hashlib.sha256(raw).hexdigest()}
    def require(*contracts):
        for contract in contracts:
            if compact(contract) not in compact(source):raise ValueError('Changed style-fracture contract: '+contract)
    fracture=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s*private IrisGeneratorStyle fracture = null;',source)
    multiplier=re.search(r'@MinNumber\((0.00001)\)\s*@Description\(("(?:[^"\\]|\\.)*")\)\s*private double multiplier = (1);',source)
    if not fracture or not multiplier:raise ValueError('Changed style-fracture declaration')
    require('@NoArgsConstructor','private NoiseStyle style = NoiseStyle.FLAT;',
            'if (fracture != null) { requirePositiveFinite("fracture.multiplier", fracture.getMultiplier()); }',
            'if (!Double.isFinite(value) || value <= 0D || !Double.isFinite(1D / value))',
            'if (fracture != null) { cng.fractureWith(fracture.createNoCache(rng.nextParallelRNG(2934), data, 1, fallbackCacheSize, quietCacheLog, engine), fracture.getMultiplier()); }',
            'cng = cng.oct(Math.clamp((long) cng.getOct() * octaveMultiplier, 1, 16)).pow(cng.getPower() * exponent);',
            'cng = cng.cellularize(rng.nextParallelRNG(884466), cellularFrequency).scale(1D / cellularZoom).bake();',
            'cng.zoom(zoom);','int effectiveCacheSize = cacheSize > 0 ? cacheSize : Math.max(0, fallbackCacheSize);')
    start=source.index('private CNG createNoCache(');body=source[start:source.index('public double warp(',start)]
    order=['cng = cng.oct(', 'cng.fractureWith(', 'cng = cng.cellularize(', 'cng.zoom(zoom);','int effectiveCacheSize']
    if [body.index(step) for step in order]!=sorted(body.index(step) for step in order):raise ValueError('Changed native distortion order')
    values.update({'fracture.description':json.loads(fracture.group(1)),'fracture.default':'null','class.style':'FLAT',
                   'multiplier.description':json.loads(multiplier.group(2)),'multiplier.minimum':multiplier.group(1),'multiplier.default':multiplier.group(3),
                   'multiplier.policy':'positiveFiniteReciprocal','child.octaveMultiplier':'1','child.rngSalt':'2934',
                   'child.cachePolicy':'forwardCallerFallback','order.policy':'octavesExponentFractureCellularZoomCache'})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__':Path(sys.argv[2]).write_bytes(extract(Path(sys.argv[1]).read_bytes()))
