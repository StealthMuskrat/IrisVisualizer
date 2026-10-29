"""Pin connector defaults, enum choices and native matching/geometry/output consumers."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact

PATHS = ['structure/jigsaw/IrisJigsawConnector.java', 'pack/value/IrisDirection.java',
         'structure/jigsaw/JigsawJoint.java', 'pack/value/IrisPosition.java', 'structure/jigsaw/IrisJigsawPiece.java',
         'structure/graph/StructureGraphCompiler.java', 'structure/placement/StructureAssembler.java',
         'structure/export/VanillaStructureTemplateEncoder.java', 'studio/jigsaw/JigsawStudioChunkWriter.java']


def read_sources(folder):
    root = Path(folder)
    return [(root / Path(p).name if (root / Path(p).name).is_file() else root / 'core/src/main/java/art/arcane/iris' / p).read_bytes() for p in PATHS]


def extract(*sources):
    connector, direction, joint, position, piece, compiler, assembler, encoder, writer = [s.decode('utf-8') for s in sources]
    values = {'irisRevision': 'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for path, raw in zip(PATHS, sources):
        values[Path(path).stem + '.sourceSha256'] = hashlib.sha256(raw).hexdigest()

    def require(text, *tokens):
        for token in tokens:
            if compact(token) not in compact(text):
                raise ValueError('Changed connector contract: ' + token)

    for field, kind, default in [('position', 'IrisPosition', 'new IrisPosition()'),
                                 ('direction', 'IrisDirection', 'IrisDirection.NORTH_NEGATIVE_Z'),
                                 ('top', 'IrisDirection', 'IrisDirection.UP_POSITIVE_Y'),
                                 ('pool', 'String', '""'), ('name', 'String', '""'), ('targetName', 'String', '""'),
                                 ('channel', 'String', '""'), ('joint', 'JigsawJoint', 'JigsawJoint.ROLLABLE'),
                                 ('finalState', 'String', '"minecraft:air"'),
                                 ('selectionPriority', 'int', '0'), ('placementPriority', 'int', '0')]:
        match = re.search(r'(?P<annotations>(?:\s*@[^\n]+\n)+)\s*private ' + kind + ' ' + field + r' = ([^;]+);', connector)
        if not match or match[2] != default:
            raise ValueError('Changed connector declaration: ' + field)
        description = re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)', match['annotations'])
        if not description:
            raise ValueError('Missing connector description: ' + field)
        values[field + '.description'] = json.loads(description[1])
        values[field + '.default'] = json.loads(default) if kind == 'String' else '{}' if field == 'position' else default.split('.')[-1]
        if field in ['position', 'direction', 'top', 'pool', 'finalState']:
            require(match['annotations'], '@Required')
        if field == 'pool':
            require(match['annotations'], '@RegistryListResource(IrisJigsawPool.class)')
    directions = re.findall(r'@Description\(("(?:[^"\\]|\\.)*")\)\s*([A-Z][A-Z0-9_]*)\(', direction)
    if [name for _, name in directions] != ['UP_POSITIVE_Y', 'DOWN_NEGATIVE_Y', 'NORTH_NEGATIVE_Z', 'SOUTH_POSITIVE_Z', 'EAST_POSITIVE_X', 'WEST_NEGATIVE_X']:
        raise ValueError('Changed connector direction choices')
    joints = re.findall(r'@Description\(("(?:[^"\\]|\\.)*")\)\s*([A-Z][A-Z0-9_]*)\s*(?=,|\})', joint)
    if [name for _, name in joints] != ['ROLLABLE', 'ALIGNED']:
        raise ValueError('Changed connector joints')
    for field, choices in [('direction', directions), ('top', directions), ('joint', joints)]:
        values[field + '.choices'] = ','.join(name for _, name in choices)
        for description, name in choices:
            values[field + '.' + name + '.description'] = json.loads(description)
    for axis in ['x', 'y', 'z']:
        require(position, 'private int ' + axis + ' = 0;')
        values['position.' + axis + '.default'] = '0'
    membership = re.search(r'@ArrayType\(type = IrisJigsawConnector.class, min = (\d+)\)\s*@Description\(("(?:[^"\\]|\\.)*")\)\s*private KList<IrisJigsawConnector> connectors = new KList<>\(\);', piece)
    if not membership or membership[1] != '1':
        raise ValueError('Changed connector list declaration')
    values['connectors.description'] = json.loads(membership[2]); values['connectors.annotationMinimum'] = membership[1]
    require(compiler, 'if (connectors == null)', 'boolean validDirection = connector.getDirection() != null;',
            'boolean validTop = connector.getTop() != null;', 'boolean validJoint = connector.getJoint() != null;',
            'connector.getName() != null && connector.getTargetName() != null',
            'connector.getChannel() != null && connector.getFinalState() != null && !connector.getFinalState().isBlank()',
            'if (!Objects.equals(sourceConnector.getChannel(), candidateConnector.getChannel()))',
            'if (!Objects.equals(sourceConnector.getTargetName(), candidateConnector.getName()))',
            'candidateDirection != sourceDirection.reverse()', 'sourceConnector.getJoint() != JigsawJoint.ALIGNED',
            'return candidateTop == sourceTop;', 'position.getX() >= 0 && position.getX() < object.getW()',
            'position.getY() >= 0 && position.getY() < object.getH()', 'position.getZ() >= 0 && position.getZ() < object.getD()',
            'connector.getDirection().isVertical()', 'connector.getTop() != IrisDirection.UP_POSITIVE_Y',
            'IrisJigsawConnector.canonicalPlanarPosition(objectSize, connector.getDirection())',
            'connector.getChannel() != null && !connector.getChannel().isBlank()')
    require(connector, 'case NORTH_NEGATIVE_Z -> new IrisPosition(centerX, centerY, 0);',
            'case EAST_POSITIVE_X -> new IrisPosition(dimensions.getX() - 1, centerY, centerZ);',
            'case SOUTH_POSITIVE_Z -> new IrisPosition(centerX, centerY, dimensions.getZ() - 1);',
            'case WEST_NEGATIVE_X -> new IrisPosition(0, centerY, centerZ);')
    require(assembler, 'ordered.sort((first, second) -> Integer.compare(second.getSelectionPriority(), first.getSelectionPriority()));',
            'highestPriority = Math.max(highestPriority, expansion.placementPriority());',
            'if (expansion.placementPriority() == highestPriority)',
            'connector.getFinalState() == null || connector.getFinalState().isBlank()')
    require(encoder, 'nbt.putString("final_state", VanillaBlockState.parse(connector.getFinalState()).canonical());')
    require(writer, 'restored = Bukkit.createBlockData(connector.connector().getFinalState());', 'target.setBlockData(restored, false);')
    values.update({'text.policy': 'exactCaseAndWhitespace', 'position.policy': 'nonnegativeIntTripleObjectBoundsUnchecked',
                   'priority.policy': 'signedInt32', 'finalState.policy': 'literalNonblankTemplateAndValidityState',
                   'execution.policy': 'disabled'})
    return ''.join(k + '=' + json.dumps(v, ensure_ascii=True)[1:-1] + '\n' for k, v in values.items()).encode('ascii')


if __name__ == '__main__':
    Path(sys.argv[2]).write_bytes(extract(*read_sources(sys.argv[1])))
