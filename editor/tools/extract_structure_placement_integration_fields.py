"""Pin placement-specific terrain/backend restrictions and foundation/palette defaults."""
import hashlib
import json
import sys
from pathlib import Path
from extract_river_material_fields import compact

def extract(*sources):
    p,t,s,v,n=[raw.decode('utf-8') for raw in sources]
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for name,raw in zip(['placement','terrain','stilt','validator','nativePlanner'],sources):values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text):raise ValueError('Changed placement integration contract: '+contract)
    require(p,'private IrisStructureTerrain terrain = new IrisStructureTerrain();','private IrisStructureStiltSettings stilt = null;',
            'public IrisStructureTerrain resolvedTerrain() { return terrain == null ? new IrisStructureTerrain() : terrain; }')
    require(t,'private StructureTerrainMode mode = StructureTerrainMode.SOURCE;','private StructureCarveShape shape = StructureCarveShape.BOX;',
            'private IrisMaterialPalette encasePalette = null;','return mode == null ? StructureTerrainMode.SOURCE : mode;')
    require(s,'private int maxDepth = 64;','private int spacing = 1;','private boolean supportNonOccluding = false;',
            'private IrisMaterialPalette palette = new IrisMaterialPalette().qclear().qadd("minecraft:cobblestone");')
    require(v,'validateEditableStructureTerrain(placementPath, placement, blockingErrors);',
            'if ("VACUUM".equals(mode) || "FLATTEN".equals(mode) || "ENCASE".equals(mode))',
            'String mode = terrain.optString("mode", "SOURCE");',
            'path + ".stilt", stilt, "maxDepth", 1, 4064, blockingErrors',
            'path + ".stilt", stilt, "spacing", 1, 64, blockingErrors',
            'path + ".stilt", stilt, "supportNonOccluding", blockingErrors',
            'Set.of("SOURCE", "PRESERVE", "BORE", "FORCE_CARVE", "VACUUM", "FLATTEN", "ENCASE")',
            'if (placement.has("terrain") && placement.opt("terrain") != JSONObject.NULL && terrain == null)',
            'if (!placement.has("stilt") || placement.opt("stilt") == JSONObject.NULL) { return; }')
    require(n,'placement.getStilt(), placement.resolvedTerrain()')
    values.update({'iris.modes':'SOURCE,PRESERVE,BORE,FORCE_CARVE','terrain.absentOrNull':'defaultSource','stilt.absentOrNull':'noFoundation','palette.scope':'hostAndPlacementOwner','runtime.policy':'notEvaluated'})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__':Path(sys.argv[6]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:6])))
