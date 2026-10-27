"""Extract cave vertical bounds and placement enum metadata from pinned declarations."""
import hashlib
import json
import re
import sys
from decimal import Decimal
from pathlib import Path


def description(annotations):
    match = re.fullmatch(r'\s*@Description\(("(?:[^"\\]|\\.)*")\)\s*', annotations)
    if not match:
        raise ValueError('Changed range/placement field annotations')
    return json.loads(match.group(1))


def enum_values(raw, name):
    match = re.search(r'public enum '+name+r'\s*\{(.*)\}\s*$', raw.decode('utf-8'), re.DOTALL)
    if not match:
        raise ValueError('Changed enum declaration: '+name)
    body, choices = match.group(1), {}
    entry = re.compile(r'\s*@Description\(("(?:[^"\\]|\\.)*")\)\s+([A-Z][A-Z0-9_]*)\s*(,|$)')
    while body.strip():
        item = entry.match(body)
        if not item or item.group(2) in choices:
            raise ValueError('Unsupported or duplicate enum entry: '+name)
        choices[item.group(2)] = json.loads(item.group(1))
        body = body[item.end():]
    if not choices:
        raise ValueError('Empty enum: '+name)
    return choices


def extract(profile, range_source, anchors, placements):
    source, range_text = profile.decode('utf-8'), range_source.decode('utf-8')
    if '@NoArgsConstructor' not in range_text or '@AllArgsConstructor' not in range_text:
        raise ValueError('Changed range constructor defaults')
    values = dict(irisRevision='c3359e0a16438b1bb8468901c6171dcefdf63f7f')
    for key, raw in [('profile',profile),('range',range_source),('anchor',anchors),('placement',placements)]:
        values[key+'SourceSha256'] = hashlib.sha256(raw).hexdigest()
    literal = r'([-+0-9.eED]+)'
    fields = re.findall(r'((?:    @[^\n]+\n)+)    private double (\w+) = '+literal+r';', range_text)
    # Lombok's all-args constructor follows declaration order.
    if [field[1] for field in fields] != ['min','max'] or len(re.findall(r'^    private ',range_text,re.MULTILINE)) != 2:
        raise ValueError('Changed range fields or constructor order')
    for annotations, name, default in fields:
        values['range.'+name+'.description'] = description(annotations)
        values['range.'+name+'.default'] = str(Decimal(default.removesuffix('D')))
    contextual = re.search(r'((?:    @[^\n]+\n)+)    private IrisRange verticalRange = new IrisRange\('+literal+r',\s*'+literal+r'\);',source)
    if not contextual:
        raise ValueError('Changed cave vertical range')
    values['verticalRange.description'] = description(contextual.group(1))
    values['verticalRange.min.default'] = str(Decimal(contextual.group(2).removesuffix('D')))
    values['verticalRange.max.default'] = str(Decimal(contextual.group(3).removesuffix('D')))
    for field, kind, raw in [('defaultObjectAnchor','IrisCaveAnchorMode',anchors),('defaultObjectPlaceMode','ObjectPlaceMode',placements)]:
        declaration = re.search(r'((?:    @[^\n]+\n)+)    private '+kind+' '+field+r' = ([^;]+);',source)
        if not declaration:
            raise ValueError('Changed cave placement field: '+field)
        choices = enum_values(raw,kind)
        expression = declaration.group(2)
        default = expression.removeprefix(kind+'.')
        if (expression != kind+'.'+default or default not in choices) and not (field=='defaultObjectPlaceMode' and expression=='null'):
            raise ValueError('Unsupported cave placement default')
        values[field+'.description'] = description(declaration.group(1))
        values[field+'.default'] = default
        values[field+'.choices'] = ','.join(choices)
        values.update((field+'.'+name+'.description',help_text) for name,help_text in choices.items())
    return ''.join(key+'='+value.replace('\\','\\\\').replace('\n','\\n').replace('\r','\\r')+'\n' for key,value in values.items()).encode('ascii')


if __name__ == '__main__':
    Path(sys.argv[5]).write_bytes(extract(*(Path(value).read_bytes() for value in sys.argv[1:5])))
