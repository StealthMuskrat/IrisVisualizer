"""Extract primitive surface-river controls without materializing styled ranges or materials."""
import hashlib
import json
import re
import sys
from decimal import Decimal
from pathlib import Path

FIELDS={
    'root':['enabled'],
    'sources':['density','minimumElevation','minimumPerTile','minimumSpacing'],
    'channel':['sink','maximumIncision','roughness','roughnessWavelength','springWidthRatio','springLength','smoothingRadius','outlineMinimumRatio','outlineMaximumRatio','springExtraDepth'],
    'banks':['shoreWidth','blendSlope','minimumBlendWidth','maximumBlendWidth','exposeCutStrata','shoreRise','blendBaseWidth'],
    'flow':['cascadeRun','waterfallMinimumDrop','waterfallThalwegFraction','plungeBasinMinimumDrop','plungeBasinLengthRatio','plungeBasinDepth'],
    'mouths':['flareRatio','maximumOceanApron','inletLength','inletDepth','maximumIncision','inletCourseFraction','inletRampSlope'],
}
INTS={
    'sources':{'minimumElevation','minimumPerTile','minimumSpacing'},
    'channel':{'sink','maximumIncision','roughnessWavelength','springLength','smoothingRadius'},
    'banks':{'minimumBlendWidth','maximumBlendWidth'},
    'flow':{'cascadeRun','waterfallMinimumDrop','plungeBasinMinimumDrop','plungeBasinDepth'},
    'mouths':{'maximumOceanApron','inletLength','inletDepth','maximumIncision'},
}
CLASSES={'sources':'IrisSurfaceRiverSourceConfig','channel':'IrisSurfaceRiverChannelConfig','banks':'IrisSurfaceRiverBankConfig','flow':'IrisSurfaceRiverFlowConfig','mouths':'IrisRiverMouthConfig'}


def extract(rivers,surface,sources,channel,banks,flow,mouths):
    raws=dict(rivers=rivers,root=surface,sources=sources,channel=channel,banks=banks,flow=flow,mouths=mouths)
    texts={name:raw.decode('utf-8') for name,raw in raws.items()}
    if any('@NoArgsConstructor' not in text for text in texts.values()):
        raise ValueError('Changed surface-river constructor defaults')
    values=dict(irisRevision='c3359e0a16438b1bb8468901c6171dcefdf63f7f')
    for name,raw in raws.items():
        values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    for name,kind,parent in [('surface','IrisSurfaceRiverConfig','rivers')]+[(name,kind,'root') for name,kind in CLASSES.items()]:
        declaration=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private '+kind+' '+name+r' = new '+kind+r'\(\);',texts[parent])
        if not declaration:
            raise ValueError('Changed surface-river parent default: '+name)
        values[name+'.default']='{}'
        values[name+'.description']=json.loads(declaration.group(1))
    for section,names in FIELDS.items():
        for name in names:
            kind='boolean' if name in ['enabled','exposeCutStrata'] else 'int' if name in INTS.get(section,set()) else 'double'
            item=re.search(r'((?:    @[^\n]+\n)+)    private '+kind+' '+name+r' = ([^;]+);',texts[section])
            if not item:
                raise ValueError('Changed surface-river primitive type: '+section+'.'+name)
            annotations,default=item.groups()
            help_text=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)',annotations)
            if not help_text:
                raise ValueError('Missing surface-river field description')
            prefix=section+'.'+name
            values[prefix+'.type']=kind
            values[prefix+'.description']=json.loads(help_text.group(1))
            if kind=='boolean':
                if default not in ['true','false']:
                    raise ValueError('Changed literal surface-river boolean default')
                values[prefix+'.default']=default
            else:
                values[prefix+'.default']=str(int(default)) if kind=='int' else str(Decimal(default.removesuffix('D')))
                for annotation,key in [('MinNumber','minimum'),('MaxNumber','maximum')]:
                    bound=re.search('@'+annotation+r'\(([^)]+)\)',annotations)
                    if not bound:
                        raise ValueError('Missing surface-river bound: '+prefix)
                    values[prefix+'.'+key]=str(Decimal(bound.group(1).removesuffix('D')))
    return ''.join(key+'='+value.replace('\\','\\\\').replace('\n','\\n').replace('\r','\\r')+'\n' for key,value in values.items()).encode('ascii')


if __name__=='__main__':
    Path(sys.argv[8]).write_bytes(extract(*(Path(value).read_bytes() for value in sys.argv[1:8])))
