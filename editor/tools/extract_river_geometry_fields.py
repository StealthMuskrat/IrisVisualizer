"""Extract dimension meander/surface shape controls and verify nullable channel fallbacks."""
import hashlib
import json
import re
import sys
from decimal import Decimal
from pathlib import Path

MEANDERS=['primaryWavelength','detailWavelength','primaryStrength','detailStrength','maximumOffsetRatio','smoothingPasses','maximumTurnDegrees']
SURFACE=['bedRoundness','bedRoughness','wallRoughness','roughnessWavelength','radialBase','radialMinimum','radialMaximum','primaryLobeStrength','detailLobeStrength','ceilingRoughness']
MEANDER_INTS={'primaryWavelength','detailWavelength','smoothingPasses'}
NULLABLE={'bedRoughness':'Double','wallRoughness':'Double','roughnessWavelength':'Integer'}


def extract(rivers, geometry, meanders, surface):
    river, geo, meander, shape=(raw.decode('utf-8') for raw in [rivers,geometry,meanders,surface])
    if any('@NoArgsConstructor' not in text for text in [river,geo,meander,shape]):
        raise ValueError('Changed geometry constructor defaults')
    values=dict(irisRevision='c3359e0a16438b1bb8468901c6171dcefdf63f7f')
    for key,raw in [('rivers',rivers),('geometry',geometry),('meanders',meanders),('surface',surface)]:
        values[key+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    for name,text,kind in [('geometry',river,'IrisRiverGeometryConfig'),('meanders',geo,'IrisRiverMeanderConfig'),('surface',geo,'IrisSurfaceRiverShapeConfig')]:
        parent=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private '+kind+' '+name+r' = new '+kind+r'\(\);',text)
        if not parent:
            raise ValueError('Changed geometry parent default: '+name)
        values[name+'.default']='{}'
        values[name+'.description']=json.loads(parent.group(1))
    for section,text,names in [('meanders',meander,MEANDERS),('surface',shape,SURFACE+['aspectMinimum','aspectRange'])]:
        for name in names:
            item=re.search(r'((?:    @[^\n]+\n)+)    private (int|double|Double|Integer) '+name+r' = ([^;]+);',text)
            expected=NULLABLE.get(name,'double') if section=='surface' else 'int' if name in MEANDER_INTS else 'double'
            if not item or item.group(2)!=expected:
                raise ValueError('Changed geometry scalar type: '+section+'.'+name)
            annotations,kind,default=item.groups()
            help_text=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)',annotations)
            if not help_text:
                raise ValueError('Missing geometry help')
            prefix=section+'.'+name
            values[prefix+'.type']=kind
            values[prefix+'.description']=json.loads(help_text.group(1))
            if kind in ['Double','Integer']:
                if default!='null' or 'Null uses surface.channel.' not in values[prefix+'.description']:
                    raise ValueError('Changed surface channel fallback contract')
                values[prefix+'.default']='null'
            else:
                values[prefix+'.default']=str(int(default)) if kind=='int' else str(Decimal(default.removesuffix('D')))
            for annotation,key in [('MinNumber','minimum'),('MaxNumber','maximum')]:
                bound=re.search('@'+annotation+r'\(([^)]+)\)',annotations)
                if bound: values[prefix+'.'+key]=str(Decimal(bound.group(1).removesuffix('D')))
            if name in ['aspectMinimum','aspectRange']:
                if 'only grotto chambers read it.' not in values[prefix+'.description']:
                    raise ValueError('Changed surface-only field classification')
                values[prefix+'.readOnlyReason']='Source states that only grotto chambers consume this shared-record field.'
    return ''.join(key+'='+value.replace('\\','\\\\').replace('\n','\\n').replace('\r','\\r')+'\n' for key,value in values.items()).encode('ascii')


if __name__=='__main__':
    Path(sys.argv[5]).write_bytes(extract(*(Path(value).read_bytes() for value in sys.argv[1:5])))
