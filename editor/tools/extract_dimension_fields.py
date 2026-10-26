"""Regenerate audited descriptors from three pinned official Iris source files."""
import hashlib
import json
import re
import sys
from pathlib import Path

sources = {
    'dimension': (Path(sys.argv[1]), ['fluidHeight', 'dimensionAngleDeg', 'landChance',
        'objectSurfaceSupportBuffer', 'bedrock', 'decorate', 'carvingEnabled',
        'postProcessing', 'postProcessingSlabs', 'postProcessingWalls', 'requireObjectSurfaceSupport', 'regions', 'biomeZoom', 'landZoom', 'seaZoom', 'continentZoom', 'regionZoom', 'coordFractureZoom', 'coordFractureDistance', 'terrainSamplingStep', 'biomeBoundsSamplingStep', 'caveDensitySamplingStep', 'caveLavaHeight', 'upperDimensionGap', 'upperDimensionCarving', 'upperDimensionObjects', 'upperObjectsForcePlace']),
    'importedStructures': (Path(sys.argv[3]), ['undergroundYShift', 'datapackOverrides', 'disabled', 'disabledExact']),
    'importedFeatures': (Path(sys.argv[4]), ['enabled', 'disabled', 'disabledExact']),
}

def escape(value):
    return str(value).replace('\\', '\\\\').replace('\n', '\\n').replace('\r', '\\r').replace('\t', '\\t')

lines = ['# Derived from official Iris fields; do not hand-edit constraints.',
         'irisRevision=c3359e0a16438b1bb8468901c6171dcefdf63f7f']
for group, (path, names) in sources.items():
    source = path.read_bytes()
    text = source.decode('utf-8')
    lines.append(group + '.sourceSha256=' + hashlib.sha256(source).hexdigest())
    for name in names:
        match = re.search(r'((?:    @[^\n]+\n)+)    private (int|double|boolean|KList<String>) ' + name + r' = ([^;]+);', text)
        if not match:
            raise ValueError('Official field definition changed: ' + group + '.' + name)
        annotations, kind, default = match.groups()
        description = re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)', annotations)
        if not description:
            raise ValueError('Missing source description: ' + name)
        fields = {'type': kind, 'description': json.loads(description.group(1))}
        if kind == 'KList<String>':
            if default != 'new KList<>()' or not re.search(r'@ArrayType\((?:type = String.class, min = 1|min = 1, type = String.class)\)', annotations):
                raise ValueError('Changed array definition: ' + name)
            if name == 'regions':
                if '@RegistryListResource(IrisRegion.class)' not in annotations or '@Required' not in annotations:
                    raise ValueError('Changed region reference definition')
                fields.update(type='regionArray', default='[]')
            else:
                fields.update(type='stringArray', default='[]', prefixes=str(name == 'disabled').lower())
        elif kind == 'boolean':
            if default not in ('true', 'false'):
                raise ValueError('Nonliteral boolean default: ' + name)
            fields['default'] = default
        else:
            fields.update(minimum=float(re.search(r'@MinNumber\(([^)]+)\)', annotations).group(1)),
                          maximum=float(re.search(r'@MaxNumber\(([^)]+)\)', annotations).group(1)),
                          default=float(default.removesuffix('D')))
        options = re.search(r'@IntegerOptions\(\{([^}]+)\}\)', annotations)
        if options:
            fields['options'] = ','.join(str(int(value.strip())) for value in options.group(1).split(','))
        key = name if group == 'dimension' else group + '.' + name
        lines.extend(key + '.' + item + '=' + escape(value) for item, value in fields.items())
Path(sys.argv[2]).write_bytes(('\n'.join(lines) + '\n').encode('ascii'))
