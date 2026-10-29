"""Reject theme key/default/weight and native selector/filter contract drift."""
import sys
from extract_structure_theme_fields import extract, read_sources

sources = read_sources(sys.argv[1])
result = extract(*sources).decode('ascii')
for token in ['key.default=\n', 'weight.default=1\n', 'weight.maximum=2147483647\n',
              'themeSets.annotationMinimum=1\n', 'key.policy=trimExactCaseSensitive\n',
              'null.compiler=invalid\n', 'piece.emptyThemes=unfiltered\n']:
    assert token in result, token
for index, old, new in [
    (0, b'@ArrayType(type = IrisJigsawThemeSet.class, min = 1)', b'@ArrayType(type = IrisJigsawThemeSet.class, min = 0)'),
    (1, b'private String key = "";', b'private String key = "default";'),
    (1, b'private int weight = 1;', b'private int weight = 0;'),
    (1, b'@MinNumber(1)', b'@MinNumber(0)'),
    (2, b'long totalWeight = 0L;', b'int totalWeight = 0;'),
    (2, b'Math.addExact(totalWeight, theme.getWeight())', b'totalWeight + theme.getWeight()'),
    (2, b'activeRng.nextLong(totalWeight)', b'activeRng.nextInt((int) totalWeight)'),
    (2, b'if (themes.size() == 1)', b'if (false)'),
    (2, b'value.trim()', b'value.toLowerCase()'),
    (3, b'selectedTheme.equals(theme)', b'selectedTheme.equalsIgnoreCase(theme)'),
    (3, b'themes == null || themes.isEmpty()', b'false'),
    (3, b'return "jigsaw-pieces";', b'return "pieces";'),
    (4, b'key.isEmpty() || !key.equals(theme.getKey())', b'key.isEmpty()'),
    (4, b'!keys.add(key)', b'false'),
    (4, b'if (themeSets == null)', b'if (false)'),
    (5, b'JigsawPoolSelection.selectTheme(structure, rng)', b'""'),
]:
    assert old in sources[index], old
    changed = sources.copy()
    changed[index] = changed[index].replace(old, new)
    try:
        extract(*changed)
    except ValueError:
        continue
    raise AssertionError('Accepted structure theme drift: ' + str(old))
print('PASS: theme declarations, trim/exact keys, positive int weights, long totals, null/empty distinctions, selector order and literal piece filter contracts reject drift')
