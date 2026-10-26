"""Extract bounded biome/editor fields from pinned official source."""
import hashlib
import json
import re
import sys
from pathlib import Path
lines = ['irisRevision=c3359e0a16438b1bb8468901c6171dcefdf63f7f']
for path, prefix, names in [(Path(sys.argv[1]), '', ['rarity', 'caveMinDepthBelowSurface', 'oreDepositFrequencyMultiplier', 'oreDepositSizeMultiplier', 'lockLayers', 'mergeFloatingChildBiomes', 'lockLayersMax', 'childShrinkFactor']),
                            (Path(sys.argv[2]), 'generators.', ['min', 'max'])]:
    source = path.read_bytes()
    lines.append((prefix or 'biome.') + 'sourceSha256=' + hashlib.sha256(source).hexdigest())
    text = source.decode('utf-8')
    for name in names:
        match = re.search(r'((?:    @[^\n]+\n)+)    private (int|double|boolean) ' + name + r' = ([^;]+);', text)
        if not match: raise ValueError('Changed official field: ' + name)
        annotations, kind, default = match.groups()
        description = json.loads(re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)', annotations).group(1))
        fields = dict(type=kind, description=description)
        if kind == 'boolean':
            if default not in ('true', 'false'): raise ValueError('Changed boolean default')
            fields['default'] = default
        else:
            fields['default'] = float(default.removesuffix('D'))
            for annotation, key in [('MinNumber', 'minimum'), ('MaxNumber', 'maximum')]:
                bound = re.search('@' + annotation + r'\(([^)]+)\)', annotations)
                if bound: fields[key] = float(bound.group(1))
        for key, value in fields.items():
            escaped = str(value).replace('\\', '\\\\').replace('\n', '\\n').replace('\r', '\\r')
            lines.append(prefix + name + '.' + key + '=' + escaped)
biome = Path(sys.argv[1]).read_text()
link = Path(sys.argv[2]).read_text()
array = re.search(r'@ArrayType\(min = (\d+), type = IrisBiomeGeneratorLink.class\)\s+@Description\(("(?:[^"\\]|\\.)*")\)\s+private KList<IrisBiomeGeneratorLink> generators = new KList<IrisBiomeGeneratorLink>\(\).qadd\(new IrisBiomeGeneratorLink\(\)\);', biome)
reference = re.search(r'@RegistryListResource\(IrisGenerator.class\)\s+@Description\(("(?:[^"\\]|\\.)*")\)\s+private String generator = ("(?:[^"\\]|\\.)*");', link)
if not array or not reference: raise ValueError('Changed official biome generator list/reference default')
for key, value in [('generators.minimumEntries',array.group(1)),('generators.defaultGenerator',json.loads(reference.group(2))),('generators.description',json.loads(array.group(2))),('generators.referenceDescription',json.loads(reference.group(1)))]:
    lines.append(key+'='+value.replace('\\','\\\\').replace('\n','\\n').replace('\r','\\r'))
children = re.search(r'((?:    @[^\n]+\n)+)    private KList<String> children = new KList<>\(\);', biome)
if not children or '@RegistryListResource(IrisBiome.class)' not in children.group(1):
    raise ValueError('Changed child biome list/default')
annotations = children.group(1)
minimum = re.search(r'@ArrayType\(min = (\d+), type = String.class\)',annotations)
description = re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)',annotations)
if not minimum or not description: raise ValueError('Changed child biome annotations')
for key,value in [('children.default','[]'),('children.required',str('@Required' in annotations).lower()),('children.minimumEntries',minimum.group(1)),('children.description',json.loads(description.group(1)))]:
    lines.append(key+'='+value.replace('\\','\\\\').replace('\n','\\n').replace('\r','\\r'))
Path(sys.argv[3]).write_bytes(('\n'.join(lines) + '\n').encode('ascii'))
