"""Reject structure defaults, enums, limits and assembly consumer drift."""
import sys
from extract_structure_definition_fields import extract, read_sources

sources = read_sources(sys.argv[1])
result = extract(*sources).decode('ascii')
for token in ['maxDepth.default=7\n', 'maxDepth.maximum=30\n', 'maxSizeChunks.maximum=32\n',
              'requireCaps.default=false\n', 'mode.default=SPATIAL_JIGSAW\n',
              'placeMode.default=STRUCTURE_PIECE\n', 'pool.folder=jigsaw-pools\n',
              'branchFailurePolicy.choices=FAIL_ASSEMBLY,TERMINATE_BRANCH\n']:
    assert token in result, token
for index, old, new in [
    (0, b'private int maxDepth = 7;', b'private int maxDepth = 8;'),
    (0, b'@MaxNumber(32)', b'@MaxNumber(64)'),
    (0, b'private boolean requireCaps = false;', b'private boolean requireCaps = true;'),
    (0, b'return mode == null ? IrisJigsawMode.SPATIAL_JIGSAW : mode;', b'return mode;'),
    (0, b'ObjectPlaceMode.STRUCTURE_PIECE;', b'ObjectPlaceMode.FLOATING;'),
    (1, b'return "jigsaw-pools";', b'return "pools";'),
    (2, b'    SPATIAL_JIGSAW', b'    SPATIAL'),
    (3, b'    VANILLA_PORTABLE', b'    PORTABLE'),
    (4, b'    TERMINATE_BRANCH', b'    TERMINATE'),
    (5, b'    STRUCTURE_PIECE', b'    STRUCTURE_ONLY'),
    (6, b'private static final int MAX_DEPTH = 30;', b'private static final int MAX_DEPTH = 31;'),
    (6, b'!themeSets.isEmpty()', b'false'),
    (6, b'structure.isRequireCaps()', b'false'),
    (6, b'structure.resolvedBranchFailurePolicy()', b'structure.otherPolicy()'),
    (7, b'pool.requiresFallback(structure.isRequireCaps())', b'false'),
    (8, b'ObjectPlaceMode mode = structure.getPlaceMode();', b'ObjectPlaceMode mode = ObjectPlaceMode.FLOATING;'),
]:
    assert old in sources[index], old
    changed = sources.copy()
    changed[index] = changed[index].replace(old, new)
    try:
        extract(*changed)
    except ValueError:
        continue
    raise AssertionError('Accepted structure definition drift: ' + str(old))
print('PASS: structure defaults/enums, exact native bounds, nullable enum defaults, pool folder, portability constraints and assembly/placement consumer bindings reject drift')
