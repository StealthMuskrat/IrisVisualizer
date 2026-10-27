"""Reject drift in region collection ownership, pass dispatch and cave fallback."""
import sys
from pathlib import Path
from extract_region_object_fields import extract

sources=[Path(path).read_bytes() for path in sys.argv[1:]]
result=extract(*sources).decode()
for token in ['objects.description=Objects define what schematics (iob files) iris will place in this region\n','chance.policy=beforeDensityLoop\n','caveProfile.policy=regionThenCaveBiomeThenDimension\n','anchor.biomeConstraint=none\n']:assert token in result,token
mutations=[(0,b'private KList<IrisObjectPlacement> objects = new KList<>();',b'private KList<IrisObjectPlacement> objects;'),
           (0,b'!i.getCarvingSupport().supportsSurface()',b'i.getCarvingSupport().supportsSurface()'),
           (0,b'!i.getCarvingSupport().supportsCarving()',b'i.getCarvingSupport().supportsCarving()'),
           (3,b'regionSurfaceChecked++;',b'regionSurfaceChecked += 2;'),
           (3,b'regionCaveChecked++;',b'regionCaveChecked += 2;'),
           (3,b'regionSurfaceTriggered++;',b'regionSurfaceTriggered += 2;'),
           (3,b'regionCaveTriggered++;',b'regionCaveTriggered += 2;'),
           (3,b'"region-cave", null, caveAnchorCache',b'"region-cave", "changed", caveAnchorCache'),
           (3,b'resolveCaveProfile(region.getCaveProfile(), caveBiome.getCaveProfile())',b'resolveCaveProfile(caveBiome.getCaveProfile(), region.getCaveProfile())'),
           (3,b'if (expectedCaveBiomeKey == null) {\n            return false;',b'if (expectedCaveBiomeKey == null) {\n            return true;'),
           (3,b'preferred != null && preferred.isEnabled()',b'preferred != null'),
           (3,b'secondary != null && secondary.isEnabled()',b'secondary != null'),
           (3,b'if (dimensionProfile != null) {\n            return dimensionProfile;',b'if (dimensionProfile != null) {\n            return preferred;'),
           (2,b'caveAnchorMode = IrisCaveAnchorMode.PROFILE_DEFAULT',b'caveAnchorMode = IrisCaveAnchorMode.FLOOR'),
           (5,b'yv >= 0 && config.isBottom()',b'config.isBottom()')]
for index,old,new in mutations:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try:extract(*changed)
    except ValueError:continue
    raise AssertionError('Accepted region object drift: '+str(old))
print('PASS: region object declaration/carving filters, chance-before-density dispatch, shared placement controls, cave profile fallback and unrestricted cave-biome anchors reject drift')
