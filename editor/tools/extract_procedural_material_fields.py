"""Pin procedural material roles, optional palettes and native resolution precedence."""
import hashlib,json,re,sys
from pathlib import Path
from extract_river_material_fields import compact
CLASSES = {'trees': 'IrisProceduralTree', 'ruins': 'IrisRuin', 'formations': 'IrisFormation', 'coral': 'IrisCoral', 'fungi': 'IrisFungus', 'crystals': 'IrisCrystal'}
MATERIALS = {'trees': ['trunk', 'leaves', 'secondaryLeaves', 'secondaryTrunk'], 'ruins': ['block', 'weatheredBlock'], 'formations': ['block', 'capBlock'], 'coral': ['block', 'tipBlock'], 'fungi': ['stem', 'cap', 'gillBlock', 'spotBlock'], 'crystals': ['block', 'tipBlock', 'glowBlock', 'baseBlock']}
PALETTES = {'trees': ['trunkPalette', 'leavesPalette', 'secondaryLeavesPalette', 'secondaryTrunkPalette'], 'ruins': ['blockPalette', 'weatheringPalette'], 'formations': ['blockPalette', 'capPalette', 'strataPalette'], 'coral': ['blockPalette', 'tipPalette'], 'fungi': ['stemPalette', 'capPalette', 'gillPalette', 'spotPalette'], 'crystals': ['blockPalette', 'tipPalette', 'basePalette']}

FILES=list(CLASSES.values())+['ProceduralTreeGenerator','RuinGenerator','FormationGenerator','CoralGenerator','FungusGenerator','CrystalGenerator','TreeBlockResolver','FormationBlockResolver','IrisProceduralBlocks','IrisMaterialPalette','IrisBlockData','IrisStiltSettings','IrisObjectPlacementRunner','IrisObjectShaping']
def read_sources(root):return {name:(Path(root)/(name+'.java')).read_bytes() for name in FILES}
def extract(sources):
    texts={name:raw.decode('utf-8') for name,raw in sources.items()};values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for name in FILES:values[name+'SourceSha256']=hashlib.sha256(sources[name]).hexdigest()
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text):raise ValueError('Changed procedural material contract: '+contract)
    for kind,name in CLASSES.items():
        text=texts[name]
        for field in MATERIALS[kind]+PALETTES[kind]:
            t='String' if field in MATERIALS[kind] else 'IrisMaterialPalette'
            match=re.search(r'(?P<annotations>(?:\s*@[^\n]+\n)+)\s*private '+t+' '+field+r' = (?P<default>[^;]+);',text)
            if not match:raise ValueError('Missing material role '+kind+'.'+field)
            description=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)',match['annotations'])
            if not description:raise ValueError('Missing material role description')
            default=match['default']
            if t=='IrisMaterialPalette' and default!='null':raise ValueError('Changed optional procedural palette default')
            if t=='String' and default!='null':default=json.loads(default)
            prefix=kind+'.'+field+'.';values[prefix+'type']=t;values[prefix+'default']=default;values[prefix+'description']=json.loads(description.group(1))
    p=texts['IrisMaterialPalette'];b=texts['IrisBlockData']
    require(p,'private IrisGeneratorStyle style = NoiseStyle.STATIC.style();','private double zoom = 5;','private KList<IrisBlockData> palette = new KList<IrisBlockData>().qadd(new IrisBlockData("STONE"));','if (blockDataSize == 0) { return null; }','for (int i = 0; i < ix.getWeight(); i++) { blockData.add(bx); }')
    require(b,'private int weight = 1;')
    require(texts['IrisProceduralBlocks'],'return palette != null && palette.getPalette() != null && !palette.getPalette().isEmpty();','if (paletteSet(palette)) { return palette.get(paletteRng, x, y, z, data); }','if (block != null && !block.isEmpty()) { return B.getStateOrNull(block, false); }')
    require(texts['IrisStiltSettings'],'private IrisMaterialPalette palette;')
    require(texts['TreeBlockResolver'],'tree.getTrunkPalette()','tree.getLeavesPalette()','tree.getSecondaryLeavesPalette()','tree.getSecondaryTrunkPalette()')
    require(texts['FormationBlockResolver'],'getBlockPalette()','getCapPalette()','getStrataPalette()')
    for name,contract in [('RuinGenerator','ruin.getWeatheringPalette()'),('CoralGenerator','coral.getTipPalette()'),('FungusGenerator','fungus.getGillPalette()'),('CrystalGenerator','crystal.getBasePalette()')]:require(texts[name],contract,'IrisProceduralBlocks.resolve(')
    require(texts['IrisObjectPlacementRunner'],'config.getStiltSettings().getPalette()')
    zoom=re.search(r'@MinNumber\(([^)]+)\)\s*@Description\("The terrain zoom[^\n]*\)\s*private double zoom = 5;',p)
    weight=re.search(r'@MinNumber\((\d+)\)\s*@MaxNumber\((\d+)\)\s*@Description\("[^\n]*"\)\s*private int weight = (\d+);',b)
    if not zoom or zoom[1]!='0.0001' or not weight or weight.groups()!=('1','1000','1'):raise ValueError('Changed shared palette bounds')
    values.update({'palette.classBlock':'STONE','palette.classStyle':'STATIC','palette.classZoom':'5','palette.activation':'nonempty','palette.absentOrNull':'nativeRoleFallback','stilt.absentOrNull':'objectLayerFallback','newBlocks.policy':'minecraftOrUnnamespacedSyntax','resolution.policy':'notExecuted'})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')
if __name__=='__main__':Path(sys.argv[2]).write_bytes(extract(read_sources(sys.argv[1])))
