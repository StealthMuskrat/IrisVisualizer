"""Audit optional stilt/vacuum objects, bounded scalars, exclusions and native mode consumers."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact

STILT=['yMax','yRand','overStilt','organicMaxScan','organicJitter','organicScratch']
VACUUM=['radius','falloff','organicJitter','waveAmplitude','waveScale']

def extract(placement,stilt,vacuum,runner,mantle,helper,shaping):
    sources=[placement,stilt,vacuum,runner,mantle,helper,shaping]
    p,s,v,r,m,h,sh=[raw.decode('utf-8') for raw in sources]
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text): raise ValueError('Changed deformation contract: '+contract)
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for name,raw in zip(['placement','stilt','vacuum','runner','mantle','helper','shaping'],sources):values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    require(p,'@NoArgsConstructor')
    for object_name,kind,text,fields in [('stiltSettings','IrisStiltSettings',s,STILT),('vacuumSettings','IrisVacuumSettings',v,VACUUM)]:
        require(text,'@NoArgsConstructor');require(p,'private '+kind+' '+object_name+';','p.set'+object_name[0].upper()+object_name[1:]+'('+object_name+');')
        help_text=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private '+kind+' '+object_name+';',p)
        if not help_text:raise ValueError('Missing deformation object description')
        values[object_name+'.description']=json.loads(help_text.group(1))
        for field in fields:
            match=re.search(r'((?:    @[^\n]+\n)+)    private (int|double) '+field+r'(?: = ([^;]+))?;',text)
            if not match:raise ValueError('Changed deformation scalar: '+field)
            annotations,field_type,default=match.groups()
            expected_type='double' if field in (['organicScratch'] if object_name=='stiltSettings' else ['falloff','waveScale']) else 'int'
            if field_type!=expected_type:raise ValueError('Changed deformation scalar type')
            if default is None:
                if object_name!='stiltSettings' or field not in ['yMax','yRand','overStilt'] or field_type!='int':raise ValueError('Changed implicit stilt default')
                default='0'
            key=object_name+'.'+field;values[key+'.type']=field_type;values[key+'.default']=default
            description=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)',annotations)
            if not description:raise ValueError('Missing deformation scalar description')
            values[key+'.description']=json.loads(description.group(1))
            for name,annotation in [('minimum','MinNumber'),('maximum','MaxNumber')]:
                found=re.search('@'+annotation+r'\(([^)]+)\)',annotations)
                if not found:raise ValueError('Missing deformation bounds')
                values[key+'.'+name]=found.group(1)
    require(s,'private IrisMaterialPalette palette;','@ArrayType(min = 1, type = String.class)','@RegistryListBlockType','private KList<String> exclude = new KList<>();',
            'if (exclude == null || exclude.isEmpty()) { return Set.of(); }','String material = block.trim().toLowerCase(Locale.ROOT);',
            'materials.add(material.indexOf(\':\') < 0 ? "minecraft:" + material : material);')
    values['stiltSettings.exclude.default']='[]';values['stiltSettings.exclude.minimum']='1'
    values['stiltSettings.exclude.description']=json.loads(re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private KList<String> exclude',s).group(1))
    require(sh,'return isStiltLayerBlock(state) && !state.isStorage() && !state.hasTileEntity() && !excludedMaterials.contains(materialKey(state));')
    require(r,'boolean organicFloor = config.getMode() == ObjectPlaceMode.ORGANIC_STILT;','boolean ceilingHang = config.getMode() == ObjectPlaceMode.CEILING_HANG;','boolean organic = organicFloor || ceilingHang;',
            'boolean vacuuming = IrisObjectVacuum.isVacuumMode(config.getMode());',
            'if (!IrisObjectShaping.shouldStilt(sourceData, excludedStiltMaterials))',
            'if (d == null || !IrisObjectShaping.shouldStilt(d, excludedStiltMaterials))',
            'int maxScan = settings != null ? Math.max(1, settings.getOrganicMaxScan()) : 48;',
            'int jitterMax = settings != null ? Math.max(0, settings.getOrganicJitter()) : 3;',
            'double scratch = settings != null ? Math.max(0, Math.min(1, settings.getOrganicScratch())) : 0.55;',
            'lowerBound -= config.getStiltSettings().getOverStilt() - rng.i(0, config.getStiltSettings().getYRand());',
            'if (settings.getYMax() != 0) lowerBound -= Math.min(config.getStiltSettings().getYMax() - (lowest + y - highest), 0);',
            'if (vacuuming && vacuumLowest != Integer.MAX_VALUE && placer.getEngine() != null)',
            'int radius = IrisObjectVacuum.resolveRadius(mode, settings);','double falloff = IrisObjectVacuum.resolveFalloff(settings);',
            'boolean organicEdge = mode == ObjectPlaceMode.VACUUM_ORGANIC;','boolean wavyEdge = mode == ObjectPlaceMode.VACUUM_WAVY;',
            'double sample = waveNoise.noiseSigned(cx * waveScale, cz * waveScale);','double waveAmplitude = IrisObjectVacuum.resolveWaveAmplitude(settings);','double waveScale = IrisObjectVacuum.resolveWaveScale(settings);')
    require(h,'case VACUUM_HIGH -> 20;','case VACUUM_FAST -> 8;','default -> 12;',
            'if (settings != null && settings.getRadius() > 0) { radius = settings.getRadius(); }',
            'return mode == ObjectPlaceMode.VACUUM_FAST ? 2 : 1;',
            'return settings != null ? Math.max(0.25, settings.getFalloff()) : 2.0;',
            'private static final double DEFAULT_WAVE_AMPLITUDE = 3.0;','private static final double DEFAULT_WAVE_SCALE = 5.0;')
    remap=re.search(r'private static ObjectPlaceMode translateStiltModeForFloating\(.*?\n    \}',m,re.DOTALL)
    if not remap:raise ValueError('Missing anchored mode translation')
    expected={'STILT':'MAX_HEIGHT','FAST_STILT':'FAST_MAX_HEIGHT','MIN_STILT':'MIN_HEIGHT','FAST_MIN_STILT':'FAST_MIN_HEIGHT','CENTER_STILT':'CENTER_HEIGHT','ERODE_STILT':'MAX_HEIGHT','STRUCTURE_PIECE':'CENTER_HEIGHT'}
    actual=dict(re.findall(r'case (\w+) -> ObjectPlaceMode\.(\w+);',remap.group()))
    if actual!=expected or 'default -> m;' not in remap.group():raise ValueError('Changed anchored remapped modes')
    if re.search(r'(floatingPlacement|anchored|inverted)\s*\.\s*set(?:StiltSettings|VacuumSettings)\s*\(',m):raise ValueError('Changed preserved deformation settings')
    values.update({'object.policy':'nullFallback','classic.context':'floatingObjectsOnly','exclude.policy':'materialKeysNullDisable'})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__': Path(sys.argv[8]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:8])))
