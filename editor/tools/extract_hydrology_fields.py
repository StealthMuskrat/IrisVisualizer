"""Extract bounded physical river-routing settings without creating hydrology or running Iris."""
import hashlib
import json
import re
import sys
from decimal import Decimal
from pathlib import Path

ROUTING = ['tileSize','sampleSpacing','maximumRouteLength','minimumSurfaceCourseLength','minimumUndergroundCourseLength','maximumOutletsPerTile','maximumCoastalOutletsPerTile','oceanOutlets','valleyPreference','uphillPenalty','slopePenalty','confluenceAttraction','lengthPreference','tributaries']
INTEGERS = {'tileSize','sampleSpacing','maximumRouteLength','minimumSurfaceCourseLength','minimumUndergroundCourseLength','maximumOutletsPerTile','maximumCoastalOutletsPerTile','tributaries'}


def extract(dimension, hydrology, rivers, routing):
    owner, hydro, river, route = (raw.decode('utf-8') for raw in [dimension,hydrology,rivers,routing])
    if any('@NoArgsConstructor' not in text for text in [owner,hydro,river,route]):
        raise ValueError('Changed hydrology constructor contract')
    values = dict(irisRevision='c3359e0a16438b1bb8468901c6171dcefdf63f7f')
    for key, raw in [('dimension',dimension),('hydrology',hydrology),('rivers',rivers),('routing',routing)]:
        values[key+'SourceSha256'] = hashlib.sha256(raw).hexdigest()
    for name, text, kind in [('hydrology',owner,'IrisHydrology'),('rivers',hydro,'IrisRiverHydrology'),('routing',river,'IrisRiverRoutingConfig')]:
        field = re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private '+kind+' '+name+r' = new '+kind+r'\(\);',text)
        if not field:
            raise ValueError('Changed default hydrology object: '+name)
        values[name+'.description'] = json.loads(field.group(1))
        values[name+'.default'] = '{}'
    enabled = re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private boolean enabled = false;',river)
    if not enabled:
        raise ValueError('Changed disabled river default')
    values['rivers.enabled.description'] = json.loads(enabled.group(1))
    values['rivers.enabled.default'] = 'false'
    for name in ROUTING:
        field = re.search(r'((?:    @[^\n]+\n)+)    private (int|double|boolean) '+name+r' = ([^;]+);',route)
        expected = 'int' if name in INTEGERS else 'boolean' if name=='oceanOutlets' else 'double'
        if not field or field.group(2)!=expected:
            raise ValueError('Changed routing scalar: '+name)
        annotations, kind, default = field.groups()
        description = re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)',annotations)
        if not description:
            raise ValueError('Missing routing description')
        prefix = 'routing.'+name
        values[prefix+'.type'] = kind
        values[prefix+'.description'] = json.loads(description.group(1))
        if kind=='boolean':
            if default not in ['true','false']:
                raise ValueError('Changed routing boolean initializer')
            values[prefix+'.default'] = default
        else:
            values[prefix+'.default'] = str(int(default)) if kind=='int' else str(Decimal(default.removesuffix('D')))
            for annotation, key in [('MinNumber','minimum'),('MaxNumber','maximum')]:
                bound = re.search('@'+annotation+r'\(([^)]+)\)',annotations)
                if bound:
                    values[prefix+'.'+key] = str(Decimal(bound.group(1).removesuffix('D')))
    return ''.join(key+'='+value.replace('\\','\\\\').replace('\n','\\n').replace('\r','\\r')+'\n' for key,value in values.items()).encode('ascii')


if __name__ == '__main__':
    Path(sys.argv[5]).write_bytes(extract(*(Path(value).read_bytes() for value in sys.argv[1:5])))
