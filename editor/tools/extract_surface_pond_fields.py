"""Prove endpoint constructor defaults and preserve them with minimal explicit pond creation."""
import hashlib
import json
import re
import sys
from pathlib import Path

FIELDS=['enabled','minimumRadius','maximumRadius','depth']
ARGUMENTS=['minimumRadius','maximumRadius','depth']
CONTEXTS=['source','terminal']


def extract(surface,ponds,pond):
    surface_text,parents,text=(raw.decode('utf-8') for raw in [surface,ponds,pond])
    if any('@NoArgsConstructor' not in source for source in [surface_text,parents,text]):
        raise ValueError('Changed pond constructor defaults')
    constructor=re.search(r'public IrisSurfaceRiverPondConfig\(int minimumRadius, int maximumRadius, int depth\)\s*\{([^}]+)\}',text)
    expected=''.join('this.'+name+'='+name+';' for name in ARGUMENTS)
    if not constructor or re.sub(r'\s+','',constructor.group(1))!=expected:
        raise ValueError('Changed pond constructor assignments/order')
    parent=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private IrisSurfaceRiverPondsConfig ponds = new IrisSurfaceRiverPondsConfig\(\);',surface_text)
    if not parent:
        raise ValueError('Changed ponds container type/default')
    values=dict(irisRevision='c3359e0a16438b1bb8468901c6171dcefdf63f7f')
    for name,raw in [('surface',surface),('ponds',ponds),('pond',pond)]:
        values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    values['ponds.default']='{}'
    values['ponds.description']=json.loads(parent.group(1))
    defaults={}
    for name in FIELDS:
        kind='boolean' if name=='enabled' else 'int'
        item=re.search(r'((?:    @[^\n]+\n)+)    private '+kind+' '+name+r' = ([^;]+);',text)
        if not item:
            raise ValueError('Changed pond primitive type/default: '+name)
        annotations,default=item.groups()
        help_text=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)',annotations)
        if not help_text:
            raise ValueError('Missing pond help')
        values[name+'.type']=kind
        values[name+'.description']=json.loads(help_text.group(1))
        if kind=='boolean':
            if default not in ['true','false']:
                raise ValueError('Changed literal pond enabled default')
            defaults[name]=default
        else:
            defaults[name]=str(int(default))
            for annotation,key in [('MinNumber','minimum'),('MaxNumber','maximum')]:
                bound=re.search('@'+annotation+r'\(([^)]+)\)',annotations)
                if not bound:
                    raise ValueError('Missing pond bound: '+name)
                values[name+'.'+key]=str(int(bound.group(1)))
            if not int(values[name+'.minimum'])<=int(defaults[name])<=int(values[name+'.maximum']):
                raise ValueError('Pond class default outside bounds')
        values[name+'.default']=defaults[name]
    for context in CONTEXTS:
        declaration=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private IrisSurfaceRiverPondConfig '+context+r' = new IrisSurfaceRiverPondConfig\((-?\d+),\s*(-?\d+),\s*(-?\d+)\);',parents)
        if not declaration:
            raise ValueError('Changed pond endpoint constructor: '+context)
        values[context+'.description']=json.loads(declaration.group(1))
        contextual=dict(defaults)
        contextual.update(zip(ARGUMENTS,(str(int(token)) for token in declaration.groups()[1:])))
        overrides={}
        for name in FIELDS:
            values[context+'.'+name+'.default']=contextual[name]
            if name!='enabled':
                if not int(values[name+'.minimum'])<=int(contextual[name])<=int(values[name+'.maximum']):
                    raise ValueError('Pond contextual default outside bounds')
                if defaults[name]!=contextual[name]:
                    overrides[name]=int(contextual[name])
        values[context+'.createToken']=json.dumps(overrides,separators=(',',':'))
    return ''.join(key+'='+value.replace('\\','\\\\').replace('\n','\\n').replace('\r','\\r')+'\n' for key,value in values.items()).encode('ascii')


if __name__=='__main__':
    Path(sys.argv[4]).write_bytes(extract(*(Path(value).read_bytes() for value in sys.argv[1:4])))
