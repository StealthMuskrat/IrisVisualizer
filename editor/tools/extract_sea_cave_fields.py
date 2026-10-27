"""Audit sea-cave annotations against pinned pack validation, spacing relation and compiler gating."""
import hashlib
import json
import re
import sys
from decimal import Decimal
from pathlib import Path

FIELDS=['enabled','maximumPerTile','minimumSpacing','minimumCoastHeight','depth','sweepJitterDegrees']


def extract(coastal,sea_caves,validator,compiler):
    coast,text,validation,compiled=(raw.decode('utf-8') for raw in [coastal,sea_caves,validator,compiler])
    if '@NoArgsConstructor' not in coast or '@NoArgsConstructor' not in text:
        raise ValueError('Changed sea-cave constructor defaults')
    parent=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private IrisSeaCaveConfig seaCaves = new IrisSeaCaveConfig\(\);',coast)
    radius=re.search(r'((?:    @[^\n]+\n)+)    private int horizontalRadius = (\d+);',coast)
    method=re.search(r'private static void validateGrotto\(.*?\n    \}',validation,re.DOTALL)
    if not parent or not radius or not method:
        raise ValueError('Changed sea-cave parent/radius/validator binding')
    native=method.group(0)
    compact=re.sub(r'\s+','',validation)
    for contract in ['privatestaticfinalIrisCoastalRiverGrottoConfigDEFAULT_COASTAL_GROTTO=newIrisCoastalRiverGrottoConfig();',
                     'if(seaCaves!=null){',
                     'inthorizontalRadius=integerValue(grotto,"horizontalRadius",DEFAULT_COASTAL_GROTTO.getHorizontalRadius());',
                     'intminimumSpacing=integerValue(seaCaves,"minimumSpacing",DEFAULT_COASTAL_GROTTO.getSeaCaves().getMinimumSpacing());',
                     'if(minimumSpacing<horizontalRadius*2)']:
        if contract not in compact:
            raise ValueError('Changed native sea-cave spacing contract')
    sea_method=re.search(r'private static HydrologyPlannerSettings.SeaCaves seaCaves\(IrisCoastalRiverGrottoConfig coastal\)\s*\{([^}]+)\}',compiled)
    expected='IrisSeaCaveConfigseaCaves=coastal.getSeaCaves();returnnewHydrologyPlannerSettings.SeaCaves(coastal.isEnabled()&&seaCaves.isEnabled(),seaCaves.getMaximumPerTile(),seaCaves.getMinimumSpacing(),seaCaves.getMinimumCoastHeight(),seaCaves.getDepth(),seaCaves.getSweepJitterDegrees());'
    if not sea_method or re.sub(r'\s+','',sea_method.group(1))!=expected:
        raise ValueError('Changed sea-cave compiler gating/field binding')
    values=dict(irisRevision='c3359e0a16438b1bb8468901c6171dcefdf63f7f')
    for name,raw in [('coastal',coastal),('seaCaves',sea_caves),('validator',validator),('compiler',compiler)]:
        values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    values['seaCaves.default']='{}'
    values['description']=json.loads(parent.group(1))
    values['radius.default']=radius.group(2)
    for section,annotations,names in [('radius',radius.group(1),['horizontalRadius'])]:
        call=re.search(r'validateOptionalIntegerRange\(path, grotto, "horizontalRadius",\s*([^,]+),\s*([^,]+), errors\)',native)
        if not call:
            raise ValueError('Missing native coastal radius bounds')
        for index,(annotation,key) in enumerate([('MinNumber','minimum'),('MaxNumber','maximum')],1):
            bound=re.search('@'+annotation+r'\(([^)]+)\)',annotations)
            if not bound or Decimal(bound.group(1))!=Decimal(call.group(index).strip().replace('_','')):
                raise ValueError('Coastal radius annotation/native bounds differ')
            values[section+'.'+key]=str(Decimal(bound.group(1)))
    for name in FIELDS:
        kind='boolean' if name=='enabled' else 'double' if name=='sweepJitterDegrees' else 'int'
        item=re.search(r'((?:    @[^\n]+\n)+)    private '+kind+' '+name+r' = ([^;]+);',text)
        if not item:
            raise ValueError('Changed sea-cave primitive type: '+name)
        annotations,default=item.groups()
        help_text=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)',annotations)
        if not help_text:
            raise ValueError('Missing sea-cave help')
        values[name+'.type']=kind
        values[name+'.description']=json.loads(help_text.group(1))
        if kind=='boolean':
            if default not in ['true','false'] or 'validateOptionalBoolean(seaCavePath, seaCaves, "enabled", errors)' not in native:
                raise ValueError('Changed sea-cave boolean default/check')
            values[name+'.default']=default
        else:
            values[name+'.default']=str(int(default)) if kind=='int' else str(Decimal(default.removesuffix('D')))
            check='Integer' if kind=='int' else 'Double'
            call=re.search(r'validateOptional'+check+r'Range\(seaCavePath, seaCaves, "'+name+r'",\s*([^,]+),\s*([^,]+), errors\)',native)
            if not call:
                raise ValueError('Missing native sea-cave bound check: '+name)
            for index,(annotation,key) in enumerate([('MinNumber','minimum'),('MaxNumber','maximum')],1):
                bound=re.search('@'+annotation+r'\(([^)]+)\)',annotations)
                actual=call.group(index).strip().replace('_','').removesuffix('D')
                if not bound or Decimal(bound.group(1).removesuffix('D'))!=Decimal(actual):
                    raise ValueError('Sea-cave annotation/native bounds differ: '+name)
                values[name+'.'+key]=str(Decimal(bound.group(1).removesuffix('D')))
    if 'Must be at least twice the coastal grotto horizontalRadius.' not in values['minimumSpacing.description']:
        raise ValueError('Changed documented sea-cave spacing rule')
    values['minimumSpacing.rule']='twiceCoastalRadius'
    values['enabled.rule']='coastalAndSeaEnabled'
    return ''.join(key+'='+value.replace('\\','\\\\').replace('\n','\\n').replace('\r','\\r')+'\n' for key,value in values.items()).encode('ascii')


if __name__=='__main__':
    Path(sys.argv[5]).write_bytes(extract(*(Path(value).read_bytes() for value in sys.argv[1:5])))
