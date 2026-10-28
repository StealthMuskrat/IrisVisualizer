"""Pin underground feature declarations and authored-data/planner gating contracts without running geometry."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact

def extract(dimension,feature,family,fluid,range_source,planner,complex_source):
    sources=[dimension,feature,family,fluid,range_source,planner,complex_source];d,f,a,l,r,p,c=[raw.decode('utf-8') for raw in sources]
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for name,raw in zip(['dimension','feature','family','fluid','range','planner','complex'],sources):values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text):raise ValueError('Changed subterrain contract: '+contract)
    require(d,'@ArrayType(type = IrisSubterrainFeature.class, min = 1)','private KList<IrisSubterrainFeature> subterrainFeatures = new KList<>();',
            'return useMantle && carvingEnabled && mode != null && mode.getType() == IrisDimensionModeType.OVERWORLD && !disabledComponents.contains(ReservedFlag.CARVED);')
    fields=[('id','String','""'),('enabled','boolean','true'),('family','IrisSubterrainFamily','IrisSubterrainFamily.CENOTE'),('biome','String','""'),
            ('allowedRegions','KList<String>','new KList<>()'),('worldYRange','IrisRange','new IrisRange(-48, 48)'),('spacing','int','512'),
            ('probability','double','0.35'),('length','int','256'),('radius','int','32'),('height','int','32'),('shapeWarp','double','0'),
            ('fluidDepth','int','4'),('chimneyHeight','int','16'),('terraceCount','int','6'),('pillarSpacing','int','24'),('formationFraction','double','0.15'),
            ('solid','String','"minecraft:stone"'),('priority','int','0')]
    for field,kind,default in fields:
        match=re.search(r'(?P<annotations>(?:@[A-Za-z]+\([^)]*\)\s*)*)@Description\((?P<description>"(?:[^"\\]|\\.)*")\)\s*private '+re.escape(kind)+' '+field+' = '+re.escape(default)+r';',f)
        if not match:raise ValueError('Changed subterrain field '+field)
        token=default[1:-1] if kind=='String' else 'CENOTE' if field=='family' else '[]' if kind.startswith('KList') else default
        values[field+'.type']=kind;values[field+'.description']=json.loads(match.group('description'));values[field+'.default']=token
        for annotation,key in [('MinNumber','minimum'),('MaxNumber','maximum')]:
            bound=re.search('@'+annotation+r'\(([^)]+)\)',match.group('annotations'))
            if bound:values[field+'.'+key]=bound.group(1)
    optional=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s*private IrisSubterrainFluid fluid;',f)
    if not optional:raise ValueError('Changed optional feature fluid')
    values['fluid.type']='IrisSubterrainFluid';values['fluid.description']=json.loads(optional.group(1));values['fluid.default']='null'
    expected={'spacing':('32','8192'),'probability':('0','1'),'length':('16','2048'),'radius':('6','256'),'height':('8','192'),'shapeWarp':('0','1'),
              'fluidDepth':('0','32'),'chimneyHeight':('0','96'),'terraceCount':('2','16'),'pillarSpacing':('0','128'),'formationFraction':('0','0.4')}
    for field,(low,high) in expected.items():
        if values.get(field+'.minimum')!=low or values.get(field+'.maximum')!=high:raise ValueError('Changed subterrain annotation bounds '+field)
    apps=re.findall(r'^\s*([A-Z][A-Z_]*)\s*[,;]?\s*$',a.split('{',1)[1].rsplit('}',1)[0],re.MULTILINE)
    if apps!=['TECTONIC_FAULT','CENOTE','LAVA_TUBE','TRAVERTINE_TERRACES']:raise ValueError('Changed feature families')
    require(l,'WATER, LAVA;', 'if (reader.peek() != JsonToken.STRING)', 'return IrisSubterrainFluid.valueOf(value);')
    values['families']=','.join(apps);values['fluids']='WATER,LAVA'
    require(r,'private double min = 16;','private double max = 32;','return v >= min && v <= max;')
    require(p,'if (options.features().size() > 64)', 'if (feature == null || !feature.isEnabled()) { continue; }',
            'if (!identifiers.add(definition.id()))', 'String id = Objects.requireNonNull(feature.getId(), "Subterrain id").trim();',
            'family == IrisSubterrainFamily.LAVA_TUBE ? IrisSubterrainFluid.LAVA : IrisSubterrainFluid.WATER',
            'int horizontalReach = family == IrisSubterrainFamily.CENOTE ? radius : Math.max(length / 2, radius);',
            'feature.getSpacing() < Math.max(32, 2 * horizontalReach + 8)',
            'feature.getFluidDepth() > Math.min(32, height / 3)',
            '(family == IrisSubterrainFamily.TECTONIC_FAULT && length < 200)',
            'feature.getWorldYRange().getMin() < Integer.MIN_VALUE','feature.getWorldYRange().getMax() > Integer.MAX_VALUE',
            'feature.getWorldYRange().getMin() > feature.getWorldYRange().getMax()',
            '(int) Math.ceil(feature.getWorldYRange().getMin())','(int) Math.floor(feature.getWorldYRange().getMax())',
            'Set<String> allowedRegions = Set.copyOf(Objects.requireNonNull(feature.getAllowedRegions()));',
            'definition.allowedRegions().contains(regionResolver.regionAt((int) x, (int) z))',
            'result.sort(Comparator.comparingInt(SubterrainPlan::priority).reversed().thenComparing(SubterrainPlan::id));',
            'family == IrisSubterrainFamily.TECTONIC_FAULT || fluidDepth == 0 ? 0')
    patterns={}
    for field in ['id','biome','region','solid']:
        patterns[field]=[json.loads(value) for value in re.findall(r'\b'+field+r'\.matches\(("(?:[^"\\]|\\.)*")\)',p)]
    if patterns['id']!=['[a-zA-Z0-9_/-]+'] or patterns['biome']!=patterns['id'] or patterns['region']!=patterns['id']:
        raise ValueError('Changed feature ID/reference patterns')
    if patterns['solid']!=[r'(?:[a-z0-9_.-]+:)?[a-z0-9_/.-]+(?:\[[a-z0-9_=,.-]+\])?',r'(?:minecraft:)?(?:air|cave_air|void_air|water|lava)(?:\[.*\])?']:
        raise ValueError('Changed feature solid syntax/exclusions')
    values['id.pattern']=patterns['id'][0];values['reference.pattern']=patterns['biome'][0];values['solid.pattern']=patterns['solid'][0];values['solid.excluded']=patterns['solid'][1]
    require(c,'if (engine.getDimension().allowsSubterrainFeatures()) { SubterrainRasterizer.validateMaterials(engine.getDimension().getSubterrainFeatures()); }',
            'engine.getDimension().allowsSubterrainFeatures() ? engine.getDimension().getSubterrainFeatures() : List.of()', 'engine.getSeedManager().getBodies()')
    values.update({'id.policy':'trimmedCaseSensitive','range.policy':'inclusiveWorldYCeilFloor','fluid.policy':'familyDefault',
                   'range.context.min':'-48','range.context.max':'48','range.class.min':'16','range.class.max':'32'})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__':Path(sys.argv[8]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:8])))
