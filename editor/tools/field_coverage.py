"""Audit direct nonstatic/nontransient declarations in thirteen pinned Iris classes.
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
    implemented['biome'].update(editable(java_dir, 'Terrain3DFields', 'OBJECT_FIELDS'))
    for group in ['dimension','region','biome']:
        implemented[group].update(editable(java_dir, 'CaveProfileFields', 'OBJECT_FIELDS'))
    implemented['dimension'].update(editable(java_dir, 'NoiseStyleFields', 'DIMENSION_FIELDS'))
    implemented['biome'].update(editable(java_dir, 'NoiseStyleFields', 'BIOME_FIELDS'))
    for group in ['importedStructures', 'importedFeatures']:
        implemented[group] = {path.split('.', 1)[1] for path in ui['dimension'] if path.startswith(group + '.')}
    implemented['interpolator'] = {path.split('.', 1)[1] for path in ui['generator'] if path.startswith('interpolator.')}
    implemented['interpolator'].update(editable(java_dir, 'InterpolationFields'))
    implemented['noise'] = set(editable(java_dir, 'NoiseFields'))
    implemented['noise'].update(editable(java_dir, 'NoiseStyleFields', 'OBJECT_FIELDS'))
    implemented['noiseStyle'] = set(editable(java_dir, 'NoiseStyleFields'))
    implemented['terrain3D'] = set(editable(java_dir, 'Terrain3DFields'))
    implemented['terrain3D'].update(editable(java_dir, 'NoiseStyleFields', 'TERRAIN_FIELDS'))
    implemented['caveProfile'] = set(editable(java_dir, 'CaveProfileFields'))
    implemented['caveProfile'].update(editable(java_dir, 'NoiseStyleFields', 'CAVE_FIELDS'))
    implemented['caveProfile'].update(editable(java_dir, 'CavePlacementFields'))
    implemented['caveProfile'].update(editable(java_dir, 'CaveThresholdFields', 'OBJECT_FIELDS'))
    implemented['styledRange'] = set(editable(java_dir, 'CaveThresholdFields'))
    implemented['biomeGeneratorLink'] = {'min', 'max'}  # paired range controls in BiomeFields
    implemented['biomeGeneratorLink'].update(editable(java_dir, 'BiomeGeneratorLinks'))
    properties = ['irisRevision=c3359e0a16438b1bb8468901c6171dcefdf63f7f']
    report = ['# Editor field coverage', '', 'Direct nonstatic/nontransient private fields in thirteen pinned source classes only. This is not full schema coverage. Unclassified or unimplemented fields remain read-only; this audit never gates Save/Export.', '', '| Class | Declared | Classified | Implemented editable | Unclassified |', '| --- | ---: | ---: | ---: | ---: |']
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
