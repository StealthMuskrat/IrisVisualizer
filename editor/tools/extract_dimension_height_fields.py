"""Audit dimension runtime height bounds and the pack deep-fluid envelope formulas."""
import hashlib
import json
import re
import sys
from decimal import Decimal
from pathlib import Path
from extract_river_material_fields import compact


def extract(dimension,ranges,dimension_type,dimension_validator,hydrology_validator):
    d,r,t,dv,hv=(raw.decode('utf-8') for raw in [dimension,ranges,dimension_type,dimension_validator,hydrology_validator])
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text): raise ValueError('Changed dimension/envelope contract: '+contract)
    require(d,'@NoArgsConstructor');require(r,'@NoArgsConstructor','@AllArgsConstructor')
    declaration=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private IrisRange dimensionHeight = new IrisRange\(([-+0-9.]+),\s*([-+0-9.]+)\);',d)
    logical=re.search(r'private int logicalHeight = (\d+);',d)
    if not declaration or not logical or re.findall(r'^    private \w+ (\w+) = ',r,re.MULTILINE)!=['min','max']: raise ValueError('Changed dimension/range declarations')
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for name,raw in [('dimension',dimension),('range',ranges),('dimensionType',dimension_type),('dimensionValidator',dimension_validator),('hydrologyValidator',hydrology_validator)]: values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    values['description']=json.loads(declaration.group(1));values['logical.default']=logical.group(1)
    for member,index in [('min',2),('max',3)]:
        initial=re.search(r'private double '+member+r' = ([-+0-9.]+);',r)
        if not initial: raise ValueError('Changed range class defaults')
        values['context.'+member]=str(Decimal(declaration.group(index)));values['class.'+member]=str(Decimal(initial.group(1)))
    for key,name in [('span.minimum','MIN_HEIGHT'),('span.maximum','MAX_HEIGHT'),('minimumY.minimum','MIN_MIN_Y'),('minimumY.maximum','MAX_MIN_Y'),('step','HEIGHT_STEP')]:
        constant=re.search(r'public static final int '+name+r' = (-?\d+);',t)
        if not constant: raise ValueError('Missing native dimension type constant')
        values[key]=constant.group(1)
    require(t,'if (logicalHeight > height)','if (logicalHeight < 0)','if (height < MIN_HEIGHT || height > MAX_HEIGHT)',
            'if ((height & (HEIGHT_STEP - 1)) != 0)','if (minY < MIN_MIN_Y || minY > MAX_MIN_Y)','if ((minY & (HEIGHT_STEP - 1)) != 0)')
    if int(values['step'])<=0 or int(values['step']) & (int(values['step'])-1): raise ValueError('Native alignment step must remain a power of two')
    native=re.search(r'static void validateDimensionHeights\(.*?\n    \}',dv,re.DOTALL)
    helper=re.search(r'private static NumericRange dimensionHeight\(.*?\n    \}',hv,re.DOTALL)
    envelopes=re.search(r'private static boolean validateDeepFluids\(.*?\n    \}',hv,re.DOTALL)
    if not native or not helper or not envelopes: raise ValueError('Missing pack validation bindings')
    require(native.group(0),'if (range == null) { minY = '+values['context.min']+'; maxY = '+values['context.max']+'; }',
            'minY = (int) range.optDouble("min", '+values['class.min']+'D);','maxY = (int) range.optDouble("max", '+values['class.max']+'D);',
            'int height = maxY - minY;','int logicalHeight = (int) dimJson.optDouble("logicalHeight", '+values['logical.default']+'D);',
            'if (height < IrisDimensionType.MIN_HEIGHT || height > IrisDimensionType.MAX_HEIGHT)',
            'if (minY < IrisDimensionType.MIN_MIN_Y || minY > IrisDimensionType.MAX_MIN_Y)',
            '(height & (IrisDimensionType.HEIGHT_STEP - 1)) != 0','(minY & (IrisDimensionType.HEIGHT_STEP - 1)) != 0',
            'if (logicalHeight < 0)','else if (logicalHeight > height)')
    # Hydrology always uses contextual fallback values, even within a partial inline object.
    require(helper.group(0),'JSONObject height = dimension.optJSONObject("dimensionHeight");','if (height == null) { return NumericRange.of('+values['context.min']+'D, '+values['context.max']+'D); }',
            'doubleValue(height, "min", '+values['context.min']+'D)','doubleValue(height, "max", '+values['context.max']+'D)')
    values['native.min']=values['context.min'];values['native.max']=values['context.max']
    require(envelopes.group(0),'NumericRange dimensionRange = dimensionHeight(dimension);','int lowerEnvelope = containedPools ? Math.max(depth, verticalRadius) : depth;',
            'if (configuredHeight.minimum() - lowerEnvelope <= dimensionRange.minimum())','if (configuredHeight.maximum() + headroom >= dimensionRange.maximum())')
    for field in ['depth','verticalRadius','headroom']:
        require(envelopes.group(0),'integerValue(deepFluid, "'+field+'", DEFAULT_DEEP_FLUID.get'+field[0].upper()+field[1:]+'())')
    require(envelopes.group(0),'booleanValue(deepFluid, "containedPools", DEFAULT_DEEP_FLUID.isContainedPools())')
    validate_range=re.search(r'private static NumericRange validateRange\(.*?\n    \}',hv,re.DOTALL)
    if not validate_range: raise ValueError('Missing native range fallbacks')
    require(validate_range.group(0),'double minimum = doubleValue(range, "min", defaultMinimum);','double maximum = doubleValue(range, "max", defaultMaximum);')
    values['envelope.rule']='strictInside'
    return ''.join(key+'='+value.replace('\\','\\\\').replace('\n','\\n').replace('\r','\\r')+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__': Path(sys.argv[6]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:6])))
