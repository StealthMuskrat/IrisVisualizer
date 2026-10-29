"""Pin literal jigsaw piece controls and native eligibility/consumer bindings."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact

PATHS = ['structure/jigsaw/IrisJigsawPiece.java', 'structure/jigsaw/IrisJigsawPieceRules.java',
         'structure/graph/StructureGraphCompiler.java', 'structure/placement/StructureAssembler.java',
         'structure/graph/JigsawPoolSelection.java', 'studio/jigsaw/JigsawStudioGraphMapper.java']


def read_sources(folder):
    root = Path(folder)
    return [(root / Path(p).name if (root / Path(p).name).is_file() else root / 'core/src/main/java/art/arcane/iris' / p).read_bytes() for p in PATHS]


def extract(*sources):
    piece, rules, compiler, assembler, selection, mapper = [raw.decode('utf-8') for raw in sources]
    values = {'irisRevision': 'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for path, raw in zip(PATHS, sources):
        values[Path(path).stem + '.sourceSha256'] = hashlib.sha256(raw).hexdigest()

    def require(text, *tokens):
        for token in tokens:
            if compact(token) not in compact(text):
                raise ValueError('Changed jigsaw piece contract: ' + token)

    declarations = [(piece, 'object', 'String', '""'), (piece, 'displayName', 'String', '""'),
                    (piece, 'connectors', 'KList<IrisJigsawConnector>', 'new KList<>()'),
                    (piece, 'rotatable', 'boolean', 'true'), (piece, 'collidable', 'boolean', 'true'),
                    (piece, 'themes', 'KList<String>', 'new KList<>()'),
                    (piece, 'rules', 'IrisJigsawPieceRules', 'new IrisJigsawPieceRules()'),
                    (rules, 'minimumDepth', 'int', '0'), (rules, 'maximumDepth', 'int', '30'),
                    (rules, 'minimumPlacements', 'int', '0'), (rules, 'maximumPlacements', 'int', '0'),
                    (rules, 'terminal', 'boolean', 'false')]
    for text, field, kind, default in declarations:
        match = re.search(r'(?P<annotations>(?:\s*@[^\n]+\n)+)\s*private ' + re.escape(kind) + ' ' + field + r' = ([^;]+);', text)
        if not match or match[2] != default:
            raise ValueError('Changed piece declaration: ' + field)
        help_text = re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)', match['annotations'])
        if not help_text:
            raise ValueError('Missing piece help: ' + field)
        values[field + '.description'] = json.loads(help_text[1])
        values[field + '.default'] = json.loads(default) if kind == 'String' else '[]' if kind.startswith('KList') else '{}' if field == 'rules' else default
        if kind == 'int':
            for annotation, name in [('MinNumber', 'minimum'), ('MaxNumber', 'maximum')]:
                bound = re.search('@' + annotation + r'\((\d+)\)', match['annotations'])
                if not bound:
                    raise ValueError('Missing rule bound: ' + field)
                values[field + '.' + name] = bound[1]
    require(piece, '@Required @RegistryListResource(IrisObject.class)',
            '@ArrayType(type = String.class, min = 1)', '@ArrayType(type = IrisJigsawConnector.class, min = 1)',
            'return "jigsaw-pieces";', 'if (themes == null || themes.isEmpty()) { return true; }',
            'if (selectedTheme == null || selectedTheme.isBlank()) { return false; }',
            'if (selectedTheme.equals(theme))', 'return rules == null ? new IrisJigsawPieceRules() : rules;',
            'IrisObjectIO.readPaletteKeysCached(data.getObjectLoader().findFile(object.trim()))')
    require(rules, 'return depth >= minimumDepth && depth <= maximumDepth;',
            '(maximumPlacements == 0 || existingPlacements < maximumPlacements)',
            'return existingPlacements >= 0 && existingPlacements < minimumPlacements;')
    require(compiler, 'private static final int MAX_DEPTH = 30;', 'private static final int ASSEMBLY_PIECE_CAP = 512;',
            'if (themes == null)', 'theme.isEmpty() || !theme.equals(configured)', '!seenThemes.add(theme)',
            '!declaredThemes.contains(theme)', 'rules.getMinimumDepth() >= 0',
            'rules.getMaximumDepth() >= rules.getMinimumDepth()', 'rules.getMaximumDepth() <= MAX_DEPTH',
            'rules.getMinimumPlacements() <= ASSEMBLY_PIECE_CAP', 'rules.getMaximumPlacements() <= ASSEMBLY_PIECE_CAP',
            '(rules.getMaximumPlacements() == 0 || rules.getMinimumPlacements() <= rules.getMaximumPlacements())',
            '&& (themes != null && !themes.isEmpty() || !defaultRules(rules))',
            'return piece.isRotatable() ? Y_ROTATIONS : NO_ROTATION;', 'resolver.loadObject(objectKey)')
    require(assembler, 'if (!candidate.getPiece().isCollidable()) { return false; }',
            'p.getPiece().isCollidable() && p.intersects(candidate)',
            'if (piece.getPiece().resolvedRules().isTerminal())',
            'int minimum = piece.resolvedRules().getMinimumPlacements();')
    require(selection, 'activePiece.supportsTheme(normalize(selectedTheme)) && rules.allowsDepth(depth) && rules.allowsPlacement(existingPlacements)',
            'Objects.requireNonNull(piece).resolvedRules().requiresMorePlacements(existingPlacements)')
    require(mapper, 'piece.getObject(), piece.getDisplayName(), dimensions, mode,')
    for field in ['minimumDepth', 'maximumDepth', 'minimumPlacements', 'maximumPlacements']:
        if (values[field + '.minimum'], values[field + '.maximum']) != ('0', '30' if field.endswith('Depth') else '512'):
            raise ValueError('Changed piece rule limits')
    values.update({'folder': 'jigsaw-pieces', 'rules.policy': 'pairedDepthAndZeroUnboundedCounts',
                   'themes.policy': 'trimExactCaseSensitive', 'execution.policy': 'disabled'})
    return ''.join(k + '=' + json.dumps(v, ensure_ascii=True)[1:-1] + '\n' for k, v in values.items()).encode('ascii')


if __name__ == '__main__':
    Path(sys.argv[2]).write_bytes(extract(*read_sources(sys.argv[1])))
