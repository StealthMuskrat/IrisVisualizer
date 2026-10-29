"""Reject jigsaw piece declaration, theme/default/rule and consumer drift."""
import sys
from extract_jigsaw_piece_fields import extract, read_sources

sources = read_sources(sys.argv[1])
result = extract(*sources).decode('ascii')
for token in ['object.default=\n', 'rotatable.default=true\n', 'collidable.default=true\n',
              'maximumDepth.default=30\n', 'maximumPlacements.default=0\n', 'terminal.default=false\n',
              'maximumPlacements.maximum=512\n', 'themes.policy=trimExactCaseSensitive\n']:
    assert token in result, token
for index, old, new in [
    (0, b'private boolean rotatable = true;', b'private boolean rotatable = false;'),
    (0, b'private boolean collidable = true;', b'private boolean collidable = false;'),
    (0, b'private String object = "";', b'private String object = "other";'),
    (0, b'private KList<String> themes = new KList<>();', b'private KList<String> themes = null;'),
    (0, b'return rules == null ? new IrisJigsawPieceRules() : rules;', b'return rules;'),
    (0, b'selectedTheme.equals(theme)', b'selectedTheme.equalsIgnoreCase(theme)'),
    (1, b'private int maximumDepth = 30;', b'private int maximumDepth = 31;'),
    (1, b'@MaxNumber(512)', b'@MaxNumber(1024)'),
    (1, b'maximumPlacements == 0', b'maximumPlacements < 0'),
    (1, b'private boolean terminal = false;', b'private boolean terminal = true;'),
    (2, b'!seenThemes.add(theme)', b'false'),
    (2, b'!declaredThemes.contains(theme)', b'false'),
    (2, b'rules.getMaximumDepth() >= rules.getMinimumDepth()', b'true'),
    (2, b'rules.getMinimumPlacements() <= rules.getMaximumPlacements()', b'true'),
    (2, b'!defaultRules(rules)', b'false'),
    (2, b'piece.isRotatable() ? Y_ROTATIONS : NO_ROTATION', b'Y_ROTATIONS'),
    (3, b'p.getPiece().isCollidable() && p.intersects(candidate)', b'p.intersects(candidate)'),
    (3, b'if (piece.getPiece().resolvedRules().isTerminal())', b'if (false)'),
    (4, b'rules.allowsDepth(depth)', b'true'),
    (4, b'rules.allowsPlacement(existingPlacements)', b'true'),
    (5, b'piece.getDisplayName(),', b'piece.getObject(),'),
]:
    assert old in sources[index], old
    changed = sources.copy(); changed[index] = changed[index].replace(old, new)
    try:
        extract(*changed)
    except ValueError:
        continue
    raise AssertionError('Accepted jigsaw piece drift: ' + str(old))
print('PASS: piece defaults/exact themes, paired rule bounds and zero-unbounded counts, rotation/collision/terminal eligibility and Studio display-only bindings reject drift')
