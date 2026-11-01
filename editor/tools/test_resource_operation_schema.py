"""Check closed ownership and reject source drift before resource rewrites are enabled."""
from pathlib import Path
import json
import sys
import tempfile
from resource_operation_schema import FILES, extract, source_path

root = Path(sys.argv[1])
values, evidence = extract(root)
manifest = json.loads(Path('editor/policy/resource-operation-sources.json').read_text())
assert manifest['sources'] == evidence
lock=json.loads(Path('iris.lock.json').read_text())
assert manifest['irisRevision'] == lock['iris']['revision']
assert manifest['volmlibRevision'] == lock['volmlib']['revision']
expected = '\n'.join(k + '=' + v for k, v in sorted(values.items())) + '\n'
assert Path('editor/src/main/resources/resource-operation-schema.properties').read_text() == expected
assert values['IrisJigsawPiece.themes'] == 'list:string'
assert values['IrisJigsawConnector.name'] == 'string'
assert values['IrisJigsawConnector.finalState'] == 'string'
assert values['IrisStructure.edit'] == 'list:object:IrisObjectReplace'
assert values['IrisGeneratorStyle.imageMap'] == 'ref:image-maps'
assert values['IrisGeneratorStyle.expression'] == 'ref:expressions'
assert values['IrisImageMap.source'] == 'ref:images'
assert values['IrisImageMap.colors'] == 'context-map:regions,biomes'
assert values['IrisBlockData.block'] == 'block-ref'
assert values['IrisBlockData.block.implicit'] == 'blocks||air'
assert values['IrisObjectReplace.replace.implicit'] == 'blocks|palette/0/block|STONE'
assert values['IrisBlockData.tileData'] == 'opaque-object'
assert len([k for k in values if k.startswith('root.')]) == 7
assert not any(k.startswith('root.dimensions') for k in values)

with tempfile.TemporaryDirectory(prefix='iris-operation-schema-') as directory:
    fixture = Path(directory)
    sources = {}
    for name, relative in FILES.items():
        path = source_path(root, name, relative)
        sources[name] = path.read_text()
        (fixture / (name + '.java')).write_text(sources[name])
    for name, old, new in [
        ('IrisRegistrant', 'private transient String loadKey;', 'private String loadKey;'),
        ('IrisRegistrant', 'private transient String loadKey;', 'protected String loadKey;'),
        ('IrisRegistrant', 'private transient String loadKey;', 'public final String loadKey = "pool";'),
        ('IrisStructure', '@RegistryListResource(IrisJigsawPool.class)', ''),
        ('IrisJigsawConnector', 'private String pool = "";', 'private String pool = "other";'),
        ('IrisJigsawPool', 'private String fallback = "";', 'private String fallback = "other";'),
        ('IrisDirection', 'enum IrisDirection', 'class IrisDirection'),
        ('IrisGeneratorStyle', '@RegistryListResource(IrisExpression.class)', ''),
        ('IrisImageMap', '@RegistryListResource(IrisImage.class)', ''),
        ('IrisBlockData', 'private String block = "air";', 'private String block = "stone";'),
        ('IrisMaterialPalette', 'new IrisBlockData("STONE")', 'new IrisBlockData("AIR")'),
        ('InterpolationMethod', 'enum InterpolationMethod', 'class InterpolationMethod'),
    ]:
        assert old in sources[name], old
        changed = fixture / (name + '.java')
        changed.write_text(sources[name].replace(old, new))
        try:
            extract(fixture)
        except AssertionError:
            pass
        else:
            raise AssertionError('Accepted unreviewed ownership/default/inheritance drift: ' + old)
        finally:
            changed.write_text(sources[name])
    # Even source changes outside parsed declarations are bound to the reviewed loader evidence.
    loader = fixture / 'ResourceLoader.java'
    loader.write_text(sources['ResourceLoader'].replace('candidate.isFile()', 'candidate.exists()'))
    assert extract(fixture)[1] != manifest['sources']
print('PASS: closed resource-operation schema regeneration, nonreference/unsupported classifications, inherited field/reference/default/enum drift and exact loader evidence')
