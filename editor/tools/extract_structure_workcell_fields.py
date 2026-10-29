"""Pin planar workcell authoring defaults, complete identity maps and native bounds."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact

PATHS = ['structure/placement/IrisStructure.java', 'structure/jigsaw/IrisJigsawWorkcell.java',
         'structure/jigsaw/IrisJigsawWorkcellArchetype.java', 'pack/value/IrisPosition.java',
         'structure/graph/PlanarJigsawWorkcellResolver.java', 'structure/graph/StructureGraphCompiler.java',
         'structure/placement/StructureAssembler.java']


def read_sources(folder):
    folder = Path(folder)
    return [(folder / Path(path).name if (folder / Path(path).name).is_file()
             else folder / 'core/src/main/java/art/arcane/iris' / path).read_bytes() for path in PATHS]


def extract(*sources):
    structure, cell, archetypes, position, resolver, compiler, assembler = [raw.decode('utf-8') for raw in sources]
    values = {'irisRevision': 'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for path, raw in zip(PATHS, sources):
        values[Path(path).stem + '.sourceSha256'] = hashlib.sha256(raw).hexdigest()

    def require(text, *contracts):
        for contract in contracts:
            if compact(contract) not in compact(text):
                raise ValueError('Changed planar workcell contract: ' + contract)

    legacy = re.search(r'private IrisPosition cellSize = new IrisPosition\((\d+), (\d+), (\d+)\);', structure)
    if not legacy or legacy.groups() != ('15', '15', '15'):
        raise ValueError('Changed structure cellSize constructor')
    for axis, value in zip(['x', 'y', 'z'], legacy.groups()):
        values['legacy.' + axis + '.default'] = value
        match = re.search('private int ' + axis + r' = (\d+);', position)
        if not match or match[1] != '0':
            raise ValueError('Changed IrisPosition default')
        values['position.' + axis + '.default'] = match[1]
    require(structure, 'private KList<IrisJigsawWorkcell> planarWorkcells = new KList<>();')
    for name in ['cellSize', 'planarWorkcells']:
        match = re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s*private [^;\n]+ ' + name + r' =', structure)
        if not match:
            raise ValueError('Missing structure workcell description')
        values[name + '.description'] = json.loads(match[1])
    declarations = {'archetype': ('IrisJigsawWorkcellArchetype', 'IrisJigsawWorkcellArchetype.BLANK'),
                    'width': ('int', '16'), 'height': ('int', '16'), 'depth': ('int', '16'), 'enabled': ('boolean', 'true')}
    for name, (kind, default) in declarations.items():
        match = re.search(r'(?P<annotations>(?:\s*@[^\n]+\n)+)\s*private ' + kind + ' ' + name + r' = ([^;]+);', cell)
        if not match or match[2] != default:
            raise ValueError('Changed workcell declaration: ' + name)
        description = re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)', match['annotations'])
        if not description:
            raise ValueError('Missing workcell description')
        values[name + '.description'] = json.loads(description[1])
        values[name + '.default'] = default.split('.')[-1]
        if kind == 'int':
            for annotation, key in [('MinNumber', 'minimum'), ('MaxNumber', 'maximum')]:
                bound = re.search('@' + annotation + r'\((\d+)\)', match['annotations'])
                if not bound:
                    raise ValueError('Missing workcell bound')
                values[name + '.' + key] = bound[1]
    entries = re.findall(r'@Description\(("(?:[^"\\]|\\.)*")\)\s*([A-Z]+)\((\d+)\)', archetypes)
    if [name for _, name, _ in entries] != ['BLANK', 'END', 'STRAIGHT', 'CORNER', 'TEE', 'CROSS']:
        raise ValueError('Changed planar archetype inventory')
    values['archetypes'] = ','.join(name for _, name, _ in entries)
    for description, name, _ in entries:
        values['archetype.' + name + '.description'] = json.loads(description)
    for name in ['DEFAULT_SIZE', 'MAX_WIDTH', 'MAX_HEIGHT', 'MAX_DEPTH', 'MAX_VOLUME']:
        match = re.search(r'public static final (?:int|long) ' + name + r' = ([\d_]+)L?;', resolver)
        if not match:
            raise ValueError('Missing native workcell limit')
        values['native.' + name] = match[1].replace('_', '')
    for field, low, high in [('width', '3', '128'), ('height', '1', '192'), ('depth', '3', '128')]:
        if (values[field + '.minimum'], values[field + '.maximum']) != (low, high):
            raise ValueError('Changed workcell annotation limits')
        if values[field + '.maximum'] != values['native.MAX_' + field.upper()]:
            raise ValueError('Workcell annotation/native limits disagree')
    if values['native.DEFAULT_SIZE'] != '16' or values['native.MAX_VOLUME'] != '2097152':
        raise ValueError('Changed native fallback or volume')
    require(resolver,
            'source.getPlanarWorkcells() == null || source.getPlanarWorkcells().isEmpty()',
            'source.getCellSize() == null ? new IrisPosition(DEFAULT_SIZE, DEFAULT_SIZE, DEFAULT_SIZE) : source.getCellSize()',
            'for (IrisJigsawWorkcellArchetype archetype : IrisJigsawWorkcellArchetype.values())',
            'configured == null || configured.getArchetype() == null',
            'validate(workcell);', 'resolved.putIfAbsent(workcell.archetype(), workcell) != null',
            'resolved.size() != IrisJigsawWorkcellArchetype.values().length',
            'workcell.width() < 3 || workcell.width() > MAX_WIDTH || workcell.height() < 1 || workcell.height() > MAX_HEIGHT || workcell.depth() < 3 || workcell.depth() > MAX_DEPTH',
            'long volume = (long) workcell.width() * workcell.height() * workcell.depth();', 'if (volume > MAX_VOLUME)')
    validation = resolver[resolver.index('private static void validate('):]
    if 'enabled(' in validation or 'isEnabled(' in validation:
        raise ValueError('Native bounds may no longer apply to disabled workcells')
    require(compiler, 'planarWorkcells = PlanarJigsawWorkcellResolver.resolve(structure);',
            'if (structure.resolvedMode() != IrisJigsawMode.PLANAR_JIGSAW || planarWorkcells.isEmpty())',
            'IrisPosition dimensions = PlanarJigsawWorkcellResolver.canonicalDimensions(piece, object);',
            'if (workcell.contains(dimensions))', 'PlanarJigsawWorkcellResolver.workcell(planarWorkcells, piece).enabled()')
    require(assembler, 'return structure.resolvedMode() != IrisJigsawMode.PLANAR_JIGSAW || PlanarJigsawWorkcellResolver.workcell(planarWorkcells, piece).enabled();')
    values.update({'identity.policy': 'sixCompleteUniqueArchetypes', 'bounds.policy': 'atomicTripletWithVolume',
                   'creation.policy': 'appliedLegacyBounds', 'disabled.bounds': 'stillRequired', 'execution.policy': 'disabled'})
    return ''.join(key + '=' + json.dumps(value, ensure_ascii=True)[1:-1] + '\n' for key, value in values.items()).encode('ascii')


if __name__ == '__main__':
    Path(sys.argv[2]).write_bytes(extract(*read_sources(sys.argv[1])))
