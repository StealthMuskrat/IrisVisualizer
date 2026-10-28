"""Pin optional jigsaw data, validator inheritance rules and native start dispatch."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact

def extract(*sources):
    j,h,l,v,f,n=[raw.decode('utf-8') for raw in sources]
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for name,raw in zip(['configuration','heightmap','liquids','validator','fieldChecks','startPlan'],sources):values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text):raise ValueError('Changed native jigsaw contract: '+contract)
    declarations={'startPool':('String','""'),'startJigsawName':('String','""'),'maxDepth':('Integer','null'),'maxDistanceHorizontal':('Integer','null'),'maxDistanceVertical':('Integer','null'),'useExpansionHack':('Boolean','null'),'projectStartToHeightmap':('IrisJigsawHeightmap','IrisJigsawHeightmap.SOURCE'),'dimensionPaddingBottom':('Integer','null'),'dimensionPaddingTop':('Integer','null'),'liquidSettings':('IrisJigsawLiquidSettings','IrisJigsawLiquidSettings.SOURCE')}
    for field,(kind,default) in declarations.items():
        match=re.search(r'(?P<annotations>(?:\s*@[^\n]+\n)+)\s*private '+kind+' '+field+r' = (?P<default>[^;]+);',j)
        if not match or match['default']!=default:raise ValueError('Changed jigsaw declaration '+field)
        help=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)',match['annotations'])
        if not help:raise ValueError('Missing jigsaw help '+field)
        values[field+'.description']=json.loads(help.group(1));values[field+'.default']='' if kind=='String' else default.split('.')[-1]
    bounds={'maxDepth':(0,20),'maxDistanceHorizontal':(1,128),'maxDistanceVertical':(1,4064),'dimensionPaddingBottom':(0,2147483647),'dimensionPaddingTop':(0,2147483647)}
    for field,(low,high) in bounds.items():
        maximum='Integer.MAX_VALUE' if high==2147483647 else str(high)
        require(v,'PackJsonFieldChecks.validateOptionalIntegerRange(path, assembly, "'+field+'", '+str(low)+', '+maximum+', blockingErrors);')
        annotations=re.search(r'(?P<annotations>(?:\s*@[^\n]+\n)+)\s*private Integer '+field+r' = null;',j)['annotations']
        require(annotations,'@MinNumber('+str(low)+')','@MaxNumber('+maximum+')')
        values[field+'.minimum']=str(low);values[field+'.maximum']=str(high)
    for text,field,names in [(h,'projectStartToHeightmap',['SOURCE','NONE','WORLD_SURFACE_WG','WORLD_SURFACE','OCEAN_FLOOR_WG','OCEAN_FLOOR','MOTION_BLOCKING','MOTION_BLOCKING_NO_LEAVES']),(l,'liquidSettings',['SOURCE','IGNORE_WATERLOGGING','APPLY_WATERLOGGING'])]:
        items=re.findall(r'@Description\(("(?:[^"\\]|\\.)*")\)\s*([A-Z_]+)\s*[,;]',text)
        if [name for _,name in items]!=names:raise ValueError('Changed jigsaw choices '+field)
        values[field+'.choices']=','.join(names)
        for description,name in items:values[field+'.'+name+'.description']=json.loads(description)
        require(text,'return this == SOURCE;')
        require(v,'PackJsonFieldChecks.validateOptionalEnum(path, assembly, "'+field+'", Set.of('+', '.join('"'+name+'"' for name in names)+'), blockingErrors);')
    require(h,'return this == NONE;')
    require(j,'return (startPool != null && !startPool.isBlank()) || (startJigsawName != null && !startJigsawName.isBlank()) || maxDepth != null || maxDistanceHorizontal != null || maxDistanceVertical != null || useExpansionHack != null || (projectStartToHeightmap != null && projectStartToHeightmap != IrisJigsawHeightmap.SOURCE) || dimensionPaddingBottom != null || dimensionPaddingTop != null || (liquidSettings != null && liquidSettings != IrisJigsawLiquidSettings.SOURCE);')
    require(v,'PackJsonFieldChecks.validateOptionalResourceKey(path, assembly, "startPool", false, blockingErrors);',
            'PackJsonFieldChecks.validateOptionalResourceKey(path, assembly, "startJigsawName", true, blockingErrors);',
            'if (assembly.has("useExpansionHack") && !(assembly.opt("useExpansionHack") instanceof Boolean))',
            'if (source.has("jigsaw") && source.opt("jigsaw") != JSONObject.NULL && jigsaw == null)',
            'long assemblySpan = Math.max(maximumDistance, startElementSpan);','if (assemblySpan > 128L)')
    require(f,'if (!object.has(field) || object.opt(field) == JSONObject.NULL) { return; }',
            'if (normalized.isEmpty() || allowNone && "NONE".equalsIgnoreCase(normalized))',
            'if (!(rawValue instanceof String value) || !values.contains(value))')
    # Check the integer method specifically: enum/resource nulls must not accidentally inherit.
    integers=f[f.index('static void validateOptionalIntegerRange'):f.index('static void validateOptionalDoubleRange')]
    require(integers,'if (!object.has(field) || object.opt(field) == JSONObject.NULL) { return; }')
    require(f,'if (!object.has(field)) { return; } Object rawValue = object.opt(field); if (!(rawValue instanceof String value))')
    require(n,'public JigsawSettings jigsaw() { return source.getJigsaw(); }')
    values.update({'reset.policy':'removeField','integer.null':'inherit','other.null':'repairOrRemove','registry.policy':'unverified','execution.policy':'disabled'})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__':Path(sys.argv[7]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:7])))
