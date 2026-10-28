"""Pin binding/mask declarations and local-resolution/composition contracts without decoding maps."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact

def extract(dimension,binding,mask,application,operation,runtime,sampler):
    sources=[dimension,binding,mask,application,operation,runtime,sampler];d,b,m,a,o,r,s=[raw.decode('utf-8') for raw in sources]
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for name,raw in zip(['dimension','binding','mask','application','operation','runtime','sampler'],sources):values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text):raise ValueError('Changed image-map binding contract: '+contract)
    require(d,'@ArrayType(type = IrisImageMapBinding.class)','private KList<IrisImageMapBinding> imageMaps = new KList<>();')
    def declaration(text,group,field,kind,default):
        match=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s*private '+re.escape(kind)+' '+field+' = '+re.escape(default)+r';',text)
        if not match:raise ValueError('Changed binding field '+group+'.'+field)
        token=default[1:-1] if kind=='String' else default.rsplit('.',1)[-1] if kind.startswith('IrisImageMap') else '[]' if kind.startswith('KList') else default.rstrip('D')
        values[group+'.'+field+'.description']=json.loads(match.group(1));values[group+'.'+field+'.default']=token
    for field,kind,default in [('key','String','""'),('map','String','""'),('application','IrisImageMapApplication','IrisImageMapApplication.CUSTOM'),('masks','KList<IrisImageMapMask>','new KList<>()')]:declaration(b,'binding',field,kind,default)
    for field,kind,default in [('map','String','""'),('operation','IrisImageMapMaskOperation','IrisImageMapMaskOperation.MULTIPLY'),('inverted','boolean','false'),('threshold','double','0D'),('falloff','double','0D')]:declaration(m,'mask',field,kind,default)
    require(b,'@RegistryListResource(IrisImageMap.class)','@ArrayType(type = IrisImageMapMask.class)')
    for field in ['threshold','falloff']:
        pattern=r'@MinNumber\(0\)\s*@MaxNumber\(1\)\s*@Description\("(?:[^"\\]|\\.)*"\)\s*private double '+field+r' = 0D;'
        if not re.search(pattern,m):raise ValueError('Changed mask bounds '+field)
    def choices(text):return re.findall(r'^\s*([A-Z][A-Z_]*)\s*[,;]?\s*$',text.split('{',1)[1].rsplit('}',1)[0],re.MULTILINE)
    apps=choices(a);ops=choices(o)
    if apps!=['TERRAIN_HEIGHT','BIOME','REGION','SURFACE_BLOCK','MASK','CUSTOM'] or ops!=['MULTIPLY','MINIMUM','MAXIMUM','ADD','SUBTRACT']:raise ValueError('Changed binding/mask choices')
    values['applications']=','.join(apps);values['operations']=','.join(ops)
    require(r,'RuntimeBinding duplicate = bindings.putIfAbsent(runtimeBinding.key, runtimeBinding);','if (duplicate != null)',
            'if (application != IrisImageMapApplication.MASK && application != IrisImageMapApplication.CUSTOM)',
            'RuntimeBinding applicationDuplicate = applications.putIfAbsent(application, runtimeBinding);','if (applicationDuplicate != null)',
            'String key = requireText(binding.getKey(), "Dimension image-map key");',
            'IrisImageMap definition = data.getImageMapLoader().load(mapKey);',
            'binding.getMasks() == null ? List.of() : List.copyOf(binding.getMasks())',
            'if (application == IrisImageMapApplication.MASK && masks != null && !masks.isEmpty())',
            'RuntimeBinding maskBinding = bindings.get(maskKey);','if (maskBinding == null)',
            'if (maskBinding.application != IrisImageMapApplication.MASK)',
            'if (mask.getOperation() == null)', 'if (!range(mask.getThreshold()) || !range(mask.getFalloff()))',
            'return Double.isFinite(value) && value >= 0D && value <= 1D;')
    text_method=re.search(r'private static String requireText\(.*?\n    \}',r,re.DOTALL)
    if not text_method:raise ValueError('Missing native local-key normalization')
    require(text_method.group(0),'if (value == null || value.isBlank())','return value.trim();')
    require(s,'double weight = 1D;','for (Layer layer : layers)',
            'case MULTIPLY -> weight * value;','case MINIMUM -> Math.min(weight, value);',
            'case MAXIMUM -> Math.max(weight, value);','case ADD -> weight + value;','case SUBTRACT -> weight - value;',
            'weight = clamp01(weight);','if (inverted) { value = 1D - value; }',
            'if (threshold == 0D && falloff == 0D) { return value; }',
            'if (falloff == 0D) { return value >= threshold ? 1D : 0D; }',
            'return clamp01((value - threshold) / falloff);')
    values.update({'key.policy':'trimmedCaseSensitive','mask.policy':'namedMaskBindings','composition.policy':'orderedLayers','singleton.policy':'exceptMaskAndCustom','mask.range.minimum':'0','mask.range.maximum':'1'})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__':Path(sys.argv[8]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:8])))
