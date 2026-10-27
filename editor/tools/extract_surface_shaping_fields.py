"""Extract bounded erosion/bed/excavation fields and exact erosion enum declarations."""
import hashlib
import json
import re
import sys
from decimal import Decimal
from pathlib import Path
from extract_cave_placement_fields import enum_values

FIELDS={
    'erosion':['enabled','smoothingRadius','thalwegFraction','blendCurve','bedNoise','terraceSteps','cliffFraction'],
    'bed':['allowGravityBlocks','padding'],
    'banks.excavation':['maximumDepth','maximumWidth','maximumVolumePerBlock'],
}
INTS={'smoothingRadius','terraceSteps','padding','maximumDepth','maximumWidth','maximumVolumePerBlock'}
ENUMS={'style':'IrisRiverBlendStyle','bedProfile':'IrisRiverBedProfile'}


def extract(surface,banks,erosion,bed,excavation,blend,profile):
    raws=dict(surface=surface,banks=banks,erosion=erosion,bed=bed,excavation=excavation,blend=blend,profile=profile)
    texts={name:raw.decode('utf-8') for name,raw in raws.items()}
    if any('@NoArgsConstructor' not in texts[name] for name in ['surface','banks','erosion','bed','excavation']):
        raise ValueError('Changed surface shaping constructor defaults')
    values=dict(irisRevision='c3359e0a16438b1bb8468901c6171dcefdf63f7f')
    for name,raw in raws.items():
        values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    for section,name,kind,parent in [('erosion','erosion','IrisSurfaceRiverErosionConfig','surface'),('bed','bed','IrisSurfaceRiverBedConfig','surface'),('banks.excavation','excavation','IrisRiverExcavationConfig','banks')]:
        declaration=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private '+kind+' '+name+r' = new '+kind+r'\(\);',texts[parent])
        if not declaration:
            raise ValueError('Changed shaping parent default: '+section)
        values[section+'.default']='{}'
        values[section+'.description']=json.loads(declaration.group(1))
    for section,names in FIELDS.items():
        text=texts['excavation' if section=='banks.excavation' else section]
        for name in names:
            kind='boolean' if name in ['enabled','allowGravityBlocks'] else 'int' if name in INTS else 'double'
            item=re.search(r'((?:    @[^\n]+\n)+)    private '+kind+' '+name+r' = ([^;]+);',text)
            if not item:
                raise ValueError('Changed shaping primitive type: '+section+'.'+name)
            annotations,default=item.groups()
            help_text=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)',annotations)
            if not help_text:
                raise ValueError('Missing shaping description')
            prefix=section+'.'+name
            values[prefix+'.type']=kind
            values[prefix+'.description']=json.loads(help_text.group(1))
            if kind=='boolean':
                if default not in ['true','false']:
                    raise ValueError('Changed shaping boolean default')
                values[prefix+'.default']=default
            else:
                values[prefix+'.default']=str(int(default)) if kind=='int' else str(Decimal(default.removesuffix('D')))
                for annotation,key in [('MinNumber','minimum'),('MaxNumber','maximum')]:
                    bound=re.search('@'+annotation+r'\(([^)]+)\)',annotations)
                    if not bound:
                        raise ValueError('Missing shaping bound: '+prefix)
                    values[prefix+'.'+key]=str(Decimal(bound.group(1).removesuffix('D')))
    for name,kind in ENUMS.items():
        declaration=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private '+kind+' '+name+' = '+kind+r'\.([A-Z][A-Z0-9_]*);',texts['erosion'])
        choices=enum_values(blend if name=='style' else profile,kind)
        if not declaration or declaration.group(2) not in choices:
            raise ValueError('Changed erosion enum type/default: '+name)
        prefix='erosion.'+name
        values[prefix+'.type']='enum'
        values[prefix+'.description']=json.loads(declaration.group(1))
        values[prefix+'.default']=declaration.group(2)
        values[prefix+'.choices']=','.join(choices)
        values.update((prefix+'.'+choice+'.description',help_text) for choice,help_text in choices.items())
    return ''.join(key+'='+value.replace('\\','\\\\').replace('\n','\\n').replace('\r','\\r')+'\n' for key,value in values.items()).encode('ascii')


if __name__=='__main__':
    Path(sys.argv[8]).write_bytes(extract(*(Path(value).read_bytes() for value in sys.argv[1:8])))
