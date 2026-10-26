"""Extract audited composite noise fields without inventing bounds absent from official annotations."""
import hashlib
import json
import re
import sys
from pathlib import Path
lines = ['irisRevision=c3359e0a16438b1bb8468901c6171dcefdf63f7f']
for path, prefix, names in [(Path(sys.argv[1]), '', ['zoom', 'negative', 'opacity', 'offsetX', 'offsetY', 'offsetZ', 'seed', 'parametric', 'bezier', 'sinCentered', 'exponent', 'enabled', 'octaves'])]:
    source = path.read_bytes()
    lines.append((prefix or 'generator.') + 'sourceSha256=' + hashlib.sha256(source).hexdigest())
    text = source.decode('utf-8')
    for name in names:
        match = re.search(r'((?:    @[^\n]+\n)+)    private (double|boolean|long|int) ' + name + r' = ([^;]+);', text)
        if not match: raise ValueError('Changed official field: ' + name)
        annotations, kind, default = match.groups()
        fields = dict(type=kind, description=json.loads(re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)', annotations).group(1)))
        if kind == 'boolean':
            if default not in ('true', 'false'): raise ValueError('Changed boolean default')
            fields['default'] = default
        else:
            fields['default'] = str(int(default.removesuffix('L'))) if kind in ('long', 'int') else str(float(default.removesuffix('D')))
            for annotation, key in [('MinNumber', 'minimum'), ('MaxNumber', 'maximum')]:
                bound = re.search('@' + annotation + r'\(([^)]+)\)', annotations)
                if bound: fields[key] = float(bound.group(1))
        for key, value in fields.items():
            escaped = str(value).replace('\\', '\\\\').replace('\n', '\\n').replace('\r', '\\r')
            lines.append(prefix + name + '.' + key + '=' + escaped)
Path(sys.argv[2]).write_bytes(('\n'.join(lines) + '\n').encode('ascii'))
