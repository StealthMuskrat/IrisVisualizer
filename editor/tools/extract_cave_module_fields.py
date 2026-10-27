"""Audit cave module list, scalar, style and range declarations without loading Iris."""
import hashlib
import json
import re
import sys
from decimal import Decimal
from pathlib import Path

SCALARS = ['weight', 'threshold', 'invert']


def description(annotations):
    match = re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)', annotations)
    if not match:
        raise ValueError('Missing cave module description')
    return json.loads(match.group(1))


def extract(profile, module, range_source, style):
    source, text, range_text, style_text = (raw.decode('utf-8') for raw in [profile, module, range_source, style])
    if '@NoArgsConstructor' not in text or '@NoArgsConstructor' not in range_text or '@AllArgsConstructor' not in range_text:
        raise ValueError('Changed module/range constructors')
    owner = re.search(r'@ArrayType\(type = IrisCaveFieldModule.class, min = (\d+)\)\s+@Description\(("(?:[^"\\]|\\.)*")\)\s+private KList<IrisCaveFieldModule> modules = new KList<>\(\);', source)
    if not owner:
        raise ValueError('Changed module list type, default or minimum')
    if not re.search(r'public IrisGeneratorStyle\(NoiseStyle s\)\s*\{\s*this.style = s;\s*\}', style_text):
        raise ValueError('Changed style constructor')
    values = dict(irisRevision='c3359e0a16438b1bb8468901c6171dcefdf63f7f', minimumEntries=owner.group(1), description=json.loads(owner.group(2)), moduleDefault='{}', listDefault='[]')
    for key, raw in [('profile',profile),('module',module),('range',range_source),('style',style)]:
        values[key+'SourceSha256'] = hashlib.sha256(raw).hexdigest()
    for field in SCALARS:
        item = re.search(r'((?:    @[^\n]+\n)+)    private (boolean|double) '+field+r' = ([^;]+);', text)
        if not item or (field=='invert') != (item.group(2)=='boolean'):
            raise ValueError('Changed cave module scalar: '+field)
        annotations, kind, default = item.groups()
        values[field+'.type'] = kind
        values[field+'.description'] = description(annotations)
        if kind=='boolean':
            if default not in ['true','false']:
                raise ValueError('Changed module boolean initializer')
            values[field+'.default'] = default
        else:
            values[field+'.default'] = str(Decimal(default.removesuffix('D')))
            for annotation, key in [('MinNumber','minimum'),('MaxNumber','maximum')]:
                bound = re.search('@'+annotation+r'\(([^)]+)\)', annotations)
                if bound:
                    values[field+'.'+key] = str(Decimal(bound.group(1).removesuffix('D')))
    selector = re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private IrisGeneratorStyle style = NoiseStyle\.([A-Z][A-Z0-9_]*)\.style\(\);', text)
    if not selector:
        raise ValueError('Changed module style initializer')
    values['style.description'], values['style.default'] = json.loads(selector.group(1)), selector.group(2)
    literal = r'([-+0-9.eED]+)'
    bounds = re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private IrisRange verticalRange = new IrisRange\('+literal+r',\s*'+literal+r'\);', text)
    if not bounds:
        raise ValueError('Changed module vertical range initializer')
    values['verticalRange.description'] = json.loads(bounds.group(1))
    values['verticalRange.min.default'] = str(Decimal(bounds.group(2).removesuffix('D')))
    values['verticalRange.max.default'] = str(Decimal(bounds.group(3).removesuffix('D')))
    fields = re.findall(r'((?:    @[^\n]+\n)+)    private double (\w+) = '+literal+r';', range_text)
    if [entry[1] for entry in fields] != ['min','max'] or len(re.findall(r'^    private ',range_text,re.MULTILINE)) != 2:
        raise ValueError('Changed range constructor fields/order')
    for annotations, name, default in fields:
        if not re.fullmatch(r'\s*@Description\(("(?:[^"\\]|\\.)*")\)\s*', annotations):
            raise ValueError('Changed range annotations')
        values['range.'+name+'.default'] = str(Decimal(default.removesuffix('D')))
    return ''.join(key+'='+value.replace('\\','\\\\').replace('\n','\\n').replace('\r','\\r')+'\n' for key,value in values.items()).encode('ascii')


if __name__ == '__main__':
    Path(sys.argv[5]).write_bytes(extract(*(Path(value).read_bytes() for value in sys.argv[1:5])))
