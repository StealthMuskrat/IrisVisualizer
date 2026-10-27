"""Extract regional routing settings and declared inland-outlet enum choices from pinned source."""
import hashlib
import json
import re
import sys
from decimal import Decimal
from pathlib import Path

FIELDS=['enabled','sampleSpacing','minimumLength','maximumTrunks','maximumCachedBasins','maximumCachedStations','coastalChannels','coastalChannelChance','maximumCoastalIncision']
BOOLEANS={'enabled','coastalChannels'}


def extract(routing, regional, outlets):
    route, region, enum=(raw.decode('utf-8') for raw in [routing,regional,outlets])
    if '@NoArgsConstructor' not in region:
        raise ValueError('Changed regional constructor defaults')
    parent=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private IrisRiverRegionalConfig regional = new IrisRiverRegionalConfig\(\);',route)
    list_field=re.search(r'@ArrayType\(type = IrisRiverInlandOutlet\.class\)\s+@Description\(("(?:[^"\\]|\\.)*")\)\s+private KList<IrisRiverInlandOutlet> inlandOutlets = new KList<>\(\);',route)
    declaration=re.search(r'public enum IrisRiverInlandOutlet\s*\{(.*)\}\s*$',enum,re.DOTALL)
    if not parent or not list_field or not declaration or not re.fullmatch(r'\s*[A-Z][A-Z0-9_]*(?:\s*,\s*[A-Z][A-Z0-9_]*)*\s*,?\s*',declaration.group(1)):
        raise ValueError('Changed regional parent/outlet list/enum contract')
    choices=re.findall(r'[A-Z][A-Z0-9_]*',declaration.group(1))
    if len(choices)!=len(set(choices)):
        raise ValueError('Duplicate inland outlet enum constant')
    values=dict(irisRevision='c3359e0a16438b1bb8468901c6171dcefdf63f7f',regionalDefault='{}',outletsDefault='[]',regionalDescription=json.loads(parent.group(1)),outletsDescription=json.loads(list_field.group(1)),outletChoices=','.join(choices))
    for key, raw in [('routing',routing),('regional',regional),('outlets',outlets)]:
        values[key+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    for name in FIELDS:
        field=re.search(r'((?:    @[^\n]+\n)+)    private (boolean|int|double) '+name+r' = ([^;]+);',region)
        expected='boolean' if name in BOOLEANS else 'double' if name=='coastalChannelChance' else 'int'
        if not field or field.group(2)!=expected:
            raise ValueError('Changed regional scalar: '+name)
        annotations, kind, default=field.groups()
        help_text=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)',annotations)
        if not help_text:
            raise ValueError('Missing regional help')
        values[name+'.type']=kind
        values[name+'.description']=json.loads(help_text.group(1))
        if kind=='boolean':
            if default not in ['true','false']:
                raise ValueError('Changed regional boolean initializer')
            values[name+'.default']=default
        else:
            values[name+'.default']=str(int(default)) if kind=='int' else str(Decimal(default.removesuffix('D')))
            for annotation,key in [('MinNumber','minimum'),('MaxNumber','maximum')]:
                bound=re.search('@'+annotation+r'\(([^)]+)\)',annotations)
                if bound:
                    values[name+'.'+key]=str(Decimal(bound.group(1).removesuffix('D')))
    if 'a power of two' not in values['sampleSpacing.description']:
        raise ValueError('Changed documented regional spacing rule')
    minimum,maximum=int(values['sampleSpacing.minimum']),int(values['sampleSpacing.maximum'])
    options=[str(1 << bit) for bit in range(31) if minimum <= (1 << bit) <= maximum]
    if values['sampleSpacing.default'] not in options:
        raise ValueError('Regional spacing default violates documented rule')
    values['sampleSpacing.rule']='positivePowerOfTwo'
    values['sampleSpacing.options']=','.join(options)
    return ''.join(key+'='+value.replace('\\','\\\\').replace('\n','\\n').replace('\r','\\r')+'\n' for key,value in values.items()).encode('ascii')


if __name__=='__main__':
    Path(sys.argv[4]).write_bytes(extract(*(Path(value).read_bytes() for value in sys.argv[1:4])))
