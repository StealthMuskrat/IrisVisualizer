"""Extract bounded style controls and annotated enum names without running noise factories."""
import hashlib
import json
import re
import sys
from pathlib import Path


def enum_choices(source):
    match = re.search(r'public enum NoiseStyle\s*\{', source)
    if not match:
        raise ValueError('Missing NoiseStyle enum')
    body = source[match.end():]
    choices = {}
    head = re.compile(r'\s*@Description\(("(?:[^"\\]|\\.)*")\)\s+([A-Z][A-Z0-9_]*)\(')
    while True:
        if body.lstrip().startswith(';'):
            break
        entry = head.match(body)
        if not entry:
            raise ValueError('Unsupported noise enum declaration')
        help_text, name = entry.groups()
        if name in choices:
            raise ValueError('Duplicate noise enum constant')
        # Skip a balanced constructor expression, respecting quoted Java tokens. Fail on new syntax.
        position, depth = entry.end(), 1
        while depth and position < len(body):
            character = body[position]
            if character in ('"', "'"):
                quote = character
                position += 1
                while position < len(body):
                    if body[position] == '\\':
                        position += 2
                    elif body[position] == quote:
                        position += 1
                        break
                    else:
                        position += 1
                else:
                    raise ValueError('Unterminated constructor string')
                continue
            if body.startswith('//', position) or body.startswith('/*', position):
                raise ValueError('Unsupported comment in noise enum constructor')
            if character == '(':
                depth += 1
            elif character == ')':
                depth -= 1
            position += 1
        if depth:
            raise ValueError('Unterminated noise enum constructor')
        body = body[position:].lstrip()
        if not body.startswith(','):
            raise ValueError('Expected noise enum separator')
        body = body[1:]
        choices[name] = json.loads(help_text)
    if not choices:
        raise ValueError('Empty noise enum')
    return choices


ROOTS = {
    'dimensions': ['regionStyle', 'continentalStyle', 'landBiomeStyle', 'shoreBiomeStyle', 'seaBiomeStyle', 'caveBiomeStyle'],
    'biomes': ['childStyle', 'biomeStyle'],
}


def cellular_rules(source):
    method = re.search(r'private CNG createNoCache\([^\{]*\)\s*\{(.*?)CNG cng = null;',source,re.DOTALL)
    helper = re.search(r'private static void requirePositiveFinite\([^)]*\)\s*\{(.*?)\n    \}',source,re.DOTALL)
    if not method or not helper:
        raise ValueError('Missing native cellular validation')
    guards = re.sub(r'\s+','',method.group(1))
    positive = re.sub(r'\s+','',helper.group(1))
    if 'if(!Double.isFinite(cellularFrequency)||cellularFrequency<0D){thrownewIllegalArgumentException("cellularFrequencymustbefiniteandnonnegative");}' not in guards:
        raise ValueError('Changed native cellular frequency validation')
    if 'if(cellularFrequency>0D){requirePositiveFinite("cellularZoom",cellularZoom);}' not in guards:
        raise ValueError('Changed native cellular zoom activation rule')
    if positive != 'if(!Double.isFinite(value)||value<=0D||!Double.isFinite(1D/value)){thrownewIllegalArgumentException(field+"mustbefiniteandpositive");}':
        raise ValueError('Changed native finite positive reciprocal validation')
    return {'cellularFrequency.minimum':'0.0',
            'cellularFrequency.runtimeRule':'finiteNonnegative',
            'cellularZoom.runtimeRule':'positiveFiniteReciprocalWhenActive'}


def extract(noise, style, enum, root_sources=None):
    noise_text, style_text, enum_text = (value.decode('utf-8') for value in (noise, style, enum))
    contextual = re.search(r'private IrisGeneratorStyle style = NoiseStyle\.([A-Z][A-Z0-9_]*)\.style\(\);', noise_text)
    default = re.search(r'private NoiseStyle style = NoiseStyle\.([A-Z][A-Z0-9_]*);', style_text)
    if not contextual or not default or not re.search(r'public IrisGeneratorStyle style\(\)\s*\{\s*return new IrisGeneratorStyle\(this\);\s*\}', enum_text):
        raise ValueError('Changed style defaults/factory')
    if not re.search(r'public IrisGeneratorStyle\(NoiseStyle s\)\s*\{\s*this.style = s;\s*\}', style_text):
        raise ValueError('Changed style constructor')
    choices = enum_choices(enum_text)
    if contextual.group(1) not in choices or default.group(1) not in choices:
        raise ValueError('Style default is not a known enum constant')
    values = dict(irisRevision='c3359e0a16438b1bb8468901c6171dcefdf63f7f',
                  noiseSourceSha256=hashlib.sha256(noise).hexdigest(),
                  styleSourceSha256=hashlib.sha256(style).hexdigest(),
                  enumSourceSha256=hashlib.sha256(enum).hexdigest(),
                  noiseDefault=contextual.group(1), objectDefault=default.group(1), choices=','.join(choices))
    values.update((name+'.description', text) for name, text in choices.items())
    help_match = re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private NoiseStyle style =', style_text)
    if not help_match:
        raise ValueError('Changed style description')
    values['style.description'] = json.loads(help_match.group(1))
    for name in ['zoom', 'exponent', 'cellularFrequency', 'cellularZoom']:
        match = re.search(r'((?:    @[^\n]+\n)+)    private (double) '+name+r' = ([^;]+);', style_text)
        if not match:
            raise ValueError('Changed official style field: '+name)
        annotations, kind, token = match.groups()
        values[name+'.type'] = kind
        values[name+'.default'] = str(float(token.removesuffix('D')))
        values[name+'.description'] = json.loads(re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)', annotations).group(1))
        for annotation, key in [('MinNumber', 'minimum'), ('MaxNumber', 'maximum')]:
            bound = re.search('@'+annotation+r'\(([^)]+)\)', annotations)
            if bound:
                values[name+'.'+key] = str(float(bound.group(1)))
    values.update(cellular_rules(style_text))
    for group, raw in (root_sources or {}).items():
        if group not in ROOTS:
            raise ValueError('Unsupported style root group')
        values[group+'.sourceSha256'] = hashlib.sha256(raw).hexdigest()
        text = raw.decode('utf-8')
        for name in ROOTS[group]:
            declaration = re.search(r'((?:    @[^\n]+\n)+)    private IrisGeneratorStyle '+name+r' = NoiseStyle\.([A-Z][A-Z0-9_]*)\.style\(\);', text)
            if not declaration or declaration.group(2) not in choices:
                raise ValueError('Changed contextual style default: '+group+'.'+name)
            help_text = re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)', declaration.group(1))
            if not help_text:
                raise ValueError('Missing contextual style description')
            values[group+'.'+name+'.default'] = declaration.group(2)
            values[group+'.'+name+'.description'] = json.loads(help_text.group(1))
    return ''.join(key+'='+value.replace('\\', '\\\\').replace('\n', '\\n').replace('\r', '\\r')+'\n' for key, value in values.items()).encode('ascii')


if __name__ == '__main__':
    if len(sys.argv) not in (5, 7):
        raise ValueError('Expected noise, style, enum, output and optional dimension/biome sources')
    roots = dict(zip(['dimensions','biomes'], (Path(value).read_bytes() for value in sys.argv[5:])))
    Path(sys.argv[4]).write_bytes(extract(*(Path(value).read_bytes() for value in sys.argv[1:4]), root_sources=roots))
