"""Extract bank controls and verify annotation bounds against native validation calls."""
import hashlib
import json
import re
import sys
from decimal import Decimal
from pathlib import Path

FIELDS = ['enabled','seed','amplitude','horizontalScale','verticalScale','maximumOverhang']
TYPES = dict(enabled='boolean', seed='long', amplitude='double', horizontalScale='double', verticalScale='double', maximumOverhang='int')


def extract(geometry, bank):
    geometry_text, text = geometry.decode('utf-8'), bank.decode('utf-8')
    parent = re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private IrisRiverBank3DConfig banks3D = new IrisRiverBank3DConfig\(\);',geometry_text)
    if not parent or '@NoArgsConstructor' not in text or '@NoArgsConstructor' not in geometry_text:
        raise ValueError('Changed bank parent type/default/constructor')
    helper = re.search(r'private static void requireRange\([^)]*\)\s*\{(.*?)\n    \}',text,re.DOTALL)
    if not helper or 'if(!Double.isFinite(value)||value<minimum||value>maximum)' not in re.sub(r'\s+','',helper.group(1)):
        raise ValueError('Changed native bank range validation')
    values = dict(irisRevision='c3359e0a16438b1bb8468901c6171dcefdf63f7f',
                  geometrySourceSha256=hashlib.sha256(geometry).hexdigest(),
                  bankSourceSha256=hashlib.sha256(bank).hexdigest(),
                  **{'banks3D.default':'{}','banks3D.description':json.loads(parent.group(1))})
    for name in FIELDS:
        match = re.search(r'((?:    @[^\n]+\n)+)    private (boolean|long|double|int) '+name+r' = ([^;]+);',text)
        if not match or match.group(2)!=TYPES[name]:
            raise ValueError('Changed bank field: '+name)
        annotations, kind, default = match.groups()
        help_text = re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)',annotations)
        if not help_text:
            raise ValueError('Missing bank field description')
        values[name+'.type'] = kind
        values[name+'.description'] = json.loads(help_text.group(1))
        if kind == 'boolean':
            if default not in ['true','false']:
                raise ValueError('Nonliteral bank boolean default')
            values[name+'.default'] = default
        elif kind == 'long':
            values[name+'.default'] = str(int(default.removesuffix('L')))
        else:
            values[name+'.default'] = str(int(default)) if kind=='int' else str(Decimal(default.removesuffix('D')))
            bounds = [re.search(r'@'+annotation+r'\(([^)]+)\)',annotations) for annotation in ['MinNumber','MaxNumber']]
            native = re.search(r'requireRange\("'+name+r'",\s*'+name+r',\s*([^,]+),\s*([^)]+)\);',text)
            if not all(bounds) or not native:
                raise ValueError('Missing native/annotation bank bounds: '+name)
            for index,key in enumerate(['minimum','maximum']):
                annotated = Decimal(bounds[index].group(1).removesuffix('D'))
                actual = Decimal(native.group(index+1).strip().removesuffix('D'))
                if annotated != actual:
                    raise ValueError('Native and annotation bounds differ: '+name)
                values[name+'.'+key] = str(annotated)
    compact=re.sub(r'\s+','',text)
    for guard in ['if(densityStyle==null)', 'validateStyle(densityStyle,0);',
                  'if(depth>32||style.getStyle()==null)',
                  'if(style.getFracture()!=null){validateStyle(style.getFracture(),depth+1);}']:
        if guard not in compact:
            raise ValueError('Changed native bank style validation binding/recursion')
    declaration = re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private IrisGeneratorStyle densityStyle = new IrisGeneratorStyle\(NoiseStyle\.([A-Z][A-Z0-9_]*)\);',text)
    if not declaration:
        raise ValueError('Changed bank density style constructor')
    values['densityStyle.default']=declaration.group(2)
    values['densityStyle.description']=json.loads(declaration.group(1))
    values['style.fractureMaximumDepth']='32'
    for name in ['zoom','cellularFrequency','cellularZoom','multiplier','exponent','cacheSize']:
        getter = 'get'+name[0].upper()+name[1:]
        bound = re.search(r'requireRange\("densityStyle\.'+name+r'",\s*style\.'+getter+r'\(\),\s*([^,]+),\s*([^)]+)\);',text)
        if not bound:
            raise ValueError('Changed bank-specific style bounds: '+name)
        for index,key in enumerate(['minimum','maximum']):
            token = bound.group(index+1).strip()
            values['style.'+name+'.'+key] = str(sys.float_info.max) if token == 'Double.MAX_VALUE' else str(Decimal(token.removesuffix('D')))
    values['style.runtimeRule'] = 'nativeBankRanges'
    return ''.join(key+'='+value.replace('\\','\\\\').replace('\n','\\n').replace('\r','\\r')+'\n' for key,value in values.items()).encode('ascii')


if __name__ == '__main__':
    Path(sys.argv[3]).write_bytes(extract(Path(sys.argv[1]).read_bytes(),Path(sys.argv[2]).read_bytes()))
