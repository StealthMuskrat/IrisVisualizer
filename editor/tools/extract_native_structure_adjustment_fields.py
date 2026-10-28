"""Audit native structure adjustment authoring, without running generation or terrain integration."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact

def extract(*sources):
    d,c,a,s,t,b,v,w,m,h,j=[raw.decode('utf-8') for raw in sources]
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f','volmLibRevision':'210705c5ab1321dc74b86b48457fd6d5e0452944'}
    for name,raw in zip(['dimension','control','adjustment','stilt','terrain','band','placementValidator','dimensionValidator','mode','shape','fieldChecks'],sources):values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text):raise ValueError('Changed native adjustment contract: '+contract)
    constants={'AUTO_LOBE_FREQUENCY_RATIO':'0.3D','DEFAULT_EROSION_STRENGTH':'0.8D','DEFAULT_EROSION_FREQUENCY':'0.07D','DEFAULT_LOBE_STRENGTH':'0.85D','MIN_EROSION_FREQUENCY':'0.001D','MAX_EROSION_FREQUENCY':'1D','MAX_LOBE_FREQUENCY':'1D'}
    for name,default in constants.items():require(t,'private static final double '+name+' = '+default+';')
    def literal(raw):return constants.get(raw,raw).removesuffix('D')
    groups=[(a,'',{'match':('KList<String>','new KList<>()'),'yShift':('int','0'),'yBand':('IrisStructureYBand','null'),'preserveSourceY':('boolean','false'),'stilt':('IrisStructureStiltSettings','null'),'terrain':('IrisStructureTerrain','null')}),
            (s,'stilt.',{'maxDepth':('int','64'),'palette':('IrisMaterialPalette','new IrisMaterialPalette().qclear().qadd("minecraft:cobblestone")'),'spacing':('int','1'),'supportNonOccluding':('boolean','false')}),
            (t,'terrain.',{'mode':('StructureTerrainMode','StructureTerrainMode.SOURCE'),'horizontalPadding':('int','0'),'flattenRange':('int','64'),'ceilingPadding':('int','0'),'floorPadding':('int','0'),'shape':('StructureCarveShape','StructureCarveShape.BOX'),'encasePalette':('IrisMaterialPalette','null'),'erosionStrength':('double','DEFAULT_EROSION_STRENGTH'),'erosionFrequency':('double','DEFAULT_EROSION_FREQUENCY'),'lobeFrequency':('double','0D'),'lobeStrength':('double','DEFAULT_LOBE_STRENGTH')}),
            (b,'yBand.',{'min':('int','0'),'max':('int','0')})]
    for text,prefix,fields in groups:
        for field,(kind,default) in fields.items():
            match=re.search(r'(?P<annotations>(?:\s*@[^\n]+\n)+)\s*private '+re.escape(kind)+' '+field+r' = (?P<default>[^;]+);',text)
            if not match or match['default']!=default:raise ValueError('Changed adjustment declaration '+prefix+field)
            description=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)',match['annotations'])
            if not description:raise ValueError('Missing adjustment help')
            key=prefix+field;values[key+'.description']=json.loads(description.group(1));values[key+'.type']='enum' if kind.startswith('Structure') else kind
            values[key+'.default']=default.split('.')[-1] if kind.startswith('Structure') else literal(default)
            for annotation,label in [('MinNumber','minimum'),('MaxNumber','maximum')]:
                bound=re.search('@'+annotation+r'\(([^)]+)\)',match['annotations'])
                if bound:values[key+'.'+label]=literal(bound.group(1))
    expected={'yShift':('-512','512'),'stilt.maxDepth':('1','4064'),'stilt.spacing':('1','64'),'terrain.horizontalPadding':('0','128'),'terrain.flattenRange':('0','128'),'terrain.ceilingPadding':('0','128'),'terrain.floorPadding':('0','64'),'terrain.erosionStrength':('0','1'),'terrain.erosionFrequency':('0.001','1'),'terrain.lobeFrequency':('0','1'),'terrain.lobeStrength':('0','1'),'yBand.min':('-4064','4064'),'yBand.max':('-4064','4064')}
    for field,(low,high) in expected.items():
        if (values.get(field+'.minimum'),values.get(field+'.maximum'))!=(low,high):raise ValueError('Changed adjustment bounds '+field)
    require(d,'private IrisImportedStructureControl importedStructures = new IrisImportedStructureControl();')
    require(c,'private KList<IrisVanillaStructureAdjustment> adjustments = new KList<>();','int y = undergroundStep ? undergroundYShift : 0;',
            'y += adjustment.getYShift();','preserveSourceY |= adjustment.isPreserveSourceY();',
            'if (adjustment.getStilt() != null) { stilt = adjustment.getStilt(); }','if (adjustment.getTerrain() != null) { terrain = adjustment.getTerrain(); }','if (adjustment.getYBand() != null) { yBand = adjustment.getYBand(); }',
            'return new IrisNativeStructureDecision( generationStatus(key), y, yBand, preserveSourceY, stilt, terrain);',"return boundary == '/' || boundary == '_';")
    require(a,'if (IrisImportedStructureControl.matchesKey(entry, key)) { return true; }')
    require(b,'return Math.min(min, max);','return Math.max(min, max);')
    require(t,'return mode == null ? StructureTerrainMode.SOURCE : mode;', 'return shape == null ? StructureCarveShape.BOX : shape;',
            'return Math.max(0, Math.min(128, flattenRange));','return resolvedErosionFrequency() * AUTO_LOBE_FREQUENCY_RATIO;',
            'return Math.max(MIN_EROSION_FREQUENCY, Math.min(MAX_EROSION_FREQUENCY, erosionFrequency));')
    for text,field,names in [(m,'mode',['SOURCE','PRESERVE','BORE','FORCE_CARVE','VACUUM','FLATTEN','ENCASE']), (h,'shape',['BOX','ROUNDED','ERODED'])]:
        items=re.findall(r'@Description\(("(?:[^"\\]|\\.)*")\)\s*([A-Z_]+)\s*[,;}]',text)
        if [name for _,name in items]!=names:raise ValueError('Changed terrain enum '+field)
        values['terrain.'+field+'.choices']=','.join(names)
        for description,name in items:values['terrain.'+field+'.'+name+'.description']=json.loads(description)
    require(w,'PackJsonFieldChecks.validateOptionalIntegerRange(path, band, "min", -4064, 4064, blockingErrors);','PackJsonFieldChecks.validateOptionalIntegerRange(path, band, "max", -4064, 4064, blockingErrors);')
    require(v,'Set.of("SOURCE", "PRESERVE", "BORE", "FORCE_CARVE", "VACUUM", "FLATTEN", "ENCASE")','Set.of("BOX", "ROUNDED", "ERODED")',
            '"erosionFrequency", 0.001D, 1D, blockingErrors','"floorPadding", 0, 64, blockingErrors')
    require(j,'if (!(rawValue instanceof String value) || !values.contains(value))')
    values.update({'resolution.policy':'stackShiftsLastNonNullOverrides','band.policy':'sortedEndpoints','palettes.policy':'readOnly','registry.policy':'syntaxOnly'})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__':Path(sys.argv[12]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:12])))
