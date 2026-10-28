"""Pin structure-placement scheduling and source authoring without running either backend."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact

def extract(*sources):
    d,b,r,p,n,g,a,s,v,x,k=[raw.decode('utf-8') for raw in sources]
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for name,raw in zip(['dimension','biome','region','placement','native','distribution','anchor','suppression','validator','planner','packValidator'],sources):values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text):raise ValueError('Changed structure placement contract: '+contract)
    for host in [d,b,r]:require(host,'private KList<IrisStructurePlacement> structures = new KList<>();')
    declarations={'structures':('KList<String>','new KList<>()'),'nativeStructures':('KList<IrisNativeStructure>','new KList<>()'),'placementId':('String','""'),'nativeSuppression':('NativeStructureSuppression','NativeStructureSuppression.NONE'),'distribution':('StructureDistribution','StructureDistribution.RANDOM_SPREAD'),'spacing':('int','32'),'separation':('int','8'),'salt':('int','165745296'),'density':('double','0.02'),'ringCount':('int','128'),'ringDistance':('int','32'),'ringSpread':('int','3'),'minHeight':('int','-2032'),'maxHeight':('int','2032'),'underground':('boolean','false'),'anchor':('IrisStructureAnchorMode','IrisStructureAnchorMode.LEGACY'),'caveBiomes':('KList<String>','new KList<>()'),'caveAnchorAttempts':('int','8'),'caveAnchorScanStep':('int','1'),'caveMinimumClearance':('int','3'),'terrain':('IrisStructureTerrain','new IrisStructureTerrain()'),'stilt':('IrisStructureStiltSettings','null'),'underwater':('boolean','false')}
    for field,(kind,default) in declarations.items():
        match=re.search(r'(?P<annotations>(?:\s*@[^\n]+\n)+)\s*private '+re.escape(kind)+' '+field+r' = (?P<default>[^;]+);',p)
        if not match or match['default']!=default:raise ValueError('Changed structure placement declaration '+field)
        help=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)',match['annotations'])
        if not help:raise ValueError('Missing placement description')
        values[field+'.description']=json.loads(help.group(1));values[field+'.type']=kind;values[field+'.default']=json.loads(default) if kind=='String' else default.split('.')[-1] if field in ['distribution','anchor','nativeSuppression'] else default
    bounds={'spacing':('1','4096'),'separation':('0','2147483647'),'salt':('-2147483648','2147483647'),'density':('0','1'),'ringCount':('1','2147483647'),'ringDistance':('1','2147483647'),'ringSpread':('1','2147483647'),'minHeight':('-2147483648','2147483647'),'maxHeight':('-2147483648','2147483647'),'caveAnchorAttempts':('1','64'),'caveAnchorScanStep':('1','16'),'caveMinimumClearance':('1','64')}
    for field,(low,high) in bounds.items():
        values[field+'.minimum']=low;values[field+'.maximum']=high
        for annotation,key in [('MinNumber',low),('MaxNumber',high)]:
            # Fields without a corresponding annotation are guarded by the pinned validator/int declaration.
            match=re.search(r'(?P<annotations>(?:\s*@[^\n]+\n)+)\s*private (?:int|double) '+field+r' = [^;]+;',p)
            bound=re.search('@'+annotation+r'\(([^)]+)\)',match['annotations'])
            if bound and bound.group(1)!=key:raise ValueError('Changed placement annotation '+field)
    for text,field,names in [(g,'distribution',['RANDOM_SPREAD','DENSITY','CONCENTRIC_RINGS']),(a,'anchor',['LEGACY','SURFACE','HEIGHT_BAND','CAVE_FLOOR','CAVE_CEILING','CAVE_CENTER','CAVE_ANY']),(s,'nativeSuppression',['NONE','REPLACE_SOURCE'])]:
        items=re.findall(r'@Description\(("(?:[^"\\]|\\.)*")\)\s*([A-Z_]+)\s*[,;}]',text)
        if [name for _,name in items]!=names:raise ValueError('Changed placement enum '+field)
        values[field+'.choices']=','.join(names)
        for description,name in items:values[field+'.'+name+'.description']=json.loads(description)
    require(p,'return underground ? IrisStructureAnchorMode.HEIGHT_BAND : IrisStructureAnchorMode.SURFACE;','return structures != null && !structures.isEmpty();','return nativeStructures != null && !nativeStructures.isEmpty();')
    require(n,'private String structure = "";','private int weight = 1;','private IrisJigsawConfiguration jigsaw = null;')
    for field in ['structure','weight']:
        match=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s*private (?:String|int) '+field+r' = [^;]+;',n)
        if not match:raise ValueError('Changed native source help')
        values['native.'+field+'.description']=json.loads(match.group(1))
    values.update({'native.weight.type':'int','native.weight.default':'1','native.weight.minimum':'1','native.weight.maximum':'2147483647'})
    require(v,'if (hasIrisStructures == hasNativeStructures)', 'if (spacing != null && separation != null && separation >= spacing)',
            'if (minimumHeight != null && maximumHeight != null && minimumHeight > maximumHeight)',
            'long ringRadius = (long) Math.ceilDiv(ringCount, ringSpread) * ringDistance;','if (ringRadius > Integer.MAX_VALUE)',
            'placementIds.putIfAbsent(normalizedId, path)','String normalizedId = placementId.trim();',
            '!placementId.equals(placementId.trim())','nativeStructures total weight exceeds ',
            'sourceKeys.add(structureKey.toLowerCase(Locale.ROOT))','!structureKeys.contains(structureKey)',
            '"spacing", 1, 4096, blockingErrors','"separation", 0, Integer.MAX_VALUE, blockingErrors','"minHeight", Integer.MIN_VALUE, Integer.MAX_VALUE, blockingErrors')
    require(x,'if (iris == nativeStructure)', 'placement.getAnchor() != IrisStructureAnchorMode.LEGACY', 'totalWeight = Math.addExact(totalWeight, source.getWeight());','if (totalWeight > Integer.MAX_VALUE)')
    require(k,'STRUCTURES_FOLDER = "structures";', 'STRUCTURE_HOST_FOLDERS = List.of(DIMENSIONS_FOLDER, "regions", "biomes");')
    values.update({'backend.policy':'exactlyOneBackend','identity.policy':'explicitNewIdLocalUniqueness','advanced.policy':'separateJigsawControls','registry.policy':'unverified'})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__':Path(sys.argv[12]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:12])))
