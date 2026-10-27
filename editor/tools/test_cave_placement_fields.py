"""Reject drift in range constructors, contextual defaults and exact enum declarations."""
from extract_cave_placement_fields import extract

profile = b'''    @Description("Vertical bounds in engine-local Y")
    private IrisRange verticalRange = new IrisRange(0, 384);
    @Description("Anchor")
    private IrisCaveAnchorMode defaultObjectAnchor = IrisCaveAnchorMode.FLOOR;
    @Description("Placement")
    private ObjectPlaceMode defaultObjectPlaceMode = null;
'''
range_source = b'''@NoArgsConstructor @AllArgsConstructor
public class IrisRange {
    @Description("Minimum")
    private double min = 16;
    @Description("Maximum")
    private double max = 32;
}'''
anchors = b'''public enum IrisCaveAnchorMode {
    @Description("Default") PROFILE_DEFAULT,
    @Description("Floor") FLOOR,
    @Description("Ceiling") CEILING
}'''
placements = b'''public enum ObjectPlaceMode {
    @Description("Center") CENTER_HEIGHT,
    @Description("Stilt") FAST_MIN_STILT
}'''
data = extract(profile,range_source,anchors,placements).decode('ascii')
for item in ['range.min.default=16\n','range.max.default=32\n','verticalRange.min.default=0\n','verticalRange.max.default=384\n',
             'defaultObjectAnchor.default=FLOOR\n','defaultObjectPlaceMode.default=null\n',
             'defaultObjectAnchor.choices=PROFILE_DEFAULT,FLOOR,CEILING\n','defaultObjectPlaceMode.choices=CENTER_HEIGHT,FAST_MIN_STILT\n']:
    assert item in data


def rejects(p=profile,r=range_source,a=anchors,m=placements):
    try:
        extract(p,r,a,m)
    except ValueError:
        return
    raise AssertionError('Changed cave bounds/placement declaration silently accepted')


rejects(r=range_source.replace(b'@AllArgsConstructor',b''))
rejects(r=range_source.replace(b'private double min',b'private int min'))
rejects(r=range_source.replace(b'min = 16',b'max = 16').replace(b'max = 32',b'min = 32'))
rejects(r=range_source.replace(b'@Description("Minimum")',b'@MinNumber(0)\n    @Description("Minimum")'))
rejects(p=profile.replace(b'new IrisRange(0, 384)',b'new IrisRange()'))
rejects(p=profile.replace(b'IrisCaveAnchorMode.FLOOR',b'IrisCaveAnchorMode.FUTURE'))
rejects(p=profile.replace(b'= null;',b'= ObjectPlaceMode.FUTURE;'))
rejects(a=anchors.replace(b'CEILING',b'FLOOR'))
rejects(a=anchors.replace(b'@Description("Ceiling")',b''))
rejects(m=placements.replace(b'FAST_MIN_STILT',b'FAST_MIN_STILT(1)'))
rejects(m=placements.replace(b'\n}',b';\n}'))
print('PASS: cave range constructors, contextual defaults and placement enum boundaries reject drift')
