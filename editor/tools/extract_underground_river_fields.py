"""Extract independent underground primitives and contextual ranges without sampling noise."""
import hashlib
import json
import re
import sys
from decimal import Decimal
from pathlib import Path

ROOT=['enabled','connectToExistingCaves','mouthLevelingDistance','tributaries','minimumRockCover','minimumFloorCover','wideningSources']
SOURCES=['density','minimumPerTile','minimumSpacing']
RANGES=['fluidLevel','channelWidth','depth','headroom']


def extract(rivers,underground,sources):
    texts={name:raw.decode('utf-8') for name,raw in [('rivers',rivers),('root',underground),('sources',sources)]}
    if any('@NoArgsConstructor' not in text for text in texts.values()):
        raise ValueError('Changed underground constructor defaults')
    values=dict(irisRevision='c3359e0a16438b1bb8468901c6171dcefdf63f7f')
    for name,raw in [('rivers',rivers),('underground',underground),('sources',sources)]:
        values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    for name,kind,parent in [('underground','IrisUndergroundRiverConfig','rivers'),('sources','IrisUndergroundRiverSourceConfig','root')]:
        item=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private '+kind+' '+name+r' = new '+kind+r'\(\);',texts[parent])
        if not item:
            raise ValueError('Changed underground parent default: '+name)
        values[name+'.default']='{}'
        values[name+'.description']=json.loads(item.group(1))
    for section,names in [('root',ROOT),('sources',SOURCES)]:
        for name in names:
            kind='boolean' if name in ['enabled','connectToExistingCaves'] else 'double' if name=='density' else 'int'
            item=re.search(r'((?:    @[^\n]+\n)+)    private '+kind+' '+name+r' = ([^;]+);',texts[section])
            if not item:
                raise ValueError('Changed underground primitive type: '+section+'.'+name)
            annotations,default=item.groups()
            help_text=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)',annotations)
            if not help_text:
                raise ValueError('Missing underground help')
            prefix=section+'.'+name
            values[prefix+'.type']=kind
            values[prefix+'.description']=json.loads(help_text.group(1))
            if kind=='boolean':
                if default not in ['true','false']:
                    raise ValueError('Changed underground literal boolean default')
                values[prefix+'.default']=default
            else:
                values[prefix+'.default']=str(int(default)) if kind=='int' else str(Decimal(default.removesuffix('D')))
                for annotation,key in [('MinNumber','minimum'),('MaxNumber','maximum')]:
                    bound=re.search('@'+annotation+r'\(([^)]+)\)',annotations)
                    if not bound:
                        raise ValueError('Missing underground bound')
                    values[prefix+'.'+key]=str(Decimal(bound.group(1).removesuffix('D')))
    helper=re.search(r'private static IrisStyledRange range\(double min, double max, double zoom\)\s*\{([^}]+)\}',texts['root'])
    expected='returnnewIrisStyledRange(min,max,newIrisGeneratorStyle(NoiseStyle.IRIS).zoomed(zoom));'
    if not helper or re.sub(r'\s+','',helper.group(1))!=expected:
        raise ValueError('Changed underground range helper')
    literal=r'([-+0-9.eED]+)'
    for name in RANGES:
        item=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private IrisStyledRange '+name+r' = range\('+literal+r',\s*'+literal+r',\s*'+literal+r'\);',texts['root'])
        if not item:
            raise ValueError('Changed underground range declaration: '+name)
        prefix='underground.'+name
        values[prefix+'.description']=json.loads(item.group(1))
        for index,member in enumerate(['min','max','zoom'],2):
            values[prefix+'.'+member+'.default']=str(Decimal(item.group(index).removesuffix('D')))
        values[prefix+'.style.default']='IRIS'
    return ''.join(key+'='+value.replace('\\','\\\\').replace('\n','\\n').replace('\r','\\r')+'\n' for key,value in values.items()).encode('ascii')


if __name__=='__main__':
    Path(sys.argv[4]).write_bytes(extract(*(Path(value).read_bytes() for value in sys.argv[1:4])))
