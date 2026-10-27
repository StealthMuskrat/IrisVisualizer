"""Audit the cave threshold's contextual defaults and its styled-range class defaults."""
import hashlib
import json
import re
import sys
from decimal import Decimal
from pathlib import Path


def help_text(annotations):
    match = re.fullmatch(r'\s*@Description\(("(?:[^"\\]|\\.)*")\)\s*', annotations)
    if not match:
        raise ValueError('Changed styled-range annotations')
    return json.loads(match.group(1))


def extract(profile, styled_range, style):
    source, range_text, style_text = (raw.decode('utf-8') for raw in [profile, styled_range, style])
    if '@NoArgsConstructor' not in range_text or '@AllArgsConstructor' not in range_text:
        raise ValueError('Changed styled-range constructors')
    order = re.findall(r'^    private \w+ (\w+) = ', range_text, re.MULTILINE)
    if order != ['min', 'max', 'style']:
        raise ValueError('Changed styled-range constructor order or fields')
    if not re.search(r'public IrisGeneratorStyle\(NoiseStyle s\)\s*\{\s*this.style = s;\s*\}', style_text):
        raise ValueError('Changed generator style constructor')
    literal = r'([-+0-9.eED]+)'
    contextual = re.search(r'((?:    @[^\n]+\n)+)    private IrisStyledRange densityThreshold = new IrisStyledRange\('+literal+r',\s*'+literal+r',\s*NoiseStyle\.([A-Z][A-Z0-9_]*)\.style\(\)\);', source)
    if not contextual:
        raise ValueError('Changed cave threshold constructor')
    values = dict(irisRevision='c3359e0a16438b1bb8468901c6171dcefdf63f7f')
    for key, raw in [('profile', profile), ('styledRange', styled_range), ('style', style)]:
        values[key+'SourceSha256'] = hashlib.sha256(raw).hexdigest()
    values['description'] = help_text(contextual.group(1))
    values['context.min.default'] = str(Decimal(contextual.group(2).removesuffix('D')))
    values['context.max.default'] = str(Decimal(contextual.group(3).removesuffix('D')))
    values['context.style.default'] = contextual.group(4)
    for member in ['min', 'max']:
        field = re.search(r'((?:    @[^\n]+\n)+)    private double '+member+' = '+literal+r';', range_text)
        if not field:
            raise ValueError('Changed styled-range bound: '+member)
        values[member+'.description'] = help_text(field.group(1))
        values[member+'.default'] = str(Decimal(field.group(2).removesuffix('D')))
    selector = re.search(r'((?:    @[^\n]+\n)+)    private IrisGeneratorStyle style = new IrisGeneratorStyle\(NoiseStyle\.([A-Z][A-Z0-9_]*)\);', range_text)
    if not selector:
        raise ValueError('Changed styled-range style initializer')
    values['style.description'] = help_text(selector.group(1))
    values['style.default'] = selector.group(2)
    return ''.join(key+'='+value.replace('\\', '\\\\').replace('\n', '\\n').replace('\r', '\\r')+'\n' for key, value in values.items()).encode('ascii')


if __name__ == '__main__':
    Path(sys.argv[4]).write_bytes(extract(*(Path(value).read_bytes() for value in sys.argv[1:4])))
