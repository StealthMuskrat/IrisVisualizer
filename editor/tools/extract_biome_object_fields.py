"""Bind root biome placement editing to the pinned surface/cave consumers."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact

def extract(biome, placement, mantle, anchor, runner):
    sources=[biome,placement,mantle,anchor,runner]
    b,p,m,a,r=[raw.decode('utf-8') for raw in sources]
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text):raise ValueError('Changed biome object contract: '+contract)
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for name,raw in zip(['biome','placement','mantle','anchor','runner'],sources):values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    declaration=re.search(r'@ArrayType\(min = 1, type = IrisObjectPlacement.class\)\s+@Description\(("(?:[^"\\]|\\.)*")\)\s+private KList<IrisObjectPlacement> objects = new KList<>\(\);',b)
    if not declaration:raise ValueError('Changed root biome object declaration')
    values['objects.description']=json.loads(declaration.group(1))
    require(b,'KList<IrisObjectPlacement> o = getObjects().copy();','if (!i.getCarvingSupport().supportsSurface()) { o.remove(i); }','if (!i.getCarvingSupport().supportsCarving()) { o.remove(i); }')
    require(m,'for (IrisObjectPlacement i : surfaceBiome.getSurfaceObjects()) { biomeSurfaceChecked++; boolean chance = rng.chance(i.getChance() + rng.d(-0.005, 0.005)) && !i.isCompatExcluded(getData());',
            'for (IrisObjectPlacement i : caveBiome.getCarvingObjects()) { if (!i.getCarvingSupport().supportsCarving()) { continue; } biomeCaveChecked++; boolean chance = rng.chance(i.getChance()) && !i.isCompatExcluded(getData());',
            'if (chance) { biomeSurfaceTriggered++; try { ObjectPlacementResult result = placeObject(',
            'if (chance) { biomeCaveTriggered++; try { ObjectPlacementResult result = placeCaveObject(',
            'int density = objectPlacement.getDensity(rng, x, z, getData());','int density = objectPlacement.getDensity(rng, minX, minZ, getData());',
            'int result = v.place(xx, -1, zz, placePlacer, effectivePlacement, rng,',
            'return applyDimensionSurfaceSupport(resolveImportedPlacement(objectPlacement, object));',
            'effectivePlacement.setMode(ObjectPlaceMode.FAST_MIN_STILT);','resolvedPlacement.setMode(profileMode);',
            'boolean require = placement.isRequireSurfaceSupport() && dimension.isRequireObjectSurfaceSupport();',
            'int buffer = Math.max(placement.getSurfaceSupportBuffer(), dimension.getObjectSurfaceSupportBuffer());',
            'IrisCaveAnchorMode anchorMode = resolveAnchorMode(objectPlacement, caveProfile); if (objectPlacement.getMode() == ObjectPlaceMode.CEILING_HANG) { anchorMode = IrisCaveAnchorMode.CEILING; }',
            'IrisCaveAnchorMode placementMode = objectPlacement.getCaveAnchorMode(); if (placementMode != null && !placementMode.equals(IrisCaveAnchorMode.PROFILE_DEFAULT)) { return placementMode; }',
            'IrisCaveAnchorMode profileMode = caveProfile.getDefaultObjectAnchor(); if (profileMode == null || profileMode.equals(IrisCaveAnchorMode.PROFILE_DEFAULT)) { return IrisCaveAnchorMode.FLOOR; } return profileMode;')
    require(p,'private IrisCaveAnchorMode caveAnchorMode = IrisCaveAnchorMode.PROFILE_DEFAULT;','private boolean bottom = false;','p.setBottom(bottom);','p.setCaveAnchorMode(caveAnchorMode);')
    require(r,'if (yv >= 0 && config.isBottom() && !rawStructurePiece && alignment != IrisObjectPlacementAlignment.FLOOR)')
    choices=re.findall(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+(PROFILE_DEFAULT|FLOOR|CEILING|CENTER|ANY)\s*[,;\n]',a)
    if [choice for _,choice in choices]!=['PROFILE_DEFAULT','FLOOR','CEILING','CENTER','ANY']:raise ValueError('Changed cave anchor choices')
    values['caveAnchorMode.choices']=','.join(choice for _,choice in choices)
    for description,choice in choices:values['caveAnchorMode.'+choice+'.description']=json.loads(description)
    values.update({'caveAnchorMode.default':'PROFILE_DEFAULT','bottom.default':'false','chance.policy':'beforeDensityLoop','objects.enablePolicy':'absentOnly'})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__':Path(sys.argv[6]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:6])))
