"""Pin structure theme declarations, key validation, weighting and piece filtering."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact

PATHS = ['structure/placement/IrisStructure.java', 'structure/jigsaw/IrisJigsawThemeSet.java',
         'structure/graph/JigsawPoolSelection.java', 'structure/jigsaw/IrisJigsawPiece.java',
         'structure/graph/StructureGraphCompiler.java', 'structure/placement/StructureAssembler.java']


def read_sources(folder):
    folder = Path(folder)
    return [(folder / Path(path).name if (folder / Path(path).name).is_file()
             else folder / 'core/src/main/java/art/arcane/iris' / path).read_bytes() for path in PATHS]


def extract(*sources):
    structure, theme, selection, piece, compiler, assembler = [raw.decode('utf-8') for raw in sources]
    values = {'irisRevision': 'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for path, raw in zip(PATHS, sources):
        values[Path(path).stem + '.sourceSha256'] = hashlib.sha256(raw).hexdigest()

    def require(text, *contracts):
        for contract in contracts:
            if compact(contract) not in compact(text):
                raise ValueError('Changed structure theme contract: ' + contract)

    list_match = re.search(r'@ArrayType\(type = IrisJigsawThemeSet.class, min = (\d+)\)\s*@Description\(("(?:[^"\\]|\\.)*")\)\s*private KList<IrisJigsawThemeSet> themeSets = new KList<>\(\);', structure)
    if not list_match or list_match[1] != '1':
        raise ValueError('Changed theme list declaration')
    values['themeSets.description'] = json.loads(list_match[2])
    values['themeSets.annotationMinimum'] = list_match[1]
    for field, kind, default in [('key', 'String', '""'), ('weight', 'int', '1')]:
        match = re.search(r'(?P<annotations>(?:\s*@[^\n]+\n)+)\s*private ' + kind + ' ' + field + r' = ([^;]+);', theme)
        if not match or match[2] != default:
            raise ValueError('Changed theme field: ' + field)
        description = re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)', match['annotations'])
        if not description:
            raise ValueError('Missing theme help')
        values[field + '.description'] = json.loads(description[1])
        values[field + '.default'] = json.loads(default) if kind == 'String' else default
        require(match['annotations'], '@Required' if field == 'key' else '@MinNumber(1)')
    values['weight.minimum'] = '1'
    values['weight.maximum'] = str(2**31 - 1)  # Java int width; no extra source maximum annotation.
    require(compiler, 'if (themeSets == null)', 'StructureGraphDiagnostic.Code.INVALID_THEME',
            'String key = normalize(theme.getKey());', 'key.isEmpty() || !key.equals(theme.getKey())',
            '!keys.add(key)', 'if (theme.getWeight() <= 0)',
            '!themeSets.isEmpty() && structure.resolvedCompatibility() == IrisJigsawCompatibility.VANILLA_PORTABLE',
            'return value == null ? "" : value.trim();')
    require(selection, 'if (activeStructure.getThemeSets() == null || activeStructure.getThemeSets().isEmpty()) { return ""; }',
            'long totalWeight = 0L;', 'theme == null || normalize(theme.getKey()).isEmpty() || theme.getWeight() <= 0',
            'totalWeight = Math.addExact(totalWeight, theme.getWeight());',
            'if (themes.size() == 1) { return normalize(themes.getFirst().getKey()); }',
            'long target = activeRng.nextLong(totalWeight);', 'target -= theme.getWeight();',
            'return normalize(theme.getKey());', 'return value == null ? "" : value.trim();')
    require(piece, 'private KList<String> themes = new KList<>();',
            'if (themes == null || themes.isEmpty()) { return true; }',
            'if (selectedTheme == null || selectedTheme.isBlank()) { return false; }',
            'if (selectedTheme.equals(theme)) { return true; }', 'return "jigsaw-pieces";')
    require(assembler, 'String selectedTheme = JigsawPoolSelection.selectTheme(structure, rng);',
            'JigsawPoolSelection.pieceEligible(piece, selectedTheme, depth, placements)')
    values.update({'key.policy': 'trimExactCaseSensitive', 'empty.selection': 'unthemed',
                   'null.compiler': 'invalid', 'piece.emptyThemes': 'unfiltered',
                   'total.policy': 'positiveIntWeightsSummedAsLong', 'execution.policy': 'disabled'})
    return ''.join(key + '=' + json.dumps(value, ensure_ascii=True)[1:-1] + '\n' for key, value in values.items()).encode('ascii')


if __name__ == '__main__':
    Path(sys.argv[2]).write_bytes(extract(*read_sources(sys.argv[1])))
