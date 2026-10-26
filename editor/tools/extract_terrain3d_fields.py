"""Extract bounded terrain controls and verify annotation bounds against native validation calls."""
import hashlib
import json
import re
import sys
from decimal import Decimal
from pathlib import Path

FIELDS = ['enabled','seed','amplitude','horizontalScale','verticalScale','crackDepth','crackWidth','crackScale','minimumSlope','slopeFade','fluidClearance','fluidFade']


def extract(biome, terrain):
    biome_text, text = biome.decode('utf-8'), terrain.decode('utf-8')
    parent = re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private IrisTerrain3D terrain3D = null;',biome_text)
    if not parent or '@NoArgsConstructor' not in text:
        raise ValueError('Changed optional terrain profile type/default/constructor')
    helper = re.search(r'private static void requireRange\([^)]*\)\s*\{(.*?)\n    \}',text,re.DOTALL)
    if not helper or 'if(!Double.isFinite(value)||value<minimum||value>maximum)' not in re.sub(r'\s+','',helper.group(1)):
        raise ValueError('Changed native terrain range validation')
    values = dict(irisRevision='c3359e0a16438b1bb8468901c6171dcefdf63f7f',
                  biomeSourceSha256=hashlib.sha256(biome).hexdigest(),
                  terrainSourceSha256=hashlib.sha256(terrain).hexdigest(),
                  profileDefault='null', description=json.loads(parent.group(1)))
    for name in FIELDS:
        match = re.search(r'((?:    @[^\n]+\n)+)    private (boolean|long|double) '+name+r' = ([^;]+);',text)
        if not match:
            raise ValueError('Changed terrain field: '+name)
        annotations, kind, default = match.groups()
        help_text = re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)',annotations)
        if not help_text:
            raise ValueError('Missing terrain field description')
        values[name+'.type'] = kind
        values[name+'.description'] = json.loads(help_text.group(1))
        if kind == 'boolean':
            if default not in ['true','false']:
                raise ValueError('Nonliteral terrain boolean default')
            values[name+'.default'] = default
        elif kind == 'long':
            values[name+'.default'] = str(int(default.removesuffix('L')))
        else:
            values[name+'.default'] = str(Decimal(default.removesuffix('D')))
            bounds = [re.search(r'@'+annotation+r'\(([^)]+)\)',annotations) for annotation in ['MinNumber','MaxNumber']]
            native = re.search(r'requireRange\("'+name+r'",\s*'+name+r',\s*([^,]+),\s*([^)]+)\);',text)
            if not all(bounds) or not native:
                raise ValueError('Missing native/annotation terrain bounds: '+name)
            for index,key in enumerate(['minimum','maximum']):
                annotated = Decimal(bounds[index].group(1).removesuffix('D'))
                actual = Decimal(native.group(index+1).strip().removesuffix('D'))
                if annotated != actual:
                    raise ValueError('Native and annotation bounds differ: '+name)
                values[name+'.'+key] = str(annotated)
    for name in ['densityStyle','crackStyle']:
        declaration = re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private IrisGeneratorStyle '+name+r' = new IrisGeneratorStyle\(NoiseStyle\.([A-Z][A-Z0-9_]*)\);',text)
        if not declaration or not re.search(r'validateStyle\('+name+r',\s*"'+name+r'",\s*0\);',text):
            raise ValueError('Changed terrain style default/validation binding: '+name)
        values[name+'.default'] = declaration.group(2)
        values[name+'.description'] = json.loads(declaration.group(1))
    for name in ['zoom','cellularFrequency','cellularZoom','exponent']:
        getter = 'get'+name[0].upper()+name[1:]
        bound = re.search(r'requireRange\(path\s*\+\s*"\.'+name+r'",\s*style\.'+getter+r'\(\),\s*([^,]+),\s*([^)]+)\);',text)
        if not bound:
            raise ValueError('Changed terrain-specific style bounds: '+name)
        for index,key in enumerate(['minimum','maximum']):
            token = bound.group(index+1).strip()
            values['style.'+name+'.'+key] = str(sys.float_info.max) if token == 'Double.MAX_VALUE' else str(Decimal(token.removesuffix('D')))
    values['style.runtimeRule'] = 'nativeTerrainRanges'
    return ''.join(key+'='+value.replace('\\','\\\\').replace('\n','\\n').replace('\r','\\r')+'\n' for key,value in values.items()).encode('ascii')


if __name__ == '__main__':
    Path(sys.argv[3]).write_bytes(extract(Path(sys.argv[1]).read_bytes(),Path(sys.argv[2]).read_bytes()))
