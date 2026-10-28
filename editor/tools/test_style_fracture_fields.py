"""Reject recursive style defaults, multiplier, salt/cache forwarding and native-order drift."""
import sys
from pathlib import Path
from extract_style_fracture_fields import extract

raw=Path(sys.argv[1]).read_bytes();result=extract(raw).decode()
for token in ['fracture.default=null\n','class.style=FLAT\n','multiplier.minimum=0.00001\n','multiplier.policy=positiveFiniteReciprocal\n','child.rngSalt=2934\n','order.policy=octavesExponentFractureCellularZoomCache\n']:assert token in result,token
mutations=[(b'private IrisGeneratorStyle fracture = null;',b'private IrisGeneratorStyle fracture = new IrisGeneratorStyle();'),
           (b'private NoiseStyle style = NoiseStyle.FLAT;',b'private NoiseStyle style = NoiseStyle.IRIS;'),
           (b'private double multiplier = 1;',b'private double multiplier = 2;'),(b'@MinNumber(0.00001)',b'@MinNumber(0)'),
           (b'requirePositiveFinite("fracture.multiplier", fracture.getMultiplier());',b''),
           (b'!Double.isFinite(1D / value)',b'false'),(b'rng.nextParallelRNG(2934)',b'rng.nextParallelRNG(1)'),
           (b'fracture.createNoCache(rng.nextParallelRNG(2934), data, 1,',b'fracture.createNoCache(rng.nextParallelRNG(2934), data, octaveMultiplier,'),
           (b'fallbackCacheSize, quietCacheLog, engine), fracture.getMultiplier()',b'0, quietCacheLog, engine), fracture.getMultiplier()')]
for old,new in mutations:
    assert old in raw,old
    try:extract(raw.replace(old,new))
    except ValueError:continue
    raise AssertionError('Accepted style-fracture drift: '+str(old))
old=b'        cng.zoom(zoom);';assert old in raw
changed=raw.replace(old,b'').replace(b'        if (fracture != null) {\n            cng.fractureWith',old+b'\n        if (fracture != null) {\n            cng.fractureWith')
try:extract(changed)
except ValueError:pass
else:raise AssertionError('Accepted distortion/zoom order drift')
print('PASS: nullable style fracture, FLAT defaults, multiplier/reciprocal bounds, child salt/octaves/cache forwarding and native evaluation order reject drift')
