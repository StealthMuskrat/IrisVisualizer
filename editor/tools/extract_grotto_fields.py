"""Extract primitive/enum grotto settings and prove the nullable cliff-height fallback meaning."""
import hashlib
import json
import re
import sys
from decimal import Decimal
from pathlib import Path
from extract_cave_placement_fields import enum_values

COASTAL=['enabled','poolLevel','horizontalRadius','verticalRadius','headroom','maximumVolume','cliffMinimumHeight','cliffSlopeFactor']
INLAND=['enabled','connectSurfaceRivers','horizontalRadius','verticalRadius','headroom','maximumVolume']


def extract(rivers,grottos,coastal,inland,levels):
    raws=dict(rivers=rivers,grottos=grottos,coastal=coastal,inland=inland,levels=levels)
    texts={name:raw.decode('utf-8') for name,raw in raws.items()}
    if any('@NoArgsConstructor' not in texts[name] for name in ['rivers','grottos','coastal','inland']):
        raise ValueError('Changed grotto constructor defaults')
    values=dict(irisRevision='c3359e0a16438b1bb8468901c6171dcefdf63f7f')
    for name,raw in raws.items():
        values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    for name,kind,parent in [('grottos','IrisRiverGrottoConfig','rivers'),('coastal','IrisCoastalRiverGrottoConfig','grottos'),('inland','IrisInlandRiverGrottoConfig','grottos')]:
        declaration=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private '+kind+' '+name+r' = new '+kind+r'\(\);',texts[parent])
        if not declaration:
            raise ValueError('Changed grotto parent default: '+name)
        values[name+'.default']='{}'
        values[name+'.description']=json.loads(declaration.group(1))
    for section,names in [('coastal',COASTAL),('inland',INLAND)]:
        for name in names:
            kind='IrisGrottoPoolLevel' if name=='poolLevel' else 'boolean' if name in ['enabled','connectSurfaceRivers'] else 'Integer' if name=='cliffMinimumHeight' else 'double' if name=='cliffSlopeFactor' else 'int'
            item=re.search(r'((?:    @[^\n]+\n)+)    private '+kind+' '+name+r' = ([^;]+);',texts[section])
            if not item:
                raise ValueError('Changed grotto scalar type: '+section+'.'+name)
            annotations,default=item.groups()
            help_text=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)',annotations)
            if not help_text:
                raise ValueError('Missing grotto help')
            p=section+'.'+name
            values[p+'.type']='enum' if name=='poolLevel' else kind
            values[p+'.description']=json.loads(help_text.group(1))
            if kind=='boolean':
                if default not in ['true','false']:
                    raise ValueError('Changed literal grotto boolean default')
                values[p+'.default']=default
            elif kind=='IrisGrottoPoolLevel':
                choices=enum_values(levels,kind)
                token=default.removeprefix(kind+'.')
                if not default.startswith(kind+'.') or token not in choices:
                    raise ValueError('Changed grotto pool enum default')
                values[p+'.default']=token
                values[p+'.choices']=','.join(choices)
                values.update((p+'.'+choice+'.description',help_text) for choice,help_text in choices.items())
            else:
                if kind=='Integer':
                    if default!='null' or 'Null uses the larger of 4 and verticalRadius.' not in values[p+'.description']:
                        raise ValueError('Changed coastal cliff-height fallback contract')
                    values[p+'.default']='null'
                    values[p+'.fallback']='Uses the larger of 4 and verticalRadius; the effective value is not resolved here.'
                else:
                    values[p+'.default']=str(int(default)) if kind=='int' else str(Decimal(default.removesuffix('D')))
                for annotation,key in [('MinNumber','minimum'),('MaxNumber','maximum')]:
                    bound=re.search('@'+annotation+r'\(([^)]+)\)',annotations)
                    if not bound:
                        raise ValueError('Missing grotto scalar bound: '+p)
                    values[p+'.'+key]=str(Decimal(bound.group(1).removesuffix('D')))
    return ''.join(key+'='+value.replace('\\','\\\\').replace('\n','\\n').replace('\r','\\r')+'\n' for key,value in values.items()).encode('ascii')


if __name__=='__main__':
    Path(sys.argv[6]).write_bytes(extract(*(Path(value).read_bytes() for value in sys.argv[1:6])))
