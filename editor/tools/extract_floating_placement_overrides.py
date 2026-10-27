"""Bind virtual height queries and current native force aliases to the pinned consumers."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact

def extract(placement,noise,placer,runner,mantle):
    sources=[placement,noise,placer,runner,mantle]
    p,n,h,r,m=[raw.decode('utf-8') for raw in sources]
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text):raise ValueError('Changed placement override contract: '+contract)
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for name,raw in zip(['placement','noise','placer','runner','mantle'],sources):values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    require(p,'@NoArgsConstructor','private IrisNoiseGenerator heightmap;','p.setHeightmap(heightmap);','p.setForcePlace(forcePlace);')
    description=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private IrisNoiseGenerator heightmap;',p)
    if not description:raise ValueError('Changed heightmap declaration')
    values['heightmap.description']=json.loads(description.group(1))
    force=re.search(r'@SerializedName\(value = "(forcePlace)", alternate = \{"(force)"\}\)\s+private boolean forcePlace = (false);',p)
    if not force:raise ValueError('Changed native force serialization')
    values['force.keys']=force.group(1)+','+force.group(2);values['force.default']=force.group(3)
    require(n,'@NoArgsConstructor','private IrisGeneratorStyle style = NoiseStyle.IRIS.style();','if (!enabled) { return offsetY; }')
    require(r,'IObjectPlacer placer = config.getHeightmap() != null ? new HeightmapObjectPlacer(rng, x, yv, z, config, oplacer) : oplacer;',
            'boolean evaluateSlopeCondition = !config.isForcePlace() && !config.getSlopeCondition().isDefault();',
            'if (!config.isForcePlace() && !rawStructurePiece && !config.getClamp().canPlace(y + rty + ty, y - rty + ty))',
            'if (!rawStructurePiece && nativeStructureVetoes(placer, config, spin, placementBounds, translating, translateOffset, ceilingHang,')
    require(h,'s = rng.nextLong() + yv + z - x;','return oplacer.getEngine();','return oplacer.isUnderwater(param1Int1, param1Int2);')
    sample='return (int) Math.round(config.getHeightmap().getNoise(this.s, param1Int1, param1Int2, data));'
    if compact(h).count(compact(sample))!=2:raise ValueError('Changed virtual normal/underwater height queries')
    require(m,'IrisObjectPlacement floatingPlacement = placement.toPlacement(obj.getLoadKey());','anchored.setForcePlace(true);','inverted.setForcePlace(true);')
    if re.search(r'floatingPlacement\s*\.\s*setForcePlace',m) or re.search(r'(floatingPlacement|anchored|inverted)\s*\.\s*setHeightmap',m):raise ValueError('Changed floating override preservation')
    values.update({'force.policy':'singleStoredBoolean','heightmap.policy':'nullUnderlyingQueries','noise.styleDefault':'IRIS'})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__':Path(sys.argv[6]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:6])))
