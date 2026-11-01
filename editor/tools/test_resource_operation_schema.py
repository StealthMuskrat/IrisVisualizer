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
assert len([k for k in values if k.startswith('root.')]) == 10
assert values['root.dimensions'] == 'IrisDimension'
assert values['root.regions'] == 'IrisRegion'
assert values['root.biomes'] == 'IrisBiome'
assert values['IrisTreeSettings.fields'] == 'enabled,mode'
assert values['IrisBiomeGeneratorLink.generator'] == 'fallback-ref:generators|default'
assert values['IrisBiome.generators.implicit.0'] == 'generators|0/generator|default'
assert values['IrisBiome.layers.implicit.0'] == 'blocks|0/palette/0/block|GRASS_BLOCK'
assert 'IrisBiome.slab.implicit.count' not in values and 'IrisBiome.wall.implicit.count' not in values
assert values['IrisDimension.hydrology.implicit.count'] == '7'
assert values['IrisDimension.hydrology.implicit.0'].endswith('|sand')
assert values['IrisDimension.hydrology.implicit.3'].endswith('|dirt')
assert values['IrisSurfaceRiverBedConfig.paddingPalette.implicit.count'] == '2'
assert values['IrisDimension.upperDimension'] == 'sentinel-ref:dimensions|none'
assert values['IrisStructurePlacement.structures'] == 'list:ref:structures'
assert values['IrisStructurePlacement.caveBiomes'] == 'list:guard:biomes'
assert values['IrisSubterrainFeature.allowedRegions'] == 'list:guard:regions'
assert values['IrisFloatingChildBiomes.carving'] == 'guard:biomes'
assert values['IrisDepositGenerator.includedBiomes'] == 'list:guard:biomes'
assert values['IrisImageMapMask.map'] == 'string' # named binding, not a resource key
assert values['IrisLoot.customNbt'] == 'opaque-object'
assert values['IrisDimension.terrainTransform'] == 'unsupported:external-terrain-transform'
assert values['IrisDimension.blockFallbacks'] == 'map:string'
assert values['IrisDimension.disabledComponents'] == 'list:string'
assert not any(k.endswith('.defaultBlock') for k in values)

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
        ('IrisTreeSettings', 'boolean enabled = false;', 'boolean hidden = false;'),
        ('IrisBiomeGeneratorLink', 'private String generator = "default";', 'private String generator = "other";'),
        ('IrisDimension', 'private String upperDimension = "none";', 'private String upperDimension = "upper";'),
        ('IrisBiomePaletteLayer', 'new IrisBlockData("GRASS_BLOCK")', 'new IrisBlockData("DIRT")'),
        ('IrisImageMapBinding', '@RegistryListResource(IrisImageMap.class)', ''),
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
print('PASS: ten-owner inherited/nested resource-operation schema regeneration, nonreference/unsupported classifications, inherited field/reference/default/enum drift and exact loader evidence')
