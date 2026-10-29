"""Reject connector declaration/enum/native geometry/matching/priority/output drift."""
import sys
from extract_jigsaw_connector_fields import extract, read_sources

sources = read_sources(sys.argv[1])
result = extract(*sources).decode('ascii')
for token in ['direction.default=NORTH_NEGATIVE_Z\n', 'top.default=UP_POSITIVE_Y\n', 'joint.default=ROLLABLE\n',
              'position.x.default=0\n', 'name.default=\n', 'channel.default=\n', 'finalState.default=minecraft:air\n',
              'selectionPriority.default=0\n', 'text.policy=exactCaseAndWhitespace\n']:
    assert token in result, token
for index, old, new in [
    (0, b'private IrisDirection top = IrisDirection.UP_POSITIVE_Y;', b'private IrisDirection top = IrisDirection.DOWN_NEGATIVE_Y;'),
    (0, b'private String channel = "";', b'private String channel = "default";'),
    (0, b'private int selectionPriority = 0;', b'private int selectionPriority = 1;'),
    (0, b'private String finalState = "minecraft:air";', b'private String finalState = "minecraft:stone";'),
    (0, b'new IrisPosition(centerX, centerY, 0)', b'new IrisPosition(0, centerY, 0)'),
    (1, b'    UP_POSITIVE_Y(', b'    UP('),
    (2, b'    ALIGNED', b'    ALIGN'),
    (3, b'private int x = 0;', b'private int x = 1;'),
    (4, b'@ArrayType(type = IrisJigsawConnector.class, min = 1)', b'@ArrayType(type = IrisJigsawConnector.class, min = 0)'),
    (5, b'position.getX() >= 0', b'position.getX() > 0'),
    (5, b'connector.getTop() != IrisDirection.UP_POSITIVE_Y', b'false'),
    (5, b'Objects.equals(sourceConnector.getChannel(), candidateConnector.getChannel())', b'true'),
    (5, b'Objects.equals(sourceConnector.getTargetName(), candidateConnector.getName())', b'true'),
    (5, b'candidateDirection != sourceDirection.reverse()', b'candidateDirection != sourceDirection'),
    (5, b'!connector.getFinalState().isBlank()', b'true'),
    (6, b'Integer.compare(\n                second.getSelectionPriority(), first.getSelectionPriority())', b'Integer.compare(first.getSelectionPriority(), second.getSelectionPriority())'),
    (6, b'Math.max(highestPriority, expansion.placementPriority())', b'Math.min(highestPriority, expansion.placementPriority())'),
    (7, b'VanillaBlockState.parse(connector.getFinalState()).canonical()', b'"minecraft:air"'),
    (8, b'Bukkit.createBlockData(connector.connector().getFinalState())', b'Bukkit.createBlockData("minecraft:air")'),
]:
    assert old in sources[index], old
    changed = sources.copy(); changed[index] = changed[index].replace(old, new)
    try:
        extract(*changed)
    except ValueError:
        continue
    raise AssertionError('Accepted connector drift: ' + str(old))
print('PASS: connector defaults/choices, exact matching, nonnegative position/native object and planar contracts, signed priorities and final-state output/validity bindings reject drift')
