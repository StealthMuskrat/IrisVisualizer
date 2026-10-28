"""Read pinned shape definitions and enum help without constructing native generators."""
import hashlib,json,re,sys
from pathlib import Path
from extract_river_material_fields import compact
CLASSES = {'trees': ('IrisProceduralTree', 'tree', 'ProceduralTreeGenerator'), 'ruins': ('IrisRuin', 'ruin', 'RuinGenerator'), 'formations': ('IrisFormation', 'formation', 'FormationGenerator'), 'coral': ('IrisCoral', 'coral', 'CoralGenerator'), 'fungi': ('IrisFungus', 'fungus', 'FungusGenerator'), 'crystals': ('IrisCrystal', 'crystal', 'CrystalGenerator')}
FIELDS = {'trees': ['profile', 'heightMin', 'heightMax', 'trunkWidth', 'trunkShape', 'shapeStart', 'shapeEnd', 'shapeSteepness', 'shapeBase', 'shapePeriod', 'shapeAmplitude', 'shapePeakOffset', 'shapeFloor', 'leanAzimuth', 'leanAngle', 'trunkCurve', 'curveSteepness', 'leanAzimuthMode', 'azimuthStart', 'azimuthEnd', 'azimuthTurns', 'azimuthAmplitude', 'azimuthPeriod', 'azimuthOffset', 'azimuthScale', 'azimuthWhorlCount', 'secondaryLeafFraction', 'secondaryTrunkStart', 'secondaryTrunkEnd', 'roots', 'rootStyle', 'rootDepth', 'rootFlare', 'trunkForks', 'forkHeight', 'forkAngle'], 'ruins': ['form', 'heightMin', 'heightMax', 'widthMin', 'widthMax', 'lengthMin', 'lengthMax', 'mossiness', 'weatheringScale', 'erosion', 'erosionScale', 'buriedFraction'], 'formations': ['form', 'strataThickness', 'heightMin', 'heightMax', 'baseWidthMin', 'baseWidthMax', 'topWidth', 'profile', 'profileWaist', 'profileWaistFloor', 'lean', 'leanAzimuth', 'roughness', 'jitter', 'hoodooCapRadius', 'hoodooCapHeight', 'archSpan', 'archThickness', 'archAsymmetry', 'basaltColumns', 'basaltColumnRadius', 'basaltHeightVariance', 'icebergPeaks', 'fractureCount', 'fractureSeparation', 'spiralTurns', 'spiralRadius', 'spiralThickness', 'overhangReach', 'overhangDrop', 'roomHeightFraction'], 'coral': ['waterlogged', 'form', 'tipChance', 'heightMin', 'heightMax', 'spread', 'sway', 'branchCount', 'branchLength', 'branchElevation', 'branchAzimuth', 'subBranches', 'subBranchCount', 'subBranchScale', 'tipClusterRadius', 'brainRadius', 'brainRoughness', 'pillarRadius', 'fanWidth', 'tendrilCount'], 'fungi': ['stemHeightMin', 'stemHeightMax', 'stemWidth', 'stemCurve', 'stemLeanAzimuth', 'stemWaveAmplitude', 'stemWavePeriods', 'capShape', 'capRadiusMin', 'capRadiusMax', 'capThickness', 'capSquish', 'capDroop', 'capOverhang', 'gillChance', 'spotChance', 'shelf', 'shelfRadius'], 'crystals': ['growthSurface', 'tipChance', 'glow', 'baseRadius', 'baseNoise', 'shardCountMin', 'shardCountMax', 'shardLengthMin', 'shardLengthMax', 'shardBaseRadius', 'shardTaper', 'spreadAngle', 'distribution', 'jitter']}
ENUMS = {'IrisTreeProfile': 'tree', 'IrisTreeFunction': 'tree', 'IrisTreeAzimuthMode': 'tree', 'IrisTreeRootStyle': 'tree', 'IrisRuinForm': 'ruin', 'IrisFormationForm': 'formation', 'IrisFormationProfile': 'formation', 'IrisCoralForm': 'coral', 'IrisCoralBranchAzimuth': 'coral', 'IrisFungusCapShape': 'fungus', 'IrisCrystalSurface': 'crystal', 'IrisCrystalDistribution': 'crystal'}

FILES={name: 'generation/decoration/'+folder+'/'+name+'.java' for name,folder,generator in CLASSES.values()}
FILES.update({generator:'generation/decoration/'+folder+'/'+generator+'.java' for name,folder,generator in CLASSES.values()})
FILES.update({name:'generation/decoration/'+folder+'/'+name+'.java' for name,folder in ENUMS.items()})
FILES['CrystalShardBuilder']='generation/decoration/crystal/CrystalShardBuilder.java'

def extract(sources):
    texts={name:raw.decode('utf-8') for name,raw in sources.items()}
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for name in FILES:values[name+'SourceSha256']=hashlib.sha256(sources[name]).hexdigest()
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text):raise ValueError('Changed procedural shape contract: '+contract)
    enums={}
    for name in ENUMS:
        text=texts[name]
        items=re.findall(r'@Description\(("(?:[^"\\]|\\.)*")\)\s*([A-Z_0-9]+)\s*[,;}]',text)
        constants=re.findall(r'^    ([A-Z_0-9]+)\s*[,;}]?',text,re.M)
        if not items or [item[1] for item in items]!=constants:raise ValueError('Incomplete enum descriptions '+name)
        enums[name]=items
    for kind,(name,folder,generator) in CLASSES.items():
        text=texts[name];values[kind+'.fields']=','.join(FIELDS[kind])
        for field in FIELDS[kind]:
            match=re.search(r'(?P<annotations>(?:\s*@[^\n]+\n)+)\s*private (?P<type>\w+) '+field+r' = (?P<default>[^;]+);',text)
            if not match:raise ValueError('Missing annotated shape field '+kind+'.'+field)
            t,default=match['type'],match['default'];prefix=kind+'.'+field+'.'
            if t not in ['int','double','boolean'] and t not in enums:raise ValueError('Unsupported shape field type '+t)
            help=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)',match['annotations'])
            if not help:raise ValueError('Missing shape description')
            values[prefix+'type']=t;values[prefix+'description']=json.loads(help.group(1))
            if t in enums:
                if not default.startswith(t+'.') or default.split('.')[-1] not in [name for _,name in enums[t]]:raise ValueError('Unknown shape default '+default)
                values[prefix+'default']=default.split('.')[-1];values[prefix+'choices']=','.join(name for _,name in enums[t])
                for description,enum in enums[t]:values[prefix+enum+'.description']=json.loads(description)
            else:
                if t=='boolean' and default not in ['true','false']:raise ValueError('Unsupported shape boolean default')
                if t!='boolean' and not re.fullmatch(r'-?\d+(?:\.\d+)?',default):raise ValueError('Unsupported shape numeric default')
                values[prefix+'default']=default
            for annotation,key in [('MinNumber','minimum'),('MaxNumber','maximum')]:
                bound=re.search('@'+annotation+r'\(([^)]+)\)',match['annotations'])
                if bound:
                    if not re.fullmatch(r'-?\d+(?:\.\d+)?',bound.group(1)):raise ValueError('Unsupported shape bound')
                    values[prefix+key]=bound.group(1)
        require(text,generator+'.generate(this,')
        require(texts[generator],'public static IrisObject generate('+name+' ')
    # Native ranges normalize their endpoints; authoring keeps their order and supports reversed pairs.
    require(texts['IrisProceduralTree'],'Math.min(heightMin, heightMax)','Math.max(heightMin, heightMax)')
    require(texts['IrisFungus'],'Math.min(stemHeightMin, stemHeightMax)','Math.max(stemHeightMin, stemHeightMax)')
    for name,left,right in [('FormationGenerator','f.getHeightMin()','f.getHeightMax()'),('FormationGenerator','f.getBaseWidthMin()','f.getBaseWidthMax()'),('CoralGenerator','coral.getHeightMin()','coral.getHeightMax()'),('FungusGenerator','fungus.getCapRadiusMin()','fungus.getCapRadiusMax()'),('CrystalShardBuilder','crystal.getShardCountMin()','crystal.getShardCountMax()'),('CrystalShardBuilder','crystal.getShardLengthMin()','crystal.getShardLengthMax()')]:
        require(texts[name],'Math.min('+left+', '+right+')','Math.max('+left+', '+right+')')
    require(texts['RuinGenerator'],'pick(ruin.getHeightMin(), ruin.getHeightMax(),','pick(ruin.getWidthMin(), ruin.getWidthMax(),','pick(ruin.getLengthMin(), ruin.getLengthMax(),','int min = Math.min(lo, hi);','int max = Math.max(lo, hi);')
    values.update({'range.policy':'nativeNormalizedOrderPreserved','bounds.policy':'nativeAnnotationsAndWidths','generation.policy':'notExecuted','shape.fields':str(sum(map(len,FIELDS.values())))})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')

def read_sources(root):return {name:(Path(root)/(name+'.java')).read_bytes() for name in FILES}
if __name__=='__main__':Path(sys.argv[2]).write_bytes(extract(read_sources(sys.argv[1])))
