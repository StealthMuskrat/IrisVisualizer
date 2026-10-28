"""Pin native foundation/encasing palette defaults and shared weighted palette contracts."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact

def extract(*sources):
    s,t,p,b,a,c,u,v=[raw.decode('utf-8') for raw in sources]
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f','volmLibRevision':'210705c5ab1321dc74b86b48457fd6d5e0452944'}
    for name,raw in zip(['stilt','terrain','palette','block','adjustment','control','stiltApi','terrainApi'],sources):values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text):raise ValueError('Changed native structure palette contract: '+contract)
    require(s,'public class IrisStructureStiltSettings implements StructureStiltSettings','private IrisMaterialPalette palette = new IrisMaterialPalette().qclear().qadd("minecraft:cobblestone");')
    require(t,'public class IrisStructureTerrain implements StructureTerrainSettings','private IrisMaterialPalette encasePalette = null;')
    require(a,'private IrisStructureStiltSettings stilt = null;','private IrisStructureTerrain terrain = null;')
    require(c,'if (adjustment.getStilt() != null) { stilt = adjustment.getStilt(); }','if (adjustment.getTerrain() != null) { terrain = adjustment.getTerrain(); }')
    require(u,'StructurePalette getPalette();');require(v,'StructurePalette getEncasePalette();')
    require(p,'public class IrisMaterialPalette implements StructurePalette','private IrisGeneratorStyle style = NoiseStyle.STATIC.style();','private double zoom = 5;',
            'private KList<IrisBlockData> palette = new KList<IrisBlockData>().qadd(new IrisBlockData("STONE"));',
            '@Required @ArrayType(min = 1, type = IrisBlockData.class)',
            'public IrisMaterialPalette qclear() { palette.clear(); return this; }','public IrisMaterialPalette qadd(String b) { palette.add(new IrisBlockData(b)); return this; }',
            'if (blockDataSize == 0) { return null; }','double scaledX = x / zoom;','double scaledY = y / zoom;','double scaledZ = z / zoom;',
            'return getLayerGenerator(rng, rdata).fit(localBlockData, scaledX, scaledY, scaledZ);',
            'NativeBlockState bx = ix.getBlockData(rdata);','for (int i = 0; i < ix.getWeight(); i++) { blockData.add(bx); }')
    zoom=re.search(r'@MinNumber\(([^)]+)\)\s*@Description\("The terrain zoom[^\n]*\)\s*private double zoom = 5;',p)
    weight=re.search(r'@MinNumber\((\d+)\)\s*@MaxNumber\((\d+)\)\s*@Description\("[^\n]*"\)\s*private int weight = (\d+);',b)
    if not zoom or zoom.group(1)!='0.0001' or not weight or weight.groups()!=('1','1000','1'):raise ValueError('Changed palette zoom/weight bounds')
    values.update({'stilt.absent':'minecraft:cobblestone','terrain.absent':'environmentFallback','palette.classBlock':'STONE','palette.classStyle':'STATIC','palette.classZoom':'5','palette.zoomMinimum':'0.0001','palette.minimum':'1','weight.minimum':'1','weight.maximum':'1000','weight.default':'1','creation.policy':'explicitEntriesPreserveOwnerDefaults','registry.policy':'unverified'})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__':Path(sys.argv[9]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:9])))
