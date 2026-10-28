"""Pin dimension generation/Studio authoring; never load native generators."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact

def extract(*sources):
    d,m,e,s,mantle,engine,layers,terrain=[raw.decode('utf-8') for raw in sources]
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for name,raw in zip(['dimension','mode','modeType','studio','mantle','engine','layers','terrain'],sources):values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text):raise ValueError('Changed dimension generation contract: '+contract)
    expected={'debugChunkCrossSections':('boolean','false'), 'explodeBiomePalettes':('boolean','false'), 'debugSmartBore':('boolean','false'),
              'useMantle':('boolean','true'), 'preventLeafDecay':('boolean','false'), 'hideOresForHiddenOre':('boolean','false'),
              'explodeBiomePaletteSize':('int','3'), 'debugCrossSectionsMod':('int','3'), 'studioMode':('StudioMode','StudioMode.NORMAL'),
              'mode':('IrisDimensionMode','new IrisDimensionMode()')}
    for field,(kind,default) in expected.items():
        pattern=r'(?P<annotations>(?:\s*@[^\n]+\n)+)\s*private '+kind+' '+field+r' = (?P<default>[^;]+);'
        match=re.search(pattern,d)
        if not match or match['default']!=default:raise ValueError('Changed generation declaration '+field)
        description=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)',match['annotations'])
        if not description:raise ValueError('Missing generation description '+field)
        values[field+'.description']=json.loads(description.group(1));values[field+'.default']=default.split('.')[-1] if kind=='StudioMode' else '{}' if field=='mode' else default
        for annotation,key in [('MinNumber','minimum'),('MaxNumber','maximum')]:
            bound=re.search('@'+annotation+r'\((\d+)\)',match['annotations'])
            if bound:values[field+'.'+key]=bound.group(1)
    for field,minimum in [('explodeBiomePaletteSize','1'),('debugCrossSectionsMod','2')]:
        if values.get(field+'.minimum')!=minimum or values.get(field+'.maximum')!='16':raise ValueError('Changed generation integer bounds')
    require(d,'@Required @Description("Define the mode of this dimension (required!)") private IrisDimensionMode mode = new IrisDimensionMode();',
            'return useMantle && carvingEnabled && mode != null && mode.getType() == IrisDimensionModeType.OVERWORLD && !disabledComponents.contains(ReservedFlag.CARVED);')
    require(m,'@Snippet("dimension-mode")','private IrisDimensionModeType type = IrisDimensionModeType.OVERWORLD;','return type.create(engine);')
    values['type.default']='OVERWORLD';values['type.description']='The dimension type'
    modes=re.findall(r'@Description\(("(?:[^"\\]|\\.)*")\)\s*([A-Z_]+)\((Mode\w+)::new\)',e)
    if [(name,factory) for _,name,factory in modes]!=[('OVERWORLD','ModeOverworld'),('SUPERFLAT','ModeSuperFlat'),('ENCLOSURE','ModeEnclosure'),('ISLANDS','ModeIslands')]:raise ValueError('Changed mode factories')
    if re.findall(r'^\s*([A-Z_]+)\(Mode\w+::new\)',e,re.M)!=[name for _,name,_ in modes]:raise ValueError('Unaudited dimension mode')
    for description,name,_ in modes:values['mode.'+name+'.description']=json.loads(description)
    values['modes']=','.join(name for _,name,_ in modes)
    studio=re.findall(r'@Description\(("(?:[^"\\]|\\.)*")\)\s*([A-Z][A-Za-z0-9_]*)\s*[,;]',s)
    names=['NORMAL','BIOME_BUFFET_1x1','BIOME_BUFFET_3x3','BIOME_BUFFET_5x5','BIOME_BUFFET_9x9','BIOME_BUFFET_18x18','BIOME_BUFFET_36x36','REGION_BUFFET','OBJECT_BUFFET']
    if [name for _,name in studio]!=names or re.findall(r'^\s*([A-Z][A-Za-z0-9_]*)\s*[,;]',s.split('public int biomeSizeChunks()',1)[0],re.M)!=names:raise ValueError('Changed Studio modes')
    for description,name in studio:values['studio.'+name+'.description']=json.loads(description)
    for size in [1,3,5,9,18,36]:require(s,f'case BIOME_BUFFET_{size}x{size} -> {size};')
    require(s,'case NORMAL, REGION_BUFFET -> c.setStudioGenerator(null);',
            'case BIOME_BUFFET_1x1, BIOME_BUFFET_3x3, BIOME_BUFFET_5x5, BIOME_BUFFET_9x9, BIOME_BUFFET_18x18, BIOME_BUFFET_36x36 -> c.setStudioGenerator(new BiomeBuffetGenerator(c.getEngine()));',
            'case OBJECT_BUFFET -> c.setStudioGenerator(new ObjectStudioGenerator(c.getEngine()));')
    require(mantle,'return getEngine().getDimension().isPreventLeafDecay();','return getEngine().getDimension().isDebugSmartBore();','if (!getEngine().getDimension().isUseMantle()) { return; }')
    require(engine,'if (getDimension().isDebugChunkCrossSections() && ((x >> 4) % getDimension().getDebugCrossSectionsMod() == 0 || (z >> 4) % getDimension().getDebugCrossSectionsMod() == 0))')
    require(layers,'if (dim.isExplodeBiomePalettes()) { for (int j = 0; j < dim.getExplodeBiomePaletteSize(); j++) { data.add(BARRIER.get());')
    require(terrain,'hideOres = dimension.isHideOresForHiddenOre();','dimensionSurfaceOres = hideOres ? IrisOreBands.EMPTY : dimension.getSurfaceOreBands();')
    values.update({'studioModes':','.join(names),'mode.policy':'inlineOnly','studio.policy':'serverOnly'})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__':Path(sys.argv[9]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:9])))
