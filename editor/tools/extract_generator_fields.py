"""Extract audited generator fields without inventing bounds absent from official annotations."""
import hashlib
import json
import re
import sys
from pathlib import Path
lines = ['irisRevision=c3359e0a16438b1bb8468901c6171dcefdf63f7f']
for path, prefix, names in [(Path(sys.argv[1]), '', ['zoom', 'opacity', 'surfaceDetail', 'multiplicitive', 'cellFractureZoom', 'cellFractureShuffle', 'cellFractureHeight', 'cellPercentSize', 'offsetX', 'offsetZ', 'seed', 'cliffHeightMin', 'cliffHeightMax']),
                            (Path(sys.argv[2]), 'interpolator.', ['horizontalScale'])]:
    source = path.read_bytes()
    lines.append((prefix or 'generator.') + 'sourceSha256=' + hashlib.sha256(source).hexdigest())
    text = source.decode('utf-8')
    for name in names:
        match = re.search(r'((?:    @[^\n]+\n)+)    private (double|boolean|long) ' + name + r' = ([^;]+);', text)
        if not match: raise ValueError('Changed official field: ' + name)
        annotations, kind, default = match.groups()
        fields = dict(type=kind, description=json.loads(re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)', annotations).group(1)))
        if kind == 'boolean':
            if default not in ('true', 'false'): raise ValueError('Changed boolean default')
            fields['default'] = default
        else:
            fields['default'] = str(int(default.removesuffix('L'))) if kind == 'long' else str(float(default.removesuffix('D')))
            for annotation, key in [('MinNumber', 'minimum'), ('MaxNumber', 'maximum')]:
                bound = re.search('@' + annotation + r'\(([^)]+)\)', annotations)
                if bound: fields[key] = float(bound.group(1))
        for key, value in fields.items():
            escaped = str(value).replace('\\', '\\\\').replace('\n', '\\n').replace('\r', '\\r')
            lines.append(prefix + name + '.' + key + '=' + escaped)
source = Path(sys.argv[1]).read_text()
composite = re.search(r'@ArrayType\(min = (\d+), type = IrisNoiseGenerator.class\)\s+@Description\("[^"\n]*"\)\s+private KList<IrisNoiseGenerator> composite = new KList<>\(\);', source)
if not composite: raise ValueError('Changed official composite list definition')
lines.append('composite.minimumEntries=' + composite.group(1))
if not re.search(r'private IrisNoiseGenerator cliffHeightGenerator = new IrisNoiseGenerator\(\);', source):
    raise ValueError('Changed official cliff noise object type/default')
lines.append('cliffHeightGenerator.type=IrisNoiseGenerator')
Path(sys.argv[3]).write_bytes(('\n'.join(lines) + '\n').encode('ascii'))
