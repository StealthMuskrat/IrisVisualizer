"""Fail on drift in the native collection, dispatch, anchor and alignment contracts."""
import sys
from pathlib import Path
from extract_biome_object_fields import extract

sources=[Path(path).read_bytes() for path in sys.argv[1:]]
result=extract(*sources).decode()
for token in ['caveAnchorMode.choices=PROFILE_DEFAULT,FLOOR,CEILING,CENTER,ANY\n','bottom.default=false\n','chance.policy=beforeDensityLoop\n','objects.enablePolicy=absentOnly\n']:assert token in result,token
mutations=[(0,b'private KList<IrisObjectPlacement> objects = new KList<>();',b'private KList<IrisObjectPlacement> objects;'),
           (0,b'!i.getCarvingSupport().supportsSurface()',b'i.getCarvingSupport().supportsSurface()'),
           (0,b'!i.getCarvingSupport().supportsCarving()',b'i.getCarvingSupport().supportsCarving()'),
           (1,b'caveAnchorMode = IrisCaveAnchorMode.PROFILE_DEFAULT',b'caveAnchorMode = IrisCaveAnchorMode.FLOOR'),
           (1,b'private boolean bottom = false;',b'private boolean bottom = true;'),
           (1,b'p.setCaveAnchorMode(caveAnchorMode);',b''),
           (2,b'rng.chance(i.getChance()) && !i.isCompatExcluded(getData())',b'rng.chance(1) && !i.isCompatExcluded(getData())'),
           (2,b'rng.chance(i.getChance() + rng.d(-0.005, 0.005))',b'rng.chance(i.getChance())'),
           (2,b'biomeSurfaceTriggered++;',b'biomeSurfaceTriggered += 2;'),
           (2,b'biomeCaveTriggered++;',b'biomeCaveTriggered += 2;'),
           (2,b'getDensity(rng, minX, minZ, getData())',b'getDensity(rng, 0, 0, getData())'),
           (2,b'v.place(xx, -1, zz, placePlacer',b'v.place(xx, 0, zz, placePlacer'),
           (2,b'effectivePlacement.setMode(ObjectPlaceMode.FAST_MIN_STILT);',b''),
           (2,b'resolvedPlacement.setMode(profileMode);',b''),
           (2,b'placement.isRequireSurfaceSupport() && dimension.isRequireObjectSurfaceSupport()',b'placement.isRequireSurfaceSupport()'),
           (2,b'anchorMode = IrisCaveAnchorMode.CEILING;',b'anchorMode = IrisCaveAnchorMode.FLOOR;'),
           (2,b'!placementMode.equals(IrisCaveAnchorMode.PROFILE_DEFAULT)',b'!placementMode.equals(IrisCaveAnchorMode.FLOOR)'),
           (3,b'    ANY',b'    RANDOM'),
           (4,b'yv >= 0 && config.isBottom()',b'config.isBottom()')]
for index,old,new in mutations:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try:extract(*changed)
    except ValueError:continue
    raise AssertionError('Accepted biome object drift: '+str(old))
print('PASS: root biome object declarations, carving filters, chance/density dispatch, contextual mode/support rules, native cave anchors and conditional bottom alignment reject drift')
