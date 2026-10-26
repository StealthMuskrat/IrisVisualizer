"""Extract the audited region scalar/biome-list descriptors from pinned IrisRegion."""
import hashlib
import json
import re
import sys
from pathlib import Path
source = Path(sys.argv[1]).read_bytes()
text = source.decode('utf-8')
lines = ['irisRevision=c3359e0a16438b1bb8468901c6171dcefdf63f7f', 'sourceSha256=' + hashlib.sha256(source).hexdigest()]
for name in ['rarity', 'shoreHeightZoom', 'shoreMinimumWidth', 'landBiomeZoom', 'shoreBiomeZoom', 'seaBiomeZoom', 'caveBiomeZoom', 'landBiomes', 'seaBiomes', 'shoreBiomes', 'caveBiomes']:
    match = re.search(r'((?:    @[^\n]+\n)+)    private (int|double|KList<String>) ' + name + r' = ([^;]+);', text)
    if not match: raise ValueError('Changed region field: ' + name)
    annotations, kind, default = match.groups()
    fields = dict(type=kind, description=json.loads(re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)', annotations).group(1)))
    if kind == 'KList<String>':
        if default != 'new KList<>()' or '@RegistryListResource(IrisBiome.class)' not in annotations: raise ValueError('Changed biome list')
        fields.update(type='biomeArray', default='[]', required=str('@Required' in annotations).lower())
    else:
        fields['default'] = float(default)
        for annotation, key in [('MinNumber', 'minimum'), ('MaxNumber', 'maximum')]:
            bound = re.search('@' + annotation + r'\(([^)]+)\)', annotations)
            if bound: fields[key] = float(bound.group(1))
    for key, value in fields.items():
        escaped = str(value).replace('\\', '\\\\').replace('\n', '\\n').replace('\r', '\\r')
        lines.append(name + '.' + key + '=' + escaped)
Path(sys.argv[2]).write_bytes(('\n'.join(lines) + '\n').encode('ascii'))
