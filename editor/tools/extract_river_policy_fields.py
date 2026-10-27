"""Extract nullable river overrides, exact enums and owner defaults from pinned source."""
import hashlib
import json
import re
import sys
from decimal import Decimal
from pathlib import Path
from extract_cave_placement_fields import enum_values

FIELDS = ['surfaceSourceDensity','surfaceSourceSpacing','surfaceTributaries','surfaceInlandOutlets','surfaceCoastalOutlets','surfaceMinimumCourseLength','surfaceMaximumIncision','placement','routing','outletAdmission','widthMultiplier','depthMultiplier','incisionMultiplier','routingMultiplier','bankMultiplier','shoreBiomeWidth','shoreWidth','erosion','confined']
ENUMS = {'placement':'IrisRiverPlacementMode','routing':'IrisRiverRoutingMode'}
INTEGERS = {'surfaceSourceSpacing','surfaceTributaries','surfaceInlandOutlets','surfaceCoastalOutlets','surfaceMinimumCourseLength','surfaceMaximumIncision'}
BOOLEANS = {'outletAdmission','erosion','confined'}


def extract(policy, owners, placement, routing):
    source = policy.decode('utf-8')
    if '@NoArgsConstructor' not in source or set(owners) != {'dimensions','regions','biomes'}:
        raise ValueError('Changed river policy constructor/owner coverage')
    values = dict(irisRevision='c3359e0a16438b1bb8468901c6171dcefdf63f7f',policySourceSha256=hashlib.sha256(policy).hexdigest())
    for context, raw in owners.items():
        owner = re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private IrisRiverPolicy riverPolicy = ([^;]+);',raw.decode('utf-8'))
        expected = 'new IrisRiverPolicy()' if context=='dimensions' else 'null'
        if not owner or owner.group(2)!=expected:
            raise ValueError('Changed river policy owner default: '+context)
        values[context+'.sourceSha256'] = hashlib.sha256(raw).hexdigest()
        values[context+'.description'] = json.loads(owner.group(1))
        values[context+'.default'] = '{}' if context=='dimensions' else 'null'
    for name in FIELDS:
        field = re.search(r'((?:    @[^\n]+\n)+)    private (\w+) '+name+r' = null;',source)
        if not field:
            raise ValueError('Changed nullable river field: '+name)
        annotations, kind = field.groups()
        expected = ENUMS.get(name,'Integer' if name in INTEGERS else 'Boolean' if name in BOOLEANS else 'Double')
        if kind != expected:
            raise ValueError('Changed river scalar type: '+name)
        description = re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)',annotations)
        if not description:
            raise ValueError('Missing river field description')
        values[name+'.type'] = kind
        values[name+'.default'] = 'null'
        values[name+'.description'] = json.loads(description.group(1))
        for annotation, key in [('MinNumber','minimum'),('MaxNumber','maximum')]:
            bound = re.search('@'+annotation+r'\(([^)]+)\)',annotations)
            if bound:
                if kind not in ['Double','Integer']:
                    raise ValueError('Bounds on nonnumeric river field')
                values[name+'.'+key] = str(Decimal(bound.group(1).removesuffix('D')))
    for name, raw in [('placement',placement),('routing',routing)]:
        choices = enum_values(raw,ENUMS[name])
        values[name+'.sourceSha256'] = hashlib.sha256(raw).hexdigest()
        values[name+'.choices'] = ','.join(choices)
        values.update((name+'.'+choice+'.description',help_text) for choice,help_text in choices.items())
    return ''.join(key+'='+value.replace('\\','\\\\').replace('\n','\\n').replace('\r','\\r')+'\n' for key,value in values.items()).encode('ascii')


if __name__ == '__main__':
    owners = dict(zip(['dimensions','regions','biomes'],(Path(value).read_bytes() for value in sys.argv[2:5])))
    Path(sys.argv[7]).write_bytes(extract(Path(sys.argv[1]).read_bytes(),owners,Path(sys.argv[5]).read_bytes(),Path(sys.argv[6]).read_bytes()))
