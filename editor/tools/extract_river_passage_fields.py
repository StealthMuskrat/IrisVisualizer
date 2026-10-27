"""Extract primitive underground/grotto/drop controls and prove grotto-only aspect scope."""
import hashlib
import json
import re
import sys
from decimal import Decimal
from pathlib import Path

CHANNEL=['bedRoundness','bedRoughness','wallRoughness','roughnessWavelength','radialBase','radialMinimum','radialMaximum','primaryLobeStrength','detailLobeStrength','ceilingRoughness','aspectMinimum','aspectRange']
DROPS=['cascadeRunPerBlock','cascadeExponent','maximumCascadeStep','flowWidthRatio','maximumFlowDepth','basinWidthRatio','maximumBasinDepth','undergroundCascadeRunPerBlock']
DROP_INTS={'cascadeRunPerBlock','maximumCascadeStep','maximumFlowDepth','maximumBasinDepth','undergroundCascadeRunPerBlock'}


def extract(geometry, channel, drops):
    geo, passage, drop=(raw.decode('utf-8') for raw in [geometry,channel,drops])
    if any('@NoArgsConstructor' not in text for text in [geo,passage,drop]):
        raise ValueError('Changed passage constructor defaults')
    values=dict(irisRevision='c3359e0a16438b1bb8468901c6171dcefdf63f7f')
    for key,raw in [('geometry',geometry),('channel',channel),('drops',drops)]:
        values[key+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    for section,kind in [('underground','IrisRiverChannelShapeConfig'),('grottos','IrisRiverChannelShapeConfig'),('drops','IrisRiverDropShapeConfig')]:
        parent=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private '+kind+' '+section+r' = new '+kind+r'\(\);',geo)
        if not parent:
            raise ValueError('Changed passage parent default: '+section)
        values[section+'.default']='{}'
        values[section+'.description']=json.loads(parent.group(1))
    for section,text,names in [('underground',passage,CHANNEL),('grottos',passage,CHANNEL),('drops',drop,DROPS)]:
        for name in names:
            kind='int' if (name in DROP_INTS if section=='drops' else name=='roughnessWavelength') else 'double'
            item=re.search(r'((?:    @[^\n]+\n)+)    private '+kind+' '+name+r' = ([^;]+);',text)
            if not item:
                raise ValueError('Changed passage primitive field: '+section+'.'+name)
            annotations,default=item.groups()
            help_text=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)',annotations)
            if not help_text:
                raise ValueError('Missing passage help')
            prefix=section+'.'+name
            values[prefix+'.type']=kind
            values[prefix+'.description']=json.loads(help_text.group(1))
            values[prefix+'.default']=str(int(default)) if kind=='int' else str(Decimal(default.removesuffix('D')))
            for annotation,key in [('MinNumber','minimum'),('MaxNumber','maximum')]:
                bound=re.search('@'+annotation+r'\(([^)]+)\)',annotations)
                if not bound:
                    raise ValueError('Missing passage bound: '+prefix)
                values[prefix+'.'+key]=str(Decimal(bound.group(1).removesuffix('D')))
            if name in ['aspectMinimum','aspectRange']:
                if 'Applies to grottos only.' not in values[prefix+'.description']:
                    raise ValueError('Changed grotto-only aspect scope')
                if section=='underground':
                    values[prefix+'.readOnlyReason']='Source states this field applies to grottos only.'
    return ''.join(key+'='+value.replace('\\','\\\\').replace('\n','\\n').replace('\r','\\r')+'\n' for key,value in values.items()).encode('ascii')


if __name__=='__main__':
    Path(sys.argv[4]).write_bytes(extract(*(Path(value).read_bytes() for value in sys.argv[1:4])))
