"""Extract the pinned Iris field and VolmLib enum; reject unrecognized enum syntax."""
import hashlib
import json
import re
import sys
from pathlib import Path


def extract(interpolator, methods):
    source = interpolator.decode('utf-8')
    field = re.search(r'((?:    @[^\n]+\n)+)    private InterpolationMethod function = InterpolationMethod\.([A-Z][A-Z0-9_]*);', source)
    if not field:
        raise ValueError('Changed official interpolation field')
    annotations, default = field.groups()
    description = json.loads(re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)', annotations).group(1))
    enum = re.search(r'public enum InterpolationMethod\s*\{(.*)\}\s*$', methods.decode('utf-8'), re.DOTALL)
    if not enum:
        raise ValueError('Changed official interpolation enum')
    body = enum.group(1)
    choices = {}
    entry = re.compile(r'\s*@Description\(("(?:[^"\\]|\\.)*")\)\s+([A-Z][A-Z0-9_]*)\s*,')
    while body.strip():
        match = entry.match(body)
        if not match:
            raise ValueError('Unsupported interpolation enum syntax')
        help_text, name = match.groups()
        if name in choices:
            raise ValueError('Duplicate interpolation enum constant')
        choices[name] = json.loads(help_text)
        body = body[match.end():]
    if default not in choices:
        raise ValueError('Interpolation default missing from enum')
    values = dict(irisRevision='c3359e0a16438b1bb8468901c6171dcefdf63f7f',
                  volmlibRevision='210705c5ab1321dc74b86b48457fd6d5e0452944',
                  interpolatorSourceSha256=hashlib.sha256(interpolator).hexdigest(),
                  methodsSourceSha256=hashlib.sha256(methods).hexdigest(),
                  default=default, description=description, choices=','.join(choices))
    values.update((name + '.description', text) for name, text in choices.items())
    return ''.join(key + '=' + value.replace('\\', '\\\\').replace('\n', '\\n').replace('\r', '\\r') + '\n' for key, value in values.items()).encode('ascii')


if __name__ == '__main__':
    Path(sys.argv[3]).write_bytes(extract(Path(sys.argv[1]).read_bytes(), Path(sys.argv[2]).read_bytes()))
