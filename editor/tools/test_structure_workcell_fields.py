"""Reject contextual defaults, identity and active/disabled native bound drift."""
import sys
from extract_structure_workcell_fields import extract, read_sources

sources = read_sources(sys.argv[1])
result = extract(*sources).decode('ascii')
for token in ['legacy.x.default=15\n', 'position.x.default=0\n', 'native.DEFAULT_SIZE=16\n',
              'width.minimum=3\n', 'height.maximum=192\n', 'native.MAX_VOLUME=2097152\n',
              'archetypes=BLANK,END,STRAIGHT,CORNER,TEE,CROSS\n', 'enabled.default=true\n']:
    assert token in result, token
for index, old, new in [
    (0, b'new IrisPosition(15, 15, 15)', b'new IrisPosition(16, 16, 16)'),
    (0, b'private KList<IrisJigsawWorkcell> planarWorkcells = new KList<>();', b'private KList<IrisJigsawWorkcell> planarWorkcells = null;'),
    (1, b'private int width = 16;', b'private int width = 15;'),
    (1, b'@MaxNumber(192)', b'@MaxNumber(256)'),
    (1, b'private boolean enabled = true;', b'private boolean enabled = false;'),
    (1, b'IrisJigsawWorkcellArchetype.BLANK;', b'IrisJigsawWorkcellArchetype.END;'),
    (2, b'    CROSS(15)', b'    FOUR_WAY(15)'),
    (3, b'private int x = 0;', b'private int x = 15;'),
    (4, b'public static final int DEFAULT_SIZE = 16;', b'public static final int DEFAULT_SIZE = 15;'),
    (4, b'2_097_152L', b'4_194_304L'),
    (4, b'resolved.putIfAbsent(workcell.archetype(), workcell) != null', b'false'),
    (4, b'resolved.size() != IrisJigsawWorkcellArchetype.values().length', b'false'),
    (4, b'private static void validate(ResolvedWorkcell workcell) {', b'private static void validate(ResolvedWorkcell workcell) { if (!workcell.enabled()) return;'),
    (5, b'if (workcell.contains(dimensions))', b'if (true)'),
    (6, b'|| PlanarJigsawWorkcellResolver.workcell(planarWorkcells, piece).enabled()', b'|| true'),
]:
    assert old in sources[index], old
    changed = sources.copy()
    changed[index] = changed[index].replace(old, new)
    try:
        extract(*changed)
    except ValueError:
        continue
    raise AssertionError('Accepted planar workcell drift: ' + str(old))
print('PASS: planar workcell constructor/null/partial defaults, complete unique identities, bounds/volume for disabled cells and native fit/participation bindings reject drift')
