"""Extract bounded biome/editor fields from pinned official source."""
import hashlib
import json
import re
import sys
from pathlib import Path
lines = ['irisRevision=c3359e0a16438b1bb8468901c6171dcefdf63f7f']
for path, prefix, names in [(Path(sys.argv[1]), '', ['rarity', 'caveMinDepthBelowSurface', 'oreDepositFrequencyMultiplier', 'oreDepositSizeMultiplier', 'lockLayers', 'mergeFloatingChildBiomes']),
                            (Path(sys.argv[2]), 'generators.', ['min', 'max'])]:
    source = path.read_bytes()
    lines.append((prefix or 'biome.') + 'sourceSha256=' + hashlib.sha256(source).hexdigest())
    text = source.decode('utf-8')
    for name in names:
        match = re.search(r'((?:    @[^\n]+\n)+)    private (int|double|boolean) ' + name + r' = ([^;]+);', text)
        if not match: raise ValueError('Changed official field: ' + name)
        annotations, kind, default = match.groups()
        description = json.loads(re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)', annotations).group(1))
        fields = dict(type=kind, description=description)
        if kind == 'boolean':
            if default not in ('true', 'false'): raise ValueError('Changed boolean default')
            fields['default'] = default
        else:
            fields.update(minimum=float(re.search(r'@MinNumber\(([^)]+)\)', annotations).group(1)),
                          maximum=float(re.search(r'@MaxNumber\(([^)]+)\)', annotations).group(1)),
                          default=float(default.removesuffix('D')))
        for key, value in fields.items():
            escaped = str(value).replace('\\', '\\\\').replace('\n', '\\n').replace('\r', '\\r')
            lines.append(prefix + name + '.' + key + '=' + escaped)
Path(sys.argv[3]).write_bytes(('\n'.join(lines) + '\n').encode('ascii'))
