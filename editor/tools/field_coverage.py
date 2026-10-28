"""Audit direct nonstatic/nontransient declarations in seventy-nine pinned Iris classes.
This is a bounded source inventory, not the complete inherited/generated Iris schema.
"""
import hashlib
import json
import re
import sys
from pathlib import Path

SOURCES = {
 'dimension': 'generation/terrain/IrisDimension.java',
 'importedStructures': 'structure/nativegen/IrisImportedStructureControl.java',
 'importedFeatures': 'structure/nativegen/IrisImportedFeatureControl.java',
 'biome': 'generation/biome/IrisBiome.java',
 'biomeGeneratorLink': 'generation/biome/IrisBiomeGeneratorLink.java',
 'generator': 'generation/noise/IrisGenerator.java',
 'interpolator': 'generation/noise/IrisInterpolator.java',
 'region': 'generation/terrain/IrisRegion.java',
 'noise': 'generation/noise/IrisNoiseGenerator.java',
 'noiseStyle': 'generation/noise/IrisGeneratorStyle.java',
 'terrain3D': 'generation/terrain/IrisTerrain3D.java',
 'caveProfile': 'generation/cave/IrisCaveProfile.java',
 'styledRange': 'generation/noise/IrisStyledRange.java',
 'caveModule': 'generation/cave/IrisCaveFieldModule.java',
 'range': 'pack/value/IrisRange.java',
 'riverPolicy': 'generation/hydrology/IrisRiverPolicy.java',
 'hydrology': 'generation/hydrology/IrisHydrology.java',
 'riverHydrology': 'generation/hydrology/IrisRiverHydrology.java',
 'riverRouting': 'generation/hydrology/IrisRiverRoutingConfig.java',
 'riverRegional': 'generation/hydrology/IrisRiverRegionalConfig.java',
 'riverGeometry': 'generation/hydrology/IrisRiverGeometryConfig.java',
 'riverMeanders': 'generation/hydrology/IrisRiverMeanderConfig.java',
 'riverSurfaceShape': 'generation/hydrology/IrisSurfaceRiverShapeConfig.java',
 'riverChannelShape': 'generation/hydrology/IrisRiverChannelShapeConfig.java',
 'riverDropShape': 'generation/hydrology/IrisRiverDropShapeConfig.java',
 'riverBank3D': 'generation/hydrology/IrisRiverBank3DConfig.java',
 'surfaceRiver': 'generation/hydrology/IrisSurfaceRiverConfig.java',
 'surfaceRiverSource': 'generation/hydrology/IrisSurfaceRiverSourceConfig.java',
 'surfaceRiverChannel': 'generation/hydrology/IrisSurfaceRiverChannelConfig.java',
 'surfaceRiverBank': 'generation/hydrology/IrisSurfaceRiverBankConfig.java',
 'surfaceRiverFlow': 'generation/hydrology/IrisSurfaceRiverFlowConfig.java',
 'surfaceRiverMouth': 'generation/hydrology/IrisRiverMouthConfig.java',
 'surfaceRiverErosion': 'generation/hydrology/IrisSurfaceRiverErosionConfig.java',
 'surfaceRiverBed': 'generation/hydrology/IrisSurfaceRiverBedConfig.java',
 'riverExcavation': 'generation/hydrology/IrisRiverExcavationConfig.java',
 'surfaceRiverPonds': 'generation/hydrology/IrisSurfaceRiverPondsConfig.java',
 'surfaceRiverPond': 'generation/hydrology/IrisSurfaceRiverPondConfig.java',
 'undergroundRiver': 'generation/hydrology/IrisUndergroundRiverConfig.java',
 'undergroundRiverSource': 'generation/hydrology/IrisUndergroundRiverSourceConfig.java',
 'riverGrottos': 'generation/hydrology/IrisRiverGrottoConfig.java',
 'coastalGrotto': 'generation/hydrology/IrisCoastalRiverGrottoConfig.java',
 'inlandGrotto': 'generation/hydrology/IrisInlandRiverGrottoConfig.java',
 'seaCaves': 'generation/hydrology/IrisSeaCaveConfig.java',
 'riverMaterial': 'generation/hydrology/IrisRiverMaterialConfig.java',
 'materialPalette': 'generation/terrain/IrisMaterialPalette.java',
 'blockData': 'generation/block/IrisBlockData.java',
 'riverProfile': 'generation/hydrology/IrisRiverProfile.java',
 'standingSurfacePool': 'generation/hydrology/IrisSurfacePoolConfig.java',
 'deepFluid': 'generation/hydrology/IrisDeepFluidConfig.java',
 'biomePaletteLayer': 'generation/biome/IrisBiomePaletteLayer.java',
 'slopeClip': 'generation/terrain/IrisSlopeClip.java',
 'floatingBiome': 'generation/biome/IrisFloatingChildBiomes.java',
 'objectPlacement': 'structure/object/IrisObjectPlacement.java',
 'objectTranslate': 'structure/object/IrisObjectTranslate.java',
 'objectScale': 'structure/object/IrisObjectScale.java',
 'objectRotation': 'structure/object/IrisObjectRotation.java',
 'axisRotation': 'structure/object/IrisAxisRotationClamp.java',
 'objectLimit': 'structure/object/IrisObjectLimit.java',
 'stiltSettings': 'generation/decoration/IrisStiltSettings.java',
 'vacuumSettings': 'generation/decoration/IrisVacuumSettings.java',
 'objectReplace': 'structure/object/IrisObjectReplace.java',
 'oreGenerator': 'generation/decoration/IrisOreGenerator.java',
 'depositGenerator': 'generation/decoration/IrisDepositGenerator.java',
 'depositVariant': 'generation/decoration/IrisDepositVariant.java',
 'staticObject': 'structure/object/IrisStaticObject.java',
 'staticObjectRotation': 'structure/object/IrisStaticObjectRotation.java',
 'position': 'pack/value/IrisPosition.java',
 'decorator': 'generation/decoration/IrisDecorator.java',
 'customBiome': 'generation/biome/IrisBiomeCustom.java',
 'customBiomeSpawn': 'generation/biome/IrisBiomeCustomSpawn.java',
 'customBiomeParticle': 'generation/biome/IrisBiomeCustomParticle.java',
 'shapedStyle': 'generation/noise/IrisShapedGeneratorStyle.java',
 'terrainTransform': 'generation/terrain/transform/IrisTerrainTransform.java',
 'dimensionCarving': 'generation/terrain/IrisDimensionCarvingEntry.java',
 'dimensionStack': 'generation/terrain/IrisDimensionStack.java',
 'dimensionStackBlend': 'generation/terrain/IrisDimensionStackBlend.java',
 'dimensionTypeOptions': 'generation/terrain/IrisDimensionTypeOptions.java',
 'worldBoundary': 'world/IrisWorldBoundary.java',
 'worldBoundaryCenter': 'world/IrisWorldBoundaryCenter.java',
}

def declared_fields(text):
    text = re.sub(r'/\*.*?\*/', '', text, flags=re.DOTALL)
    pattern = r'^    private (?P<modifiers>(?:(?:static|final|transient|volatile) )*)(?P<type>[\w.$<>?,\[\] ]+?) (?P<name>\w+)\s*(?==|;)'
    fields = {}
    for match in re.finditer(pattern, text, re.MULTILINE):
        modifiers = match.group('modifiers').split()
        if 'static' not in modifiers and 'transient' not in modifiers:
            fields[match.group('name')] = match.group('type')
    return fields

def editable(java_dir, name, allowlist='EDITABLE'):
    source = (java_dir / (name + '.java')).read_text()
    match = re.search(allowlist + r' = List.of\((.*?)\);', source, re.DOTALL)
    if not match: raise ValueError('Missing UI allowlist: ' + name)
    return re.findall(r'"([^"]+)"', match.group(1))

def run(source_dir, policy_file, java_dir, output_dir):
    policy = json.loads(policy_file.read_text())
    ui = {group: editable(java_dir, cls) for group, cls in [('dimension', 'DimensionFields'), ('biome', 'BiomeFields'), ('generator', 'GeneratorFields'), ('region', 'RegionFields')]}
    implemented = {group: set(path for path in paths if '.' not in path) for group, paths in ui.items()}
    implemented['generator'].update(editable(java_dir, 'CompositeNoise'))
    implemented['generator'].update(editable(java_dir, 'NoiseFields', 'OBJECT_FIELDS'))
    implemented['generator'].update(editable(java_dir, 'InterpolationFields', 'OBJECT_FIELDS'))
    implemented['biome'].update(editable(java_dir, 'BiomeGeneratorLinks', 'LIST_FIELDS'))
    implemented['biome'].update(editable(java_dir, 'BiomeChildren'))
    implemented['biome'].update(editable(java_dir, 'BiomeLayers', 'LIST_FIELDS'))
    implemented['biome'].update(editable(java_dir, 'BiomeDerivatives'))
    implemented['biome'].update(editable(java_dir, 'BiomeSlabWall', 'OBJECT_FIELDS'))
    implemented['biome'].update(editable(java_dir, 'FloatingBiomes', 'LIST_FIELDS'))
    implemented['biome'].update(editable(java_dir, 'BiomeObjectPlacements', 'LIST_FIELDS'))
    implemented['region'].update(editable(java_dir, 'RegionObjectPlacements', 'LIST_FIELDS'))
    implemented['floatingBiome'] = set(editable(java_dir, 'FloatingBiomes'))
    implemented['floatingBiome'].update(editable(java_dir, 'FloatingNoiseStyles'))
    implemented['floatingBiome'].update(editable(java_dir, 'FloatingBottomPalettes'))
    implemented['floatingBiome'].update(editable(java_dir, 'FloatingObjectPlacements', 'LIST_FIELDS'))
    implemented['objectPlacement'] = set(editable(java_dir, 'FloatingObjectPlacements'))
    implemented['objectPlacement'].update(editable(java_dir, 'FloatingPlacementSettings', 'OBJECT_FIELDS'))
    implemented['objectPlacement'].update(editable(java_dir, 'FloatingObjectRotations'))
    implemented['objectPlacement'].update(editable(java_dir, 'FloatingPlacementConstraints'))
    implemented['objectPlacement'].update(editable(java_dir, 'FloatingPlacementEffects'))
    implemented['objectPlacement'].update(editable(java_dir, 'FloatingPlacementDeformation', 'OBJECT_FIELDS'))
    implemented['stiltSettings'] = set(editable(java_dir, 'FloatingPlacementDeformation', 'STILT_FIELDS'))
    implemented['stiltSettings'].update(editable(java_dir, 'FloatingObjectPalettes', 'STILT_FIELDS'))
    implemented['objectPlacement'].update(editable(java_dir, 'FloatingObjectReplacements', 'LIST_FIELDS'))
    implemented['objectPlacement'].update(editable(java_dir, 'FloatingPlacementOverrides'))
    implemented['objectPlacement'].update(editable(java_dir, 'BiomeObjectPlacements'))
    implemented['objectReplace'] = set(editable(java_dir, 'FloatingObjectReplacements'))
    implemented['oreGenerator'] = set(editable(java_dir, 'OreGenerators'))
    for owner in ['dimension','region','biome']: implemented[owner].update(editable(java_dir, 'OreGenerators', 'LIST_FIELDS'))
    implemented['depositGenerator'] = set(editable(java_dir, 'DepositGenerators'))
    for owner in ['dimension','region','biome']: implemented[owner].update(editable(java_dir, 'DepositGenerators', 'LIST_FIELDS'))
    implemented['depositVariant'] = set(editable(java_dir, 'DepositVariants'))
    for owner in ['dimension','region','biome']: implemented[owner].update(editable(java_dir, 'DepositVariants', 'LIST_FIELDS'))
    implemented['biome'].update(editable(java_dir, 'BiomeOreHosts', 'LIST_FIELDS'))
    implemented['biome'].update(editable(java_dir, 'BiomeDecorators', 'LIST_FIELDS'))
    implemented['decorator'] = set(editable(java_dir, 'BiomeDecorators'))
    implemented['biome'].update(editable(java_dir, 'CustomBiomes', 'LIST_FIELDS'))
    implemented['customBiome'] = set(editable(java_dir, 'CustomBiomes'))
    implemented['customBiomeSpawn'] = set(editable(java_dir, 'CustomBiomes', 'SPAWN_FIELDS'))
    implemented['customBiomeParticle'] = set(editable(java_dir, 'CustomBiomes', 'PARTICLE_FIELDS'))
    implemented['dimension'].update(editable(java_dir, 'StaticObjects', 'LIST_FIELDS'))
    implemented['dimension'].update(editable(java_dir, 'StaticObjects', 'ROOT_FIELDS'))
    implemented['dimension'].update(editable(java_dir, 'BlockFallbacks', 'OBJECT_FIELDS'))
    implemented['dimension'].update(editable(java_dir, 'WorldBoundaryFields', 'OBJECT_FIELDS'))
    implemented['dimension'].update(editable(java_dir, 'DimensionTypeFields', 'OBJECT_FIELDS'))
    implemented['dimension'].update(editable(java_dir, 'DimensionTypeFields', 'ROOT_FIELDS'))
    implemented['dimension'].update(editable(java_dir, 'DimensionStacks', 'OBJECT_FIELDS'))
    implemented['dimension'].update(editable(java_dir, 'DimensionStacks', 'ROOT_FIELDS'))
    implemented['dimension'].update(editable(java_dir, 'DimensionCarvings', 'LIST_FIELDS'))
    implemented['dimension'].update(editable(java_dir, 'TerrainOverlays', 'LIST_FIELDS'))
    implemented['dimension'].update(editable(java_dir, 'TerrainTransforms', 'OBJECT_FIELDS'))
    implemented['shapedStyle'] = set(editable(java_dir, 'TerrainOverlays'))
    implemented['terrainTransform'] = set(editable(java_dir, 'TerrainTransforms'))
    implemented['dimensionCarving'] = set(editable(java_dir, 'DimensionCarvings'))
    implemented['dimensionStack'] = set(editable(java_dir, 'DimensionStacks'))
    implemented['dimensionStackBlend'] = set(editable(java_dir, 'DimensionStacks', 'BLEND_FIELDS'))
    implemented['dimensionTypeOptions'] = set(editable(java_dir, 'DimensionTypeFields'))
    implemented['worldBoundary'] = set(editable(java_dir, 'WorldBoundaryFields'))
    implemented['worldBoundaryCenter'] = set(editable(java_dir, 'WorldBoundaryFields', 'AXES'))
    implemented['staticObject'] = set(editable(java_dir, 'StaticObjects'))
    implemented['staticObjectRotation'] = set(editable(java_dir, 'StaticObjects', 'AXES'))
    implemented['position'] = set(editable(java_dir, 'StaticObjects', 'AXES'))
    implemented['vacuumSettings'] = set(editable(java_dir, 'FloatingPlacementDeformation', 'VACUUM_FIELDS'))
    implemented['objectLimit'] = set(editable(java_dir, 'FloatingPlacementConstraints', 'CLAMP_FIELDS'))
    implemented['objectRotation'] = set(editable(java_dir, 'FloatingObjectRotations', 'ROTATION_FIELDS'))
    implemented['axisRotation'] = set(editable(java_dir, 'FloatingObjectRotations', 'AXIS_FIELDS'))
    implemented['objectScale'] = set(editable(java_dir, 'FloatingPlacementSettings', 'SCALE_FIELDS'))
    implemented['objectTranslate'] = set(editable(java_dir, 'FloatingObjectPlacements', 'TRANSLATION_FIELDS'))
    implemented['biome'].update(editable(java_dir, 'Terrain3DFields', 'OBJECT_FIELDS'))
    for group in ['dimension','region','biome']:
        implemented[group].update(editable(java_dir, 'CaveProfileFields', 'OBJECT_FIELDS'))
        implemented[group].update(editable(java_dir, 'RiverPolicyFields', 'OBJECT_FIELDS'))
    implemented['dimension'].update(editable(java_dir, 'NoiseStyleFields', 'DIMENSION_FIELDS'))
    implemented['dimension'].update(editable(java_dir, 'ResourceSelectors', 'DIMENSION_FIELDS'))
    implemented['biome'].update(editable(java_dir, 'ResourceSelectors', 'BIOME_FIELDS'))
    implemented['dimension'].update(editable(java_dir, 'HydrologyFields', 'ROOT_FIELDS'))
    implemented['dimension'].update(editable(java_dir, 'DimensionHeights', 'OBJECT_FIELDS'))
    implemented['dimension'].update(editable(java_dir, 'DimensionPalettes', 'OBJECT_FIELDS'))
    for group in ['dimension','biome']:
        implemented[group].update(editable(java_dir, 'SurfaceLayerFallbacks', 'OBJECT_FIELDS'))
    implemented['biome'].update(editable(java_dir, 'NoiseStyleFields', 'BIOME_FIELDS'))
    for group in ['importedStructures', 'importedFeatures']:
        implemented[group] = {path.split('.', 1)[1] for path in ui['dimension'] if path.startswith(group + '.')}
    implemented['interpolator'] = {path.split('.', 1)[1] for path in ui['generator'] if path.startswith('interpolator.')}
    implemented['interpolator'].update(editable(java_dir, 'InterpolationFields'))
    implemented['noise'] = set(editable(java_dir, 'NoiseFields'))
    implemented['noise'].update(editable(java_dir, 'NoiseFractures'))
    implemented['noise'].update(editable(java_dir, 'NoiseStyleFields', 'OBJECT_FIELDS'))
    implemented['noiseStyle'] = set(editable(java_dir, 'NoiseStyleFields'))
    implemented['noiseStyle'].update(editable(java_dir, 'NoiseStyleSources'))
    implemented['noiseStyle'].update(editable(java_dir, 'StyleFractures'))
    implemented['noiseStyle'].update(editable(java_dir, 'FloatingPlacementConstraints', 'WARP_FIELDS'))
    implemented['terrain3D'] = set(editable(java_dir, 'Terrain3DFields'))
    implemented['terrain3D'].update(editable(java_dir, 'NoiseStyleFields', 'TERRAIN_FIELDS'))
    implemented['caveProfile'] = set(editable(java_dir, 'CaveProfileFields'))
    implemented['caveProfile'].update(editable(java_dir, 'NoiseStyleFields', 'CAVE_FIELDS'))
    implemented['caveProfile'].update(editable(java_dir, 'CavePlacementFields'))
    implemented['caveProfile'].update(editable(java_dir, 'CaveThresholdFields', 'OBJECT_FIELDS'))
    implemented['styledRange'] = set(editable(java_dir, 'CaveThresholdFields'))
    implemented['caveProfile'].update(editable(java_dir, 'CaveModules', 'LIST_FIELDS'))
    implemented['caveModule'] = set(editable(java_dir, 'CaveModules'))
    implemented['caveModule'].update(editable(java_dir, 'CaveModules', 'OBJECT_FIELDS'))
    implemented['range'] = set(editable(java_dir, 'CavePlacementFields', 'RANGE_MEMBERS'))
    implemented['riverPolicy'] = set(editable(java_dir, 'RiverPolicyFields'))
    implemented['riverPolicy'].update(editable(java_dir, 'RiverBiomeLists'))
    implemented['riverPolicy'].update(editable(java_dir, 'RiverIdLists'))
    implemented['hydrology'] = set(editable(java_dir, 'HydrologyFields', 'HYDROLOGY_FIELDS'))
    implemented['riverHydrology'] = set(editable(java_dir, 'HydrologyFields', 'RIVER_FIELDS'))
    implemented['riverRouting'] = set(editable(java_dir, 'HydrologyFields', 'ROUTING_FIELDS'))
    implemented['riverRouting'].update(editable(java_dir, 'RoutingExtras', 'OBJECT_FIELDS'))
    implemented['riverRouting'].update(editable(java_dir, 'RoutingExtras', 'LIST_FIELDS'))
    implemented['riverRegional'] = set(editable(java_dir, 'RoutingExtras', 'REGIONAL_FIELDS'))
    implemented['riverGeometry'] = set(editable(java_dir, 'RiverGeometryFields', 'GEOMETRY_FIELDS'))
    implemented['riverGeometry'].update(editable(java_dir, 'RiverGeometryFields', 'PASSAGE_FIELDS'))
    implemented['riverGeometry'].update(editable(java_dir, 'RiverBankFields', 'OBJECT_FIELDS'))
    implemented['riverBank3D'] = set(editable(java_dir, 'RiverBankFields'))
    implemented['riverBank3D'].update(editable(java_dir, 'RiverBankFields', 'STYLE_FIELDS'))
    implemented['riverChannelShape'] = set(editable(java_dir, 'RiverGeometryFields', 'CHANNEL_FIELDS'))
    implemented['riverDropShape'] = set(editable(java_dir, 'RiverGeometryFields', 'DROP_FIELDS'))
    implemented['riverMeanders'] = set(editable(java_dir, 'RiverGeometryFields', 'MEANDER_FIELDS'))
    implemented['riverSurfaceShape'] = set(editable(java_dir, 'RiverGeometryFields', 'SURFACE_FIELDS'))
    implemented['surfaceRiver'] = set(editable(java_dir, 'SurfaceRiverFields', 'ROOT_FIELDS'))
    implemented['surfaceRiverSource'] = set(editable(java_dir, 'SurfaceRiverFields', 'SOURCE_FIELDS'))
    implemented['surfaceRiverChannel'] = set(editable(java_dir, 'SurfaceRiverFields', 'CHANNEL_FIELDS'))
    implemented['surfaceRiverChannel'].update(editable(java_dir, 'RiverChannelRanges', 'OBJECT_FIELDS'))
    implemented['surfaceRiverBank'] = set(editable(java_dir, 'SurfaceRiverFields', 'BANK_FIELDS'))
    implemented['surfaceRiverFlow'] = set(editable(java_dir, 'SurfaceRiverFields', 'FLOW_FIELDS'))
    implemented['surfaceRiverMouth'] = set(editable(java_dir, 'SurfaceRiverFields', 'MOUTH_FIELDS'))
    implemented['surfaceRiverBank'].update(editable(java_dir, 'SurfaceRiverFields', 'EXCAVATION_OBJECT_FIELDS'))
    implemented['surfaceRiverErosion'] = set(editable(java_dir, 'SurfaceRiverFields', 'EROSION_FIELDS'))
    implemented['surfaceRiverBed'] = set(editable(java_dir, 'SurfaceRiverFields', 'BED_FIELDS'))
    implemented['riverExcavation'] = set(editable(java_dir, 'SurfaceRiverFields', 'EXCAVATION_FIELDS'))
    implemented['surfaceRiverPonds'] = set(editable(java_dir, 'SurfacePondFields', 'CONTEXTS'))
    implemented['surfaceRiverPond'] = set(editable(java_dir, 'SurfacePondFields'))
    implemented['undergroundRiver'] = set(editable(java_dir, 'UndergroundRiverFields', 'ROOT_FIELDS'))
    implemented['undergroundRiverSource'] = set(editable(java_dir, 'UndergroundRiverFields', 'SOURCE_FIELDS'))
    implemented['riverGrottos'] = set(editable(java_dir, 'GrottoFields', 'SECTIONS'))
    implemented['coastalGrotto'] = set(editable(java_dir, 'GrottoFields', 'COASTAL_FIELDS'))
    implemented['inlandGrotto'] = set(editable(java_dir, 'GrottoFields', 'INLAND_FIELDS'))
    implemented['coastalGrotto'].update(editable(java_dir, 'GrottoFields', 'COASTAL_OBJECT_FIELDS'))
    implemented['surfaceRiverBank'].update(editable(java_dir, 'RiverMaterialFields', 'BANK_FIELDS'))
    implemented['surfaceRiverBed'].update(editable(java_dir, 'RiverMaterialFields', 'BED_FIELDS'))
    implemented['undergroundRiver'].update(editable(java_dir, 'RiverMaterialFields', 'UNDERGROUND_FIELDS'))
    implemented['biomePaletteLayer'] = set(editable(java_dir, 'BiomeLayers'))
    implemented['slopeClip'] = set(editable(java_dir, 'BiomeLayers', 'SLOPE_FIELDS'))
    implemented['deepFluid'] = set(editable(java_dir, 'DeepFluids'))
    implemented['standingSurfacePool'] = set(editable(java_dir, 'SurfacePools'))
    implemented['riverProfile'] = set(editable(java_dir, 'RiverProfiles'))
    implemented['riverMaterial'] = set(editable(java_dir, 'RiverMaterialFields'))
    implemented['materialPalette'] = set(editable(java_dir, 'RiverMaterialFields', 'PALETTE_FIELDS'))
    implemented['blockData'] = set(editable(java_dir, 'RiverMaterialFields', 'ENTRY_FIELDS'))
    implemented['seaCaves'] = set(editable(java_dir, 'SeaCaveFields'))
    implemented['biomeGeneratorLink'] = {'min', 'max'}  # paired range controls in BiomeFields
    implemented['biomeGeneratorLink'].update(editable(java_dir, 'BiomeGeneratorLinks'))
    properties = ['irisRevision=c3359e0a16438b1bb8468901c6171dcefdf63f7f']
    report = ['# Editor field coverage', '', 'Direct nonstatic/nontransient private fields in seventy-nine pinned source classes only. This is not full schema coverage. Unclassified or unimplemented fields remain read-only; this audit never gates Save/Export.', '', '| Class | Declared | Classified | Implemented editable | Unclassified |', '| --- | ---: | ---: | ---: | ---: |']
    details = []
    for group, relative in SOURCES.items():
        path = source_dir / Path(relative).name
        if not path.exists(): path = source_dir / 'core/src/main/java/art/arcane/iris' / relative
        raw = path.read_bytes(); fields = declared_fields(raw.decode('utf-8'))
        if not fields: raise ValueError('Empty field inventory: ' + group)
        rules = policy.get(group, {})
        if set(rules) - set(fields): raise ValueError('Policy fields missing in source: ' + group + ' ' + str(set(rules) - set(fields)))
        if implemented[group] - set(fields): raise ValueError('UI fields missing in source: ' + group)
        for field in implemented[group]:
            if rules.get(field, {}).get('classification') != 'generation': raise ValueError('Editable field lacks generation classification: ' + group + '.' + field)
        properties += [group + '.sourceSha256=' + hashlib.sha256(raw).hexdigest(), group + '.fields=' + ','.join(sorted(fields))]
        missing = sorted(set(fields) - set(rules))
        report.append(f'| {group} | {len(fields)} | {len(rules)} | {len(implemented[group])} | {len(missing)} |')
        details += ['', '## ' + group, '', 'Unclassified: ' + (', '.join('`' + field + '`' for field in missing) or 'none') + '.']
        for field in sorted(fields):
            rule = rules.get(field, dict(classification='unclassified', reason='Awaiting audited classification and control coverage.'))
            if rule['classification'] not in ['generation', 'read-only', 'unclassified'] or not rule.get('reason'): raise ValueError('Invalid policy rule')
            prefix = group + '.' + field
            reason = rule['reason'].replace('\\', '\\\\').replace('\n', '\\n')
            properties += [prefix + '.classification=' + rule['classification'], prefix + '.reason=' + reason, prefix + '.implemented=' + str(field in implemented[group]).lower()]
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / 'field-coverage.properties').write_bytes(('\n'.join(properties) + '\n').encode('ascii'))
    (output_dir / 'coverage-report.md').write_bytes(('\n'.join(report + details) + '\n').encode('utf-8'))

if __name__ == '__main__':
    run(*(Path(value) for value in sys.argv[1:]))
