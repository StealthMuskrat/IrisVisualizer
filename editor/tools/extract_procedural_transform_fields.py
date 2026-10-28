"""Pin procedural nested transform/deformation defaults and native dispatch."""
import hashlib,json,re,sys
from pathlib import Path
from extract_river_material_fields import compact
CLASSES=['IrisProceduralTree','IrisRuin','IrisFormation','IrisCoral','IrisFungus','IrisCrystal']
GROUPS={'translate':('IrisObjectTranslate',['x','y','yRandom','z']),'rotation':('IrisObjectRotation',['enabled']),'axis':('IrisAxisRotationClamp',['enabled','min','max','interval']),'clamp':('IrisObjectLimit',['minimumHeight','maximumHeight']),'stiltSettings':('IrisStiltSettings',['yMax','yRand','overStilt','organicMaxScan','organicJitter','organicScratch']),'vacuumSettings':('IrisVacuumSettings',['radius','falloff','organicJitter','waveAmplitude','waveScale'])}
FILES=CLASSES+[name for name,fields in GROUPS.values()]+['IrisObjectPlacementRunner','IrisObjectVacuum','IrisObjectShaping']
def read_sources(root):return {name:(Path(root)/(name+'.java')).read_bytes() for name in FILES}
def extract(sources):
    texts={name:raw.decode('utf-8') for name,raw in sources.items()};values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for name in FILES:values[name+'SourceSha256']=hashlib.sha256(sources[name]).hexdigest()
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text):raise ValueError('Changed procedural transform contract: '+contract)
    for name in CLASSES:
        text=texts[name]
        for field,kind,default in [('translate','IrisObjectTranslate','new IrisObjectTranslate()'),('rotation','IrisObjectRotation','new IrisObjectRotation()'),('clamp','IrisObjectLimit','new IrisObjectLimit()')]:require(text,'private '+kind+' '+field+' = '+default+';','placement.set'+field[0].upper()+field[1:]+'('+field+');')
        for field,kind in [('stiltSettings','IrisStiltSettings'),('vacuumSettings','IrisVacuumSettings')]:require(text,'private '+kind+' '+field+';','placement.set'+field[0].upper()+field[1:]+'('+field+');')
    for group,(name,fields) in GROUPS.items():
        text=texts[name];values[group+'.fields']=','.join(fields)
        for field in fields:
            match=re.search(r'(?P<annotations>(?:\s*@[^\n]+\n)+)\s*private (?P<type>int|double|boolean) '+field+r'(?: = (?P<default>[^;]+))?;',text)
            if not match:raise ValueError('Missing nested scalar '+group+'.'+field)
            t,default=match['type'],match['default']
            if default is None:
                if group!='stiltSettings' or field not in ['yMax','yRand','overStilt'] or t!='int':raise ValueError('Unknown implicit nested default')
                default='0'
            desc=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)',match['annotations'])
            if not desc:raise ValueError('Missing nested help')
            prefix=group+'.'+field+'.';values[prefix+'type']=t;values[prefix+'default']=default;values[prefix+'description']=json.loads(desc.group(1))
            if t!='boolean':
                for annotation,key in [('MinNumber','minimum'),('MaxNumber','maximum')]:
                    bound=re.search('@'+annotation+r'\(([^)]+)\)',match['annotations'])
                    if not bound:raise ValueError('Missing nested bound')
                    values[prefix+key]=bound.group(1)
    require(texts['IrisObjectRotation'],'private boolean enabled = true;','private IrisAxisRotationClamp xAxis = new IrisAxisRotationClamp();','private IrisAxisRotationClamp yAxis = new IrisAxisRotationClamp(true, false, 0, 0, 90);','private IrisAxisRotationClamp zAxis = new IrisAxisRotationClamp();')
    require(texts['IrisAxisRotationClamp'],'private boolean enabled = false;','private double interval = 0;','return min == max && min == 0;','return min == max && !isUnlimited();','double resolvedInterval = interval < 1 ? 1 : interval;','double resolvedInterval = interval <= 0 ? 1 : interval;','Math.min(min, max), Math.max(min, max)')
    require(texts['IrisObjectLimit'],'return h <= maximumHeight && l >= minimumHeight;')
    require(texts['IrisStiltSettings'],'private IrisMaterialPalette palette;','private KList<String> exclude = new KList<>();','if (exclude == null || exclude.isEmpty()) { return Set.of(); }',"materials.add(material.indexOf(':') < 0 ? \"minecraft:\" + material : material);")
    values['exclude.description']=json.loads(re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s*private KList<String> exclude',texts['IrisStiltSettings']).group(1))
    require(texts['IrisObjectPlacementRunner'],'config.getClamp().canPlace(y + rty + ty, y - rty + ty)','int yrand = config.getTranslate().getYRandom();','if (!IrisObjectShaping.shouldStilt(sourceData, excludedStiltMaterials))','int radius = IrisObjectVacuum.resolveRadius(mode, settings);','double falloff = IrisObjectVacuum.resolveFalloff(settings);')
    require(texts['IrisObjectShaping'],'return isStiltLayerBlock(state) && !state.isStorage() && !state.hasTileEntity() && !excludedMaterials.contains(materialKey(state));')
    require(texts['IrisObjectVacuum'],'case VACUUM_HIGH -> 20;','case VACUUM_FAST -> 8;','default -> 12;','if (settings != null && settings.getRadius() > 0)','return settings != null ? Math.max(0.25, settings.getFalloff()) : 2.0;')
    values.update({'axis.yAxis.creation':'{"enabled":true,"interval":90}','axis.xAxis.creation':'{}','axis.zAxis.creation':'{}','axis.inline.policy':'classDefaults','clamp.range.policy':'ordered','axis.range.policy':'equalOrReversed','optional.null.policy':'nativeFallback','required.null.policy':'explicitReplacement','execution.policy':'disabled'})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')
if __name__=='__main__':Path(sys.argv[2]).write_bytes(extract(read_sources(sys.argv[1])))
