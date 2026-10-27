"""Extend the common object contracts with pinned region dispatch and cave-profile selection."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_biome_object_fields import extract as common
from extract_river_material_fields import compact

def extract(region,biome,placement,mantle,anchor,runner):
    shared=common(biome,placement,mantle,anchor,runner).decode('ascii')
    r,m=[raw.decode('utf-8') for raw in [region,mantle]]
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text):raise ValueError('Changed region object contract: '+contract)
    declaration=re.search(r'@ArrayType\(min = 1, type = IrisObjectPlacement.class\)\s+@Description\(("(?:[^"\\]|\\.)*")\)\s+private KList<IrisObjectPlacement> objects = new KList<>\(\);',r)
    if not declaration:raise ValueError('Changed region object declaration')
    require(r,'KList<IrisObjectPlacement> o = getObjects().copy();','if (!i.getCarvingSupport().supportsSurface()) { o.remove(i); }','if (!i.getCarvingSupport().supportsCarving()) { o.remove(i); }')
    require(m,'for (IrisObjectPlacement i : region.getSurfaceObjects()) { regionSurfaceChecked++; boolean chance = rng.chance(i.getChance() + rng.d(-0.005, 0.005)) && !i.isCompatExcluded(getData());',
            'for (IrisObjectPlacement i : region.getCarvingObjects()) { if (!i.getCarvingSupport().supportsCarving()) { continue; } regionCaveChecked++; boolean chance = rng.chance(i.getChance()) && !i.isCompatExcluded(getData());',
            'if (chance) { regionSurfaceTriggered++; try { ObjectPlacementResult result = placeObject(writer, rng, x << 4, z << 4, i, complex, traceRegen, x, z, "region-surface");',
            'if (chance) { regionCaveTriggered++; try { ObjectPlacementResult result = placeCaveObject(writer, rng, x, z, i, regionCaveProfile, complex, traceRegen, x, z, "region-cave", null, caveAnchorCache);',
            'IrisCaveProfile regionCaveProfile = resolveCaveProfile(region.getCaveProfile(), caveBiome.getCaveProfile());',
            'if (expectedCaveBiomeKey == null) { return false; }',
            'IrisCaveProfile dimensionProfile = getDimension().getCaveProfile(); if (preferred != null && preferred.isEnabled()) { return preferred; } if (secondary != null && secondary.isEnabled()) { return secondary; } if (dimensionProfile != null) { return dimensionProfile; } return new IrisCaveProfile();')
    shared=re.sub(r'^objects.description=.*$',lambda ignored:'objects.description='+json.dumps(json.loads(declaration.group(1)),ensure_ascii=True)[1:-1],shared,flags=re.MULTILINE)
    return (shared+'regionSourceSha256='+hashlib.sha256(region).hexdigest()+'\ncaveProfile.policy=regionThenCaveBiomeThenDimension\nanchor.biomeConstraint=none\n').encode('ascii')

if __name__=='__main__':Path(sys.argv[7]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:7])))
