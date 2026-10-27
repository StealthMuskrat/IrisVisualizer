"""Audit independent deep-fluid authoring and bounded native geometry/height rules."""
import hashlib
import json
import re
import sys
from decimal import Decimal
from pathlib import Path
from extract_river_material_fields import compact
from extract_surface_pool_fields import extract as pool_extract

NUMERIC=['density','spacing','horizontalRadius','verticalRadius','channelWidth','depth','headroom']
BOOLEANS=['containedPools','shortChannels']

def extract(hydrology,deep,ranges,style,validator,query,pool):
    h,d,r,s,v=(raw.decode('utf-8') for raw in [hydrology,deep,ranges,style,validator])
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text): raise ValueError('Changed deep-fluid contract: '+contract)
    require(h,'@NoArgsConstructor');require(d,'@NoArgsConstructor');require(r,'@NoArgsConstructor','@AllArgsConstructor')
    list_field=re.search(r'@ArrayType\(type = IrisDeepFluidConfig.class\)\s+@Description\(("(?:[^"\\]|\\.)*")\)\s+private KList<IrisDeepFluidConfig> deepFluids = new KList<>\(\);',h)
    identifier=re.search(r'@Required\s+@Description\(("(?:[^"\\]|\\.)*")\)\s+private String id = ("(?:[^"\\]|\\.)*");',d)
    fluid=re.search(r'@Required\s+@Description\(("(?:[^"\\]|\\.)*")\)\s+private IrisMaterialPalette fluidPalette = new IrisMaterialPalette\(\).qclear\(\).qadd\(("(?:[^"\\]|\\.)*")\);',d)
    native=re.search(r'private static boolean validateDeepFluids\(.*?\n    \}',v,re.DOTALL)
    if not all([list_field,identifier,fluid,native]): raise ValueError('Changed deep-fluid list/required declarations')
    # The same pinned native method supplies IDs, limits and selector semantics for independent systems.
    common=dict(line.split('=',1) for line in pool_extract(hydrology,pool,validator,query).decode().splitlines())
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for name,raw in [('hydrology',hydrology),('deep',deep),('range',ranges),('style',style),('validator',validator),('query',query),('pool',pool)]: values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    values.update({'list.minimum':'0','list.maximum':common['list.maximum'],'list.description':json.loads(list_field.group(1)),
                   'id.default':json.loads(identifier.group(2)),'id.description':json.loads(identifier.group(1)),
                   'id.pattern':common['id.pattern'],'id.reserved':common['id.reserved'],
                   'fluid.default':json.loads(fluid.group(2)),'fluidPalette.description':json.loads(fluid.group(1))})
    if values['id.default']!='deep_lava' or values['fluid.default']!='lava': raise ValueError('Changed deep contextual ID/fluid defaults')
    native=native.group(0)
    require(native,'if (!hydrology.has("deepFluids")) { return false; }','if (!(rawDeepFluids instanceof JSONArray deepFluids))','if (deepFluids.length() > MAX_DEEP_FLUIDS)',
            'JSONObject deepFluid = deepFluids.optJSONObject(index);','if (deepFluid == null)','String id = requiredId(fluidPath, deepFluid, errors);',
            'if (id != null && !ids.add(id))','if (HydrologyFeatureQuery.isReservedKeyword(id))','if (id != null && riverProfileIds.contains(id))','validateFluidPalette(fluidPath, deepFluid, true, errors);')
    for name in NUMERIC+BOOLEANS:
        kind='boolean' if name in BOOLEANS else 'double' if name=='density' else 'int'
        declaration=re.search(r'((?:    @[^\n]+\n)+)    private '+kind+' '+name+r' = ([^;]+);',d)
        if not declaration: raise ValueError('Changed deep-fluid primitive: '+name)
        annotations,default=declaration.groups();help_text=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)',annotations)
        if not help_text: raise ValueError('Missing field help')
        values[name+'.type']=kind;values[name+'.description']=json.loads(help_text.group(1))
        if kind=='boolean':
            if default not in ['true','false']: raise ValueError('Changed boolean default')
            require(native,'validateOptionalBoolean(fluidPath, deepFluid, "'+name+'", errors);');values[name+'.default']=default
        else:
            values[name+'.default']=str(Decimal(default.removesuffix('D')))
            call=re.search(r'validateOptional'+('Double' if kind=='double' else 'Integer')+r'Range\(fluidPath, deepFluid, "'+name+r'",\s*([^,]+),\s*([^,]+), errors\)',native)
            if not call: raise ValueError('Missing native primitive bounds: '+name)
            for index,(annotation,key) in enumerate([('MinNumber','minimum'),('MaxNumber','maximum')],1):
                bound=re.search('@'+annotation+r'\(([^)]+)\)',annotations)
                if not bound or Decimal(bound.group(1))!=Decimal(call.group(index).strip().removesuffix('D')): raise ValueError('Changed primitive annotation/native bound: '+name)
                values[name+'.'+key]=str(Decimal(bound.group(1)))
    require(v,'private static final IrisDeepFluidConfig DEFAULT_DEEP_FLUID = new IrisDeepFluidConfig();')
    for name in ['spacing','horizontalRadius','verticalRadius','channelWidth','depth','headroom']:
        require(native,'integerValue(deepFluid, "'+name+'", DEFAULT_DEEP_FLUID.get'+name[0].upper()+name[1:]+'())')
    require(native,'int footprintDiameter = (horizontalRadius + channelWidth) * 2;','if (spacing < footprintDiameter)','if (depth + headroom >= verticalRadius * 2 + 1)')
    values['geometry.rule']='spacingAndClearance'
    height=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private IrisStyledRange height = new IrisStyledRange\(\s*([-+0-9.D]+),\s*([-+0-9.D]+),\s*new IrisGeneratorStyle\(NoiseStyle.([A-Z]+)\).zoomed\(([-+0-9.D]+)\)\s*\);',d)
    if not height: raise ValueError('Changed contextual height constructor')
    values['height.description']=json.loads(height.group(1))
    for key,raw in [('min',height.group(2)),('max',height.group(3)),('zoom',height.group(5))]: values['height.context.'+key]=str(Decimal(raw.removesuffix('D')))
    values['height.context.style']=height.group(4)
    require(native,'validateRange(fluidPath, deepFluid, "height", -2048D, 2048D, '+height.group(2)+', '+height.group(3)+', errors)')
    values['height.minimum']='-2048';values['height.maximum']='2048'
    if re.findall(r'^    private \w+ (\w+) = ',r,re.MULTILINE)!=['min','max','style']: raise ValueError('Changed range constructor field order')
    for name in ['min','max']:
        declaration=re.search(r'private double '+name+r' = ([-+0-9.D]+);',r)
        if not declaration: raise ValueError('Missing styled range default')
        values['height.class.'+name]=str(Decimal(declaration.group(1).removesuffix('D')))
    class_style=re.search(r'private IrisGeneratorStyle style = new IrisGeneratorStyle\(NoiseStyle.([A-Z]+)\);',r)
    if not class_style: raise ValueError('Changed styled-range class style')
    values['height.class.style']=class_style.group(1)
    require(s,'public IrisGeneratorStyle(NoiseStyle s) { this.style = s; }','public IrisGeneratorStyle zoomed(double z) { this.zoom = z; return this; }')
    return ''.join(key+'='+value.replace('\\','\\\\').replace('\n','\\n').replace('\r','\\r')+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__': Path(sys.argv[8]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:8])))
