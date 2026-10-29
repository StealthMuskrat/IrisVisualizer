"""Pin pool membership, direct fallback, chance and native selection bindings."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact

PATHS = ['structure/jigsaw/IrisJigsawPool.java', 'structure/jigsaw/IrisJigsawPieceEntry.java',
         'structure/jigsaw/IrisJigsawPiece.java', 'structure/graph/JigsawPoolSelection.java',
         'structure/graph/StructureGraphCompiler.java', 'structure/placement/StructureAssembler.java']


def read_sources(folder):
    root = Path(folder)
    return [(root / Path(p).name if (root / Path(p).name).is_file() else root / 'core/src/main/java/art/arcane/iris' / p).read_bytes() for p in PATHS]


def extract(*sources):
    pool, entry, piece, selection, compiler, assembler = [s.decode('utf-8') for s in sources]
    values = {'irisRevision': 'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for path, raw in zip(PATHS, sources):
        values[Path(path).stem + '.sourceSha256'] = hashlib.sha256(raw).hexdigest()

    def require(text, *tokens):
        for token in tokens:
            if compact(token) not in compact(text):
                raise ValueError('Changed jigsaw pool contract: ' + token)

    declarations = [(pool, 'pieces', 'KList<IrisJigsawPieceEntry>', 'new KList<>()'),
                    (pool, 'fallback', 'String', '""'), (pool, 'mandatoryFallback', 'boolean', 'false'),
                    (entry, 'piece', 'String', None), (entry, 'weight', 'int', '1'),
                    (entry, 'chance', 'double', '1D'), (entry, 'empty', 'boolean', 'false')]
    for text, field, kind, default in declarations:
        match = re.search(r'(?P<annotations>(?:\s*@[^\n]+\n)+)\s*private ' + re.escape(kind) + ' ' + field + r'(?P<default>[^;]*);', text)
        expected = '' if default is None else ' = ' + default
        if not match or match['default'] != expected:
            raise ValueError('Changed pool declaration: ' + field)
        description = re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)', match['annotations'])
        if not description:
            raise ValueError('Missing pool help: ' + field)
        values[field + '.description'] = json.loads(description[1])
        values[field + '.default'] = 'null' if default is None else '[]' if field == 'pieces' else json.loads(default) if kind == 'String' else default.removesuffix('D')
        if field in ['weight', 'chance']:
            minimum = re.search(r'@MinNumber\((\d+)\)', match['annotations'])
            if not minimum:
                raise ValueError('Missing pool bound: ' + field)
            values[field + '.minimum'] = minimum[1]
        if field == 'chance':
            maximum = re.search(r'@MaxNumber\((\d+)\)', match['annotations'])
            if not maximum:
                raise ValueError('Missing chance maximum')
            values['chance.maximum'] = maximum[1]
    require(pool, '@ArrayType(type = IrisJigsawPieceEntry.class, min = 1)',
            '@RegistryListResource(IrisJigsawPool.class)', 'return "jigsaw-pools";',
            'return structureRequiresCaps || mandatoryFallback;')
    require(entry, '@RegistryListResource(IrisJigsawPiece.class)', 'return chance > 0D && chance < 1D;',
            'return chance >= 1D || chance > 0D && roll < chance;')
    require(piece, 'return "jigsaw-pieces";')
    require(selection, 'return normalize(pool.getFallback());',
            'return directFallback.isEmpty() ? List.of() : List.of(directFallback);',
            'return directFallback.isEmpty() ? List.of(normalizedPrimary) : List.of(normalizedPrimary, directFallback);',
            'if (!Double.isFinite(chance) || chance < 0D || chance > 1D)',
            'if (!activeEntry.requiresChanceRoll()) { return chance >= 1D; }',
            'return activeEntry.passesChance(activeRng.nextDouble());',
            'return value == null ? "" : value.trim();')
    require(compiler, 'if (entry.getWeight() <= 0)',
            'if (!Double.isFinite(entry.getChance()) || entry.getChance() < 0D || entry.getChance() > 1D)',
            'if (entry.isEmpty()) { if (!pieceKey.isEmpty())',
            'if (pool.isMandatoryFallback() && fallback.isEmpty())',
            'if (pool.isMandatoryFallback() && structure.resolvedCompatibility() == IrisJigsawCompatibility.VANILLA_PORTABLE)',
            'if (entry.getChance() != 1D && structure.resolvedCompatibility() == IrisJigsawCompatibility.VANILLA_PORTABLE)')
    require(assembler, 'boolean fallbackRequired = pool.requiresFallback(structure.isRequireCaps());',
            'String fallbackKey = JigsawPoolSelection.directFallbackKey(pool);',
            'KList<IrisJigsawPieceEntry> remaining = new KList<>(chanceEligibleEntries(eligible, rng));',
            'long total = 0L;', 'total += e.getWeight();', 'long t = rng.nextLong(total);',
            'if (JigsawPoolSelection.passesChance(entry, rng))',
            'if (startEntry.isEmpty())', 'return value == null ? "" : value.trim();')
    if (values['weight.minimum'], values['chance.minimum'], values['chance.maximum']) != ('1', '0', '1'):
        raise ValueError('Changed pool bounds')
    values.update({'folder': 'jigsaw-pools', 'piece.folder': 'jigsaw-pieces', 'pieces.minimum': '1',
                   'weight.maximum': '2147483647', 'fallback.policy': 'primaryThenDirectFallbackOnly',
                   'empty.policy': 'removePieceWhenEmpty', 'execution.policy': 'disabled'})
    return ''.join(k + '=' + json.dumps(v, ensure_ascii=True)[1:-1] + '\n' for k, v in values.items()).encode('ascii')


if __name__ == '__main__':
    Path(sys.argv[2]).write_bytes(extract(*read_sources(sys.argv[1])))
