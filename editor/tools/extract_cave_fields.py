"""Extract bounded cave-profile scalars/styles and verify defaults without resolving profiles."""
import hashlib
import json
import re
import sys
from decimal import Decimal
from pathlib import Path

FIELDS = ['enabled','verticalEdgeFade','verticalEdgeFadeStrength','baseWeight','detailWeight','warpStrength','thresholdBias','sampleStep','adaptiveSampling','adaptiveSampleStep','adaptiveThresholdMargin','surfaceClearance','allowSurfaceBreak','surfaceBreakNoiseThreshold','surfaceBreakDepth','surfaceBreakThresholdBoost','objectMinDepthBelowSurface','anchorScanStep','anchorSearchAttempts','allowFluid','fluidMinDepthBelowSurface','fluidRequiresFloor','allowLava']
STYLES = ['baseDensityStyle','detailDensityStyle','warpStyle','surfaceBreakStyle']


def extract(profile, owners, style):
    text = profile.decode('utf-8')
    style_text = style.decode('utf-8')
    if '@NoArgsConstructor' not in text or set(owners) != {'dimensions','regions','biomes'}:
        raise ValueError('Changed cave profile constructor or owner coverage')
    if not re.search(r'public IrisGeneratorStyle\(NoiseStyle s\)\s*\{\s*this.style = s;\s*\}',style_text) or not re.search(r'public IrisGeneratorStyle zoomed\(double z\)\s*\{\s*this.zoom = z;\s*return this;\s*\}',style_text):
        raise ValueError('Changed contextual style constructor/zoom setter')
    values = dict(irisRevision='c3359e0a16438b1bb8468901c6171dcefdf63f7f',profileSourceSha256=hashlib.sha256(profile).hexdigest(),styleSourceSha256=hashlib.sha256(style).hexdigest(),profileDefault='{}')
    for group, raw in owners.items():
        parent = re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private IrisCaveProfile caveProfile = new IrisCaveProfile\(\);',raw.decode('utf-8'))
        if not parent:
            raise ValueError('Changed cave profile owner default: '+group)
        values[group+'.sourceSha256'] = hashlib.sha256(raw).hexdigest()
        values[group+'.description'] = json.loads(parent.group(1))
    for name in FIELDS:
        match = re.search(r'((?:    @[^\n]+\n)+)    private (boolean|int|double) '+name+r' = ([^;]+);',text)
        if not match:
            raise ValueError('Changed cave field: '+name)
        annotations, kind, default = match.groups()
        help_text = re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)',annotations)
        if not help_text:
            raise ValueError('Missing cave field description')
        values[name+'.type'] = kind
        values[name+'.description'] = json.loads(help_text.group(1))
        if kind == 'boolean':
            if default not in ('true','false'):
                raise ValueError('Changed cave boolean default')
            values[name+'.default'] = default
        else:
            values[name+'.default'] = str(int(default)) if kind == 'int' else str(Decimal(default.removesuffix('D')))
            for annotation, key in [('MinNumber','minimum'),('MaxNumber','maximum')]:
                bound = re.search('@'+annotation+r'\(([^)]+)\)',annotations)
                if bound:
                    values[name+'.'+key] = str(Decimal(bound.group(1).removesuffix('D')))
    for name in STYLES:
        declaration = re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private IrisGeneratorStyle '+name+r' = ([^;]+);',text)
        if not declaration:
            raise ValueError('Changed cave style field: '+name)
        expression = declaration.group(2)
        factory = re.fullmatch(r'NoiseStyle\.([A-Z][A-Z0-9_]*)\.style\(\)',expression)
        constructor = re.fullmatch(r'new IrisGeneratorStyle\(NoiseStyle\.([A-Z][A-Z0-9_]*)\)(?:\.zoomed\(([-+0-9.eED]+)\))?',expression)
        if not factory and not constructor:
            raise ValueError('Unsupported cave style default: '+name)
        values[name+'.default'] = (factory or constructor).group(1)
        values[name+'.description'] = json.loads(declaration.group(1))
        if constructor and constructor.group(2):
            values[name+'.zoomOverride'] = str(Decimal(constructor.group(2).removesuffix('D')))
    return ''.join(key+'='+value.replace('\\','\\\\').replace('\n','\\n').replace('\r','\\r')+'\n' for key,value in values.items()).encode('ascii')


if __name__ == '__main__':
    owners = dict(zip(['dimensions','regions','biomes'],(Path(value).read_bytes() for value in sys.argv[2:5])))
    Path(sys.argv[5]).write_bytes(extract(Path(sys.argv[1]).read_bytes(),owners,Path(sys.argv[6]).read_bytes()))
