"""Reject drift in pool membership/default/fallback/chance bindings."""
import sys
from extract_jigsaw_pool_fields import extract, read_sources

sources = read_sources(sys.argv[1])
result = extract(*sources).decode('ascii')
for token in ['weight.default=1\n', 'weight.minimum=1\n', 'chance.default=1\n', 'chance.maximum=1\n',
              'empty.default=false\n', 'fallback.default=\n', 'mandatoryFallback.default=false\n',
              'pieces.minimum=1\n', 'piece.default=null\n', 'fallback.policy=primaryThenDirectFallbackOnly\n']:
    assert token in result, token
for index, old, new in [
    (0, b'private String fallback = "";', b'private String fallback = "other";'),
    (0, b'private boolean mandatoryFallback = false;', b'private boolean mandatoryFallback = true;'),
    (0, b'min = 1', b'min = 0'),
    (0, b'structureRequiresCaps || mandatoryFallback', b'mandatoryFallback'),
    (1, b'private String piece;', b'private String piece = "";'),
    (1, b'private int weight = 1;', b'private int weight = 2;'),
    (1, b'private double chance = 1D;', b'private double chance = 0D;'),
    (1, b'private boolean empty = false;', b'private boolean empty = true;'),
    (1, b'@MaxNumber(1)', b'@MaxNumber(2)'),
    (1, b'chance > 0D && chance < 1D', b'chance > 0D'),
    (2, b'return "jigsaw-pieces";', b'return "pieces";'),
    (3, b'List.of(normalizedPrimary, directFallback)', b'List.of(normalizedPrimary)'),
    (3, b'!activeEntry.requiresChanceRoll()', b'false'),
    (3, b'value.trim()', b'value.toLowerCase()'),
    (4, b'entry.getWeight() <= 0', b'entry.getWeight() < 0'),
    (4, b'if (!pieceKey.isEmpty())', b'if (false)'),
    (4, b'pool.isMandatoryFallback() && fallback.isEmpty()', b'false'),
    (4, b'entry.getChance() != 1D', b'false'),
    (5, b'pool.requiresFallback(structure.isRequireCaps())', b'false'),
    (5, b'new KList<>(chanceEligibleEntries(eligible, rng))', b'new KList<>(eligible)'),
    (5, b'long total = 0L;', b'int total = 0;'),
    (5, b'JigsawPoolSelection.passesChance(entry, rng)', b'true'),
]:
    assert old in sources[index], old
    changed = sources.copy(); changed[index] = changed[index].replace(old, new)
    try:
        extract(*changed)
    except ValueError:
        continue
    raise AssertionError('Accepted jigsaw pool drift: ' + str(old))
print('PASS: pool membership defaults/bounds, empty ambiguity, direct fallback/caps, chance gates, long selection totals and native graph bindings reject drift')
