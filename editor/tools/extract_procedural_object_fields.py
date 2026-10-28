"""Pin six procedural collections and their shared placement/default contracts."""
import hashlib,json,re,sys
from pathlib import Path
from extract_river_material_fields import compact
KINDS={'trees':'IrisProceduralTree','ruins':'IrisRuin','formations':'IrisFormation','coral':'IrisCoral','fungi':'IrisFungus','crystals':'IrisCrystal'}
COMMON=['name','chance','density','variants','seed','mode','carvingSupport','underwater']
def extract(*sources):
    b,r,o,*tail=[raw.decode('utf-8') for raw in sources];classes=tail[:6];m,c,n,i=tail[6:]
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f','collections':','.join(KINDS),'common':','.join(COMMON)}
    for name,raw in zip(['biome','region','owner',*KINDS,'modes','carvings','mantle','interface'],sources):values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text):raise ValueError('Changed procedural placement contract: '+contract)
    for host in [b,r]:require(host,'private IrisProceduralObjects proceduralObjects = new IrisProceduralObjects();')
    expected_modes=['CENTER_HEIGHT','MAX_HEIGHT','FAST_MAX_HEIGHT','MIN_HEIGHT','FAST_MIN_HEIGHT','STILT','FAST_STILT','MIN_STILT','FAST_MIN_STILT','CENTER_STILT','ERODE_STILT','ORGANIC_STILT','CEILING_HANG','VACUUM','VACUUM_HIGH','VACUUM_FAST','VACUUM_ORGANIC','VACUUM_WAVY','PAINT','FLOATING']
    # Extract the complete annotated enum, preserving native order and help.
    for text,key in [(m,'mode'),(c,'carvingSupport')]:
        items=re.findall(r'@Description\(("(?:[^"\\]|\\.)*")\)\s*([A-Z_]+)\s*[,;]',text)
        if not items:raise ValueError('Missing procedural enum '+key)
        if key=='mode' and [name for _,name in items]!=expected_modes:raise ValueError('Changed placement choices')
        if key=='carvingSupport' and [name for _,name in items]!=['SURFACE_ONLY','CARVING_ONLY','ANYWHERE']:raise ValueError('Changed carving choices')
        values[key+'.choices']=','.join(name for _,name in items)
        for description,name in items:values[key+'.'+name+'.description']=json.loads(description)
    for (kind,cls),text in zip(KINDS.items(),classes):
        require(o,'private KList<'+cls+'> '+kind+' = new KList<>();','if ('+kind+' != null) { all.addAll('+kind+'); }')
        match=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s*private KList<'+cls+'> '+kind,o)
        values[kind+'.description']=json.loads(match.group(1))
        fields=COMMON+(['plausible'] if kind=='trees' else ['surfaceSupportBuffer'] if kind=='formations' else [])
        values[kind+'.fields']=','.join(fields)
        declarations={'name':('String',json.dumps({'trees':'procedural-tree','ruins':'ruin','formations':'formation','coral':'coral','fungi':'fungus','crystals':'crystal'}[kind])), 'chance':('double','0.05' if kind=='ruins' else '0.02' if kind=='formations' else '0.2' if kind=='crystals' else '0.4'),'density':('int','1'),'variants':('int','8' if kind=='trees' else '6'),'seed':('long','1337'),'mode':('ObjectPlaceMode','ObjectPlaceMode.MIN_HEIGHT' if kind=='ruins' else 'ObjectPlaceMode.CENTER_HEIGHT'),'carvingSupport':('CarvingMode','CarvingMode.CARVING_ONLY' if kind=='crystals' else 'CarvingMode.SURFACE_ONLY'),'underwater':('boolean','true' if kind=='coral' else 'false'),'plausible':('boolean','true'),'surfaceSupportBuffer':('int','3')}
        for field in fields:
            kind_type,default=declarations[field]
            match=re.search(r'(?P<annotations>(?:\s*@[^\n]+\n)+)\s*private '+kind_type+' '+field+r' = (?P<default>[^;]+);',text)
            if not match or match['default']!=default:raise ValueError('Changed procedural declaration '+kind+'.'+field)
            description=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)',match['annotations'])
            if not description:raise ValueError('Missing procedural help')
            prefix=kind+'.'+field+'.';values[prefix+'type']=kind_type;values[prefix+'description']=json.loads(description.group(1));values[prefix+'default']=json.loads(default) if kind_type=='String' else default.split('.')[-1] if field in ['mode','carvingSupport'] else default
            for annotation,key in [('MinNumber','minimum'),('MaxNumber','maximum')]:
                bound=re.search('@'+annotation+r'\(([^)]+)\)',match['annotations'])
                if bound:values[prefix+key]=bound.group(1)
            expected_bounds={'chance':('0','1'),'density':('1',None),'variants':('1','64'),'surfaceSupportBuffer':('0','16')}.get(field)
            if expected_bounds and (values.get(prefix+'minimum'),values.get(prefix+'maximum'))!=expected_bounds:raise ValueError('Changed procedural bounds '+kind+'.'+field)
        require(text,'implements IrisProceduralPlacement','int count = Math.max(1, variants);','placement.setChance(chance);','placement.setDensity(density);','placement.setMode(mode);','placement.setCarvingSupport(carvingSupport);','placement.setUnderwater(underwater);','return variantCache.aquire(() ->')
    require(n,'for (IrisProceduralPlacement p : proceduralObjects.getAllPlacements())','boolean chancePassed = passesProceduralChance(rng, p.getChance());','if (!chancePassed) { continue; }','int density = Math.max(1, p.getDensity());','for (int i = 0; i < density; i++)','surfaceBiome.getProceduralObjects()','region.getProceduralObjects()','caveBiome.getProceduralObjects()')
    require(i,'IrisObjectPlacement asPlacement();','KList<IrisObject> getVariantObjects(IrisData data);')
    values.update({'generation.policy':'notExecuted','lists.null':'empty','owner.null':'noProceduralObjects','newEntry.policy':'emptyNativeDefaults','limit.policy':'64PerCollection','seed.policy':'exactInt64','chance.policy':'perChunkBeforeDensity'})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')
if __name__=='__main__':Path(sys.argv[14]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:14])))
