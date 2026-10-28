"""Pin authored Iris structure roots and the native assembly/placement contracts."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact

PATHS = ['structure/placement/IrisStructure.java', 'structure/jigsaw/IrisJigsawPool.java',
         'structure/jigsaw/IrisJigsawMode.java', 'structure/jigsaw/IrisJigsawCompatibility.java',
         'structure/jigsaw/IrisJigsawBranchFailurePolicy.java', 'structure/object/ObjectPlaceMode.java',
         'structure/graph/StructureGraphCompiler.java', 'structure/placement/StructureAssembler.java',
         'generation/mantle/IrisStructureComponent.java', 'structure/placement/IrisStructureLocator.java']


def read_sources(folder):
    folder = Path(folder)
    return [(folder / Path(path).name if (folder / Path(path).name).is_file()
             else folder / 'core/src/main/java/art/arcane/iris' / path).read_bytes() for path in PATHS]


def extract(*sources):
    d, p, m, c, b, modes, compiler, assembler, component, locator = [raw.decode('utf-8') for raw in sources]
    values = {'irisRevision': 'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for path, raw in zip(PATHS, sources):
        values[Path(path).stem + '.sourceSha256'] = hashlib.sha256(raw).hexdigest()

    def require(text, *contracts):
        for contract in contracts:
            if compact(contract) not in compact(text):
                raise ValueError('Changed structure definition contract: ' + contract)

    declarations = {'startPool': ('String', '""'), 'maxDepth': ('int', '7'),
                    'maxSizeChunks': ('int', '8'), 'mode': ('IrisJigsawMode', 'IrisJigsawMode.SPATIAL_JIGSAW'),
                    'compatibility': ('IrisJigsawCompatibility', 'IrisJigsawCompatibility.IRIS_EXTENDED'),
                    'branchFailurePolicy': ('IrisJigsawBranchFailurePolicy', 'IrisJigsawBranchFailurePolicy.FAIL_ASSEMBLY'),
                    'requireCaps': ('boolean', 'false'), 'placeMode': ('ObjectPlaceMode', 'ObjectPlaceMode.STRUCTURE_PIECE')}
    for field, (kind, default) in declarations.items():
        match = re.search(r'(?P<annotations>(?:\s*@[^\n]+\n)+)\s*private ' + kind + ' ' + field + r' = (?P<default>[^;]+);', d)
        if not match or match['default'] != default:
            raise ValueError('Changed structure declaration: ' + field)
        description = re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)', match['annotations'])
        if not description:
            raise ValueError('Missing structure description: ' + field)
        values[field + '.description'] = json.loads(description[1])
        values[field + '.default'] = json.loads(default) if kind == 'String' else default.split('.')[-1]
        values[field + '.type'] = kind
        if kind == 'int':
            for annotation, name in [('MinNumber', 'minimum'), ('MaxNumber', 'maximum')]:
                bound = re.search('@' + annotation + r'\((\d+)\)', match['annotations'])
                if not bound:
                    raise ValueError('Missing structure bound: ' + field)
                values[field + '.' + name] = bound[1]
    require(d, '@Required @RegistryListResource(IrisJigsawPool.class)', 'return "structures";',
            'return mode == null ? IrisJigsawMode.SPATIAL_JIGSAW : mode;',
            'return compatibility == null ? IrisJigsawCompatibility.IRIS_EXTENDED : compatibility;',
            'return branchFailurePolicy == null ? IrisJigsawBranchFailurePolicy.FAIL_ASSEMBLY : branchFailurePolicy;')
    require(p, 'return "jigsaw-pools";')
    expected = [('mode', m, ['PLANAR_JIGSAW', 'SPATIAL_JIGSAW']),
                ('compatibility', c, ['IRIS_EXTENDED', 'VANILLA_PORTABLE']),
                ('branchFailurePolicy', b, ['FAIL_ASSEMBLY', 'TERMINATE_BRANCH']),
                ('placeMode', modes, None)]
    for field, text, names in expected:
        items = re.findall(r'@Description\(("(?:[^"\\]|\\.)*")\)\s*([A-Z][A-Z0-9_]*)\s*(?=,|;|\})', text)
        actual = [name for _, name in items]
        if not actual or names is not None and actual != names or field == 'placeMode' and 'STRUCTURE_PIECE' not in actual:
            raise ValueError('Changed structure enum: ' + field)
        values[field + '.choices'] = ','.join(actual)
        for description, name in items:
            values[field + '.' + name + '.description'] = json.loads(description)
    for text in [compiler, assembler]:
        require(text, 'private static final int MAX_DEPTH = 30;', 'private static final int MAX_SIZE_CHUNKS = 32;',
                'structure.getMaxDepth() < 1 || structure.getMaxDepth() > MAX_DEPTH')
    require(compiler, 'structure.getMaxSizeChunks() < 1 || structure.getMaxSizeChunks() > MAX_SIZE_CHUNKS',
            'String startPool = normalize(structure.getStartPool());',
            'structure.resolvedMode() != IrisJigsawMode.PLANAR_JIGSAW',
            '!themeSets.isEmpty() && structure.resolvedCompatibility() == IrisJigsawCompatibility.VANILLA_PORTABLE',
            'structure.isRequireCaps() && structure.resolvedCompatibility() == IrisJigsawCompatibility.VANILLA_PORTABLE',
            'structure.resolvedCompatibility() == IrisJigsawCompatibility.VANILLA_PORTABLE && structure.resolvedBranchFailurePolicy() != IrisJigsawBranchFailurePolicy.TERMINATE_BRANCH')
    if (values['maxDepth.minimum'], values['maxDepth.maximum'], values['maxSizeChunks.minimum'], values['maxSizeChunks.maximum']) != ('1', '30', '1', '32'):
        raise ValueError('Structure annotations no longer match native limits')
    require(assembler, 'structure.getMaxSizeChunks() * 16', 'pool.requiresFallback(structure.isRequireCaps())',
            'structure.resolvedBranchFailurePolicy() == IrisJigsawBranchFailurePolicy.FAIL_ASSEMBLY')
    require(component, 'ObjectPlaceMode mode = structure.getPlaceMode();',
            'mode == ObjectPlaceMode.STRUCTURE_PIECE || mode == ObjectPlaceMode.FLOATING',
            'ObjectPlaceMode undergroundMode = (mode == ObjectPlaceMode.ORGANIC_STILT || mode == ObjectPlaceMode.CEILING_HANG) ? mode : ObjectPlaceMode.STRUCTURE_PIECE;',
            'else if (pieces.size() == 1)')
    require(locator, 'boolean replacesNative = NativeStructureSuppression.REPLACE_SOURCE == placement.getNativeSuppression();',
            'String vanillaSource = structure.getVanillaSource();',
            'if (vanillaSource == null || !NAMESPACED_RESOURCE_KEY.matcher(vanillaSource).matches())',
            'suppressedVanillaSources.add(normalize(vanillaSource));')
    values.update({'folder': 'structures', 'pool.folder': 'jigsaw-pools', 'enums.null': 'modeCompatibilityBranchDefaults',
                   'reset.policy': 'removeOptionalField', 'portable.policy': 'themesCapsAndBranchTermination', 'execution.policy': 'disabled'})
    return ''.join(key + '=' + json.dumps(value, ensure_ascii=True)[1:-1] + '\n' for key, value in values.items()).encode('ascii')


if __name__ == '__main__':
    Path(sys.argv[2]).write_bytes(extract(*read_sources(sys.argv[1])))
