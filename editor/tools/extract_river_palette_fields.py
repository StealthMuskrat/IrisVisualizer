"""Audit palette zoom and contextual style defaults; shared generator-style metadata is audited separately."""
import hashlib
import json
import re
import sys
from decimal import Decimal
from pathlib import Path
from extract_style_fields import enum_choices


def extract(palette,style,enum):
    text,style_text,enum_text=(raw.decode('utf-8') for raw in [palette,style,enum])
    if '@NoArgsConstructor' not in text:
        raise ValueError('Changed palette constructor defaults')
    contextual=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private IrisGeneratorStyle style = NoiseStyle\.([A-Z][A-Z0-9_]*)\.style\(\);',text)
    zoom=re.search(r'    @MinNumber\(([^)]+)\)\s+@Description\(("(?:[^"\\]|\\.)*")\)\s+private double zoom = ([^;]+);',text)
    empty=re.search(r'private NoiseStyle style = NoiseStyle\.([A-Z][A-Z0-9_]*);',style_text)
    if not contextual or not zoom or not empty:
        raise ValueError('Changed palette zoom/style declarations')
    if not re.search(r'public IrisGeneratorStyle style\(\)\s*\{\s*return new IrisGeneratorStyle\(this\);\s*\}',enum_text):
        raise ValueError('Changed NoiseStyle factory')
    if not re.search(r'public IrisGeneratorStyle\(NoiseStyle s\)\s*\{\s*this.style = s;\s*\}',style_text):
        raise ValueError('Changed generator style constructor')
    choices=enum_choices(enum_text)
    if contextual.group(2) not in choices or empty.group(1) not in choices:
        raise ValueError('Unknown contextual/class style defaults')
    minimum=Decimal(zoom.group(1).removesuffix('D'));default=Decimal(zoom.group(3).removesuffix('D'))
    if not minimum.is_finite() or not default.is_finite() or minimum<=0 or default<minimum:
        raise ValueError('Invalid source palette zoom defaults/bounds')
    runtime=re.sub(r'\s+','',text)
    for expression in ['double scaledX = x / zoom;', 'double scaledY = y / zoom;', 'double scaledZ = z / zoom;',
                       'ignored -> style.create(new RNG(generatorSeed), rdata, engine)']:
        if re.sub(r'\s+','',expression) not in runtime:
            raise ValueError('Changed palette coordinate/style binding')
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for name,raw in [('palette',palette),('style',style),('enum',enum)]:
        values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    values.update({'style.default':contextual.group(2),'style.objectDefault':empty.group(1),
                   'style.description':json.loads(contextual.group(1)),
                   'zoom.default':str(default),'zoom.minimum':str(minimum),'zoom.description':json.loads(zoom.group(2)),
                   'zoom.rule':'finiteAtLeastSourceMinimum'})
    return ''.join(key+'='+value.replace('\\','\\\\').replace('\n','\\n').replace('\r','\\r')+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__':
    Path(sys.argv[4]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:4])))
