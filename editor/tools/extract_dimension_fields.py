"""Regenerate the initial field descriptors from the pinned official IrisDimension source."""
import hashlib
import re
import sys
from pathlib import Path

source = Path(sys.argv[1]).read_bytes()
text = source.decode('utf-8')
lines = ['# Derived from official IrisDimension fields; do not hand-edit constraints.',
         'irisRevision=c3359e0a16438b1bb8468901c6171dcefdf63f7f',
         'sourceSha256=' + hashlib.sha256(source).hexdigest()]
for name in ['fluidHeight', 'dimensionAngleDeg']:
    match = re.search(r'((?:    @[^\n]+\n)+)    private (int|double) ' + name + r' = ([^;]+);', text)
    if not match:
        raise ValueError('Official field definition changed: ' + name)
    annotations, kind, default = match.groups()
    fields = {'type': kind,
              'minimum': float(re.search(r'@MinNumber\(([^)]+)\)', annotations).group(1)),
              'maximum': float(re.search(r'@MaxNumber\(([^)]+)\)', annotations).group(1)),
              'default': float(default)}
    lines.extend(name + '.' + key + '=' + str(value) for key, value in fields.items())
Path(sys.argv[2]).write_bytes(('\n'.join(lines) + '\n').encode('utf-8'))
