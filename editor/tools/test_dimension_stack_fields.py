"""Reject stack schema, host/mutual-exclusion, optional-state and seam-consumer drift."""
import sys
from pathlib import Path
from extract_dimension_stack_fields import extract

sources=[Path(path).read_bytes() for path in sys.argv[1:]];result=extract(*sources).decode()
for token in ['dimensions.minimum=2\n','layers.policy=hostExactlyOnceAtEnd\n','spacer.default=32\n','amplitude.default=8\n','style.default=SIMPLEX\n','optional.policy=removeFieldForPackValidation\n']:assert token in result,token
mutations=[(0,b'private IrisDimensionStack dimensionStack = null;',b'private IrisDimensionStack dimensionStack = new IrisDimensionStack();'),
           (0,b'private String upperDimension = "none";',b'private String upperDimension = "";'),
           (1,b'@ArrayType(min = 2, type = String.class)',b'@ArrayType(min = 1, type = String.class)'),
           (1,b'private int spacer = 32;',b'private int spacer = 16;'),(1,b'private IrisDimensionStackBlend blend = null;',b'private IrisDimensionStackBlend blend = new IrisDimensionStackBlend();'),
           (2,b'private int amplitude = 8;',b'private int amplitude = 16;'),(2,b'NoiseStyle.SIMPLEX.style()',b'NoiseStyle.IRIS.style()'),
           (3,b'if (hostOccurrences != 1)',b'if (hostOccurrences == 0)'),(3,b'!dimensionKey.equals(lastKey)',b'false'),
           (3,b'!upperDimension.equalsIgnoreCase("none")',b'false'),(3,b'JSONObject blend = stack.optJSONObject("blend");',b'JSONObject blend = stack.optJSONObject("blend") == null ? new JSONObject() : stack.optJSONObject("blend");'),
           (4,b'if (hostReferences != 1)',b'if (hostReferences < 1)'),(4,b'keyIndex--',b'keyIndex++'),
           (4,b'blend == null || blend.getAmplitude() == 0',b'blend == null'),
           (4,b'engine.getSeedManager().getTerrain() ^ salt',b'engine.getSeedManager().getTerrain()'),
           (4,b'fitDouble(-amplitude, amplitude, x, z)',b'fitDouble(0, amplitude, x, z)'),(4,b'return (int) Math.round(clamped);',b'return (int) clamped;')]
for index,old,new in mutations:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try:extract(*changed)
    except ValueError:continue
    raise AssertionError('Accepted dimension-stack drift: '+str(old))
print('PASS: dimension stack/upper defaults, exact layer host/order and mutual exclusion, pack optional states and native independent seam/noise/clamp consumers reject drift')
