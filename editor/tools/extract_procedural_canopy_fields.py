"""Pin canopy/layer/branch definitions, native defaults and builder dispatch."""
import hashlib,json,re,sys
from pathlib import Path
from extract_river_material_fields import compact
CLASSES = {'canopy': 'IrisTreeCanopy', 'layers': 'IrisTreeLayer', 'branches': 'IrisTreeBranches', 'subBranches': 'IrisTreeSubBranches'}
FIELDS = {'canopy': ['startAngle', 'squish', 'mode', 'leafDensity', 'crownStretchX', 'crownStretchZ', 'layers', 'branches'], 'layers': ['yOffset', 'radius'], 'branches': ['probabilityFunction', 'probabilityConstant', 'probabilityBase', 'probabilityCrown', 'probabilitySteepness', 'probabilityMidpoint', 'probabilityExponent', 'probabilityMean', 'probabilityStd', 'probabilityScale', 'probabilityPeriods', 'lengthFunction', 'lengthBase', 'lengthCrown', 'lengthConstant', 'lengthMax', 'lengthSteepness', 'azimuthMode', 'azimuth', 'elevation', 'sag', 'branchDepth', 'leafStartUp', 'clusterRadius', 'clusterMode', 'clusterDensity', 'subBranches'], 'subBranches': ['count', 'pitchDelta', 'yawDelta', 'lengthScale', 'sag', 'clusterRadius', 'clusterMode', 'clusterDensity']}
SCALARS = {'canopy': ['startAngle', 'squish', 'mode', 'leafDensity', 'crownStretchX', 'crownStretchZ'], 'layers': ['yOffset', 'radius'], 'branches': ['probabilityFunction', 'probabilityConstant', 'probabilityBase', 'probabilityCrown', 'probabilitySteepness', 'probabilityMidpoint', 'probabilityExponent', 'probabilityMean', 'probabilityStd', 'probabilityScale', 'probabilityPeriods', 'lengthFunction', 'lengthBase', 'lengthCrown', 'lengthConstant', 'lengthMax', 'lengthSteepness', 'azimuthMode', 'azimuth', 'elevation', 'sag', 'branchDepth', 'leafStartUp', 'clusterRadius', 'clusterMode', 'clusterDensity'], 'subBranches': ['count', 'pitchDelta', 'yawDelta', 'lengthScale', 'sag', 'clusterRadius', 'clusterMode', 'clusterDensity']}

ENUMS=['IrisTreeLeafMode','IrisTreeBranchProbability','IrisTreeFunction','IrisTreeAzimuthMode']
FILES=list(CLASSES.values())+ENUMS+['IrisProceduralTree','TreeCanopyBuilder','TreeFunctions','ProceduralTreeGenerator']
def read_sources(root):return {name:(Path(root)/(name+'.java')).read_bytes() for name in FILES}
def extract(sources):
    texts={name:raw.decode('utf-8') for name,raw in sources.items()};values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for name in FILES:values[name+'SourceSha256']=hashlib.sha256(sources[name]).hexdigest()
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text):raise ValueError('Changed canopy contract: '+contract)
    enums={}
    for name in ENUMS:
        items=re.findall(r'@Description\(("(?:[^"\\]|\\.)*")\)\s*([A-Z_0-9]+)\s*[,;}]',texts[name]);constants=re.findall(r'^    ([A-Z_0-9]+)\s*[,;}]?',texts[name],re.M)
        if not items or [name for _,name in items]!=constants:raise ValueError('Incomplete canopy enum '+name)
        enums[name]=items
    for group,name in CLASSES.items():
        text=texts[name];require(text,'@NoArgsConstructor');values[group+'.fields']=','.join(FIELDS[group])
        for field in FIELDS[group]:
            match=re.search(r'(?P<annotations>(?:\s*@[^\n]+\n)+)\s*private (?P<type>[\w<>]+) '+field+r' = (?P<default>[^;]+);',text)
            if not match:raise ValueError('Missing canopy field '+field)
            t,default=match['type'],match['default'];prefix=group+'.'+field+'.'
            desc=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)',match['annotations'])
            if not desc:raise ValueError('Missing canopy help')
            values[prefix+'type']=t;values[prefix+'description']=json.loads(desc.group(1))
            if field not in SCALARS[group]:
                expected={'layers':('KList<IrisTreeLayer>','new KList<>()'),'branches':('IrisTreeBranches','null'),'subBranches':('IrisTreeSubBranches','null')}.get(field)
                if (t,default)!=expected:raise ValueError('Changed nested canopy owner default')
                values[prefix+'default']=default
            elif t in enums:
                if not default.startswith(t+'.') or default.split('.')[-1] not in [name for _,name in enums[t]]:raise ValueError('Unknown canopy enum default')
                values[prefix+'default']=default.split('.')[-1];values[prefix+'choices']=','.join(name for _,name in enums[t])
                for description,name in enums[t]:values[prefix+name+'.description']=json.loads(description)
            else:
                if t not in ['int','double','boolean']:raise ValueError('Unsupported canopy scalar')
                if t=='boolean' and default not in ['true','false']:raise ValueError('Invalid canopy flag')
                if t!='boolean' and not re.fullmatch(r'-?\d+(?:\.\d+)?',default):raise ValueError('Invalid canopy numeric default')
                values[prefix+'default']=default
            for annotation,key in [('MinNumber','minimum'),('MaxNumber','maximum')]:
                bound=re.search('@'+annotation+r'\(([^)]+)\)',match['annotations'])
                if bound:values[prefix+key]=bound[1]
    require(texts['IrisProceduralTree'],'private IrisTreeCanopy canopy = new IrisTreeCanopy();')
    builder=texts['TreeCanopyBuilder']
    require(builder,'if (canopy == null) { canopy = new IrisTreeCanopy(); }','IrisTreeBranches branches = canopy.getBranches();','if (explicit != null && !explicit.isEmpty())','out[i][0] = explicit.get(i).getYOffset();','out[i][1] = explicit.get(i).getRadius();','return TreeProfiles.presetLayers(profile, height, branchDriven);','double[][] crown = new double[][]{layers[layers.length - 1]};','TreeFunctions.branchProbability(branches, t, branchSeed)','TreeFunctions.branchLength(branches, t)','int depth = branches.getBranchDepth();','if (sub == null || depth <= 0) { return; }','sub, depth - 1, seed + si * 131L + 17L')
    require(texts['TreeFunctions'],'public static double branchProbability(IrisTreeBranches b, double t, long seed)','public static double branchLength(IrisTreeBranches b, double t)','switch (b.getProbabilityFunction())','switch (b.getLengthFunction())')
    require(texts['ProceduralTreeGenerator'],'TreeCanopyBuilder.build(canvas, tree, height,')
    values.update({'layers.policy':'explicitNonemptyElseProfile','branch.policy':'nonnullEnablesBranchCrown','subBranch.policy':'configuredAndDepthPositive','creation.policy':'emptyNativeClassDefaults','scalar.count':str(sum(map(len,SCALARS.values()))),'execution.policy':'disabled'})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')
if __name__=='__main__':Path(sys.argv[2]).write_bytes(extract(read_sources(sys.argv[1])))
