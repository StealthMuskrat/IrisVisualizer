"""Audit surface channel range helpers, constructor order and existing-object class defaults."""
import hashlib
import json
import re
import sys
from decimal import Decimal
from pathlib import Path
from extract_cave_threshold_fields import help_text

FIELDS=['width','depth']


def extract(channel,styled_range,style):
    source,ranges,styles=(raw.decode('utf-8') for raw in [channel,styled_range,style])
    if '@NoArgsConstructor' not in source or '@NoArgsConstructor' not in ranges or '@AllArgsConstructor' not in ranges:
        raise ValueError('Changed channel/styled-range constructors')
    if re.findall(r'^    private \w+ (\w+) = ',ranges,re.MULTILINE)!=['min','max','style']:
        raise ValueError('Changed styled-range constructor field order')
    helper=re.search(r'private static IrisStyledRange range\(double min, double max, double zoom\)\s*\{([^}]+)\}',source)
    expected='IrisGeneratorStylestyle=newIrisGeneratorStyle(NoiseStyle.IRIS).zoomed(zoom);returnnewIrisStyledRange(min,max,style);'
    if not helper or re.sub(r'\s+','',helper.group(1))!=expected:
        raise ValueError('Changed surface channel range helper')
    if not re.search(r'public IrisGeneratorStyle\(NoiseStyle s\)\s*\{\s*this.style = s;\s*\}',styles):
        raise ValueError('Changed style enum constructor')
    if not re.search(r'public IrisGeneratorStyle zoomed\(double z\)\s*\{\s*this.zoom = z;\s*return this;\s*\}',styles):
        raise ValueError('Changed contextual zoom method')
    values=dict(irisRevision='c3359e0a16438b1bb8468901c6171dcefdf63f7f')
    for name,raw in [('channel',channel),('styledRange',styled_range),('style',style)]:
        values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    literal=r'([-+0-9.eED]+)'
    for name in FIELDS:
        declaration=re.search(r'((?:    @[^\n]+\n)+)    private IrisStyledRange '+name+r' = range\('+literal+r',\s*'+literal+r',\s*'+literal+r'\);',source)
        if not declaration:
            raise ValueError('Changed channel range declaration: '+name)
        values[name+'.description']=help_text(declaration.group(1))
        for index,member in enumerate(['min','max','zoom'],2):
            values[name+'.'+member+'.default']=str(Decimal(declaration.group(index).removesuffix('D')))
        values[name+'.style.default']='IRIS'
    for name in ['min','max']:
        declaration=re.search(r'((?:    @[^\n]+\n)+)    private double '+name+' = '+literal+r';',ranges)
        if not declaration:
            raise ValueError('Changed range class bound: '+name)
        values[name+'.description']=help_text(declaration.group(1))
        values[name+'.default']=str(Decimal(declaration.group(2).removesuffix('D')))
    declaration=re.search(r'((?:    @[^\n]+\n)+)    private IrisGeneratorStyle style = new IrisGeneratorStyle\(NoiseStyle\.([A-Z][A-Z0-9_]*)\);',ranges)
    if not declaration:
        raise ValueError('Changed range class style default')
    values['style.description']=help_text(declaration.group(1))
    values['style.default']=declaration.group(2)
    return ''.join(key+'='+value.replace('\\','\\\\').replace('\n','\\n').replace('\r','\\r')+'\n' for key,value in values.items()).encode('ascii')


if __name__=='__main__':
    Path(sys.argv[4]).write_bytes(extract(*(Path(value).read_bytes() for value in sys.argv[1:4])))
