"""Extract bounded floating entry authoring metadata without running island generation."""
import hashlib
import json
import re
import sys
from decimal import Decimal
from pathlib import Path
from extract_river_material_fields import compact

SCALARS=['rarity','footprintThreshold','edgeTaperWidth','edgeTaperExponent','edgeTaperVariationAmplitude','maxTopHeight','topShapeAmp','bottomExponent','maxThickness','wallWarpAmplitude','carveThreshold','objectShrinkFactor']
PAIRS=['minHeightAboveSurface','maxHeightAboveSurface','bottomDepthMin','bottomDepthMax']
OPTIONAL=['minAbsoluteY','maxAbsoluteY','localFluidHeight']
TOGGLES=['inheritDecorators','inheritObjects']
STRINGS=['biome','carving','fluidBlock']
ENUMS={'topShapeMode':'TopShapeMode','bottomPaletteMode':'FloatingBottomPaletteMode','topObjectMode':'OverrideMode','bottomObjectMode':'OverrideMode'}


def extract(biome,entry,edge,sample,top,bottom,override):
    b,e,d,s=(raw.decode('utf-8') for raw in [biome,entry,edge,sample])
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text): raise ValueError('Changed floating entry contract: '+contract)
    require(b,'@NoArgsConstructor');require(e,'@NoArgsConstructor','@AllArgsConstructor')
    declaration=re.search(r'@ArrayType\(min = (\d+), type = IrisFloatingChildBiomes.class\)\s+@Description\(("(?:[^"\\]|\\.)*")\)\s+private KList<IrisFloatingChildBiomes> floatingChildBiomes = new KList<>\(\);',b)
    if not declaration or declaration.group(1)!='1': raise ValueError('Changed floating list declaration')
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f','list.default':'[]','list.annotatedMinimum':declaration.group(1),'list.description':json.loads(declaration.group(2))}
    for name,raw in [('biome',biome),('entry',entry),('edge',edge),('sample',sample),('top',top),('bottom',bottom),('override',override)]: values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    constants={name:value for name,value in re.findall(r'public static final (?:int|double) (\w+) = ([0-9.]+D?);',d)}
    for field in SCALARS+PAIRS+OPTIONAL+TOGGLES+STRINGS+list(ENUMS):
        match=re.search(r'((?:    @[^\n]+\n)+)    private ([\w<>]+) '+field+r' = ([^;]+);',e)
        if not match: raise ValueError('Changed floating field declaration: '+field)
        annotations,kind,token=match.groups();help_text=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)',annotations)
        if not help_text: raise ValueError('Missing floating field help')
        values[field+'.description']=json.loads(help_text.group(1));values[field+'.type']=kind
        if field in SCALARS+PAIRS:
            if kind not in ['int','double']: raise ValueError('Changed numeric type')
            if token.startswith('FloatingIslandEdgeProfile.'): token=constants[token.split('.')[1]]
            values[field+'.default']=str(Decimal(token.removesuffix('D')))
            for annotation,key in [('MinNumber','minimum'),('MaxNumber','maximum')]:
                bound=re.search('@'+annotation+r'\(([^)]+)\)',annotations)
                if not bound: raise ValueError('Missing floating numeric bounds')
                values[field+'.'+key]=str(Decimal(bound.group(1)))
        elif field in OPTIONAL:
            if kind!='Integer' or token!='null': raise ValueError('Changed optional integer default')
            values[field+'.default']='null'
        elif field in TOGGLES:
            if kind!='boolean' or token!='true': raise ValueError('Changed inheritance toggle')
            values[field+'.default']=token
        elif field in STRINGS:
            if kind!='String': raise ValueError('Changed string type')
            values[field+'.default']=json.loads(token)
        else:
            if kind!=ENUMS[field] or not token.startswith(kind+'.'): raise ValueError('Changed enum default')
            values[field+'.default']=token.split('.')[1]
    for name,raw in [('TopShapeMode',top),('FloatingBottomPaletteMode',bottom),('OverrideMode',override)]:
        body=raw.decode().split('public enum '+name+' {',1)[1].rsplit('}',1)[0]
        pattern=r'\s*@Description\(("(?:[^"\\]|\\.)*")\)\s+([A-Z][A-Z_]*)(?:,|(?=\s*$))'
        entries=list(re.finditer(pattern,body));remaining=re.sub(pattern,'',body)
        if not entries or remaining.strip() or len(set(m.group(2) for m in entries))!=len(entries): raise ValueError('Changed enum declaration')
        values[name+'.choices']=','.join(m.group(2) for m in entries)
        for match in entries: values[name+'.'+match.group(2)+'.description']=json.loads(match.group(1))
    require(e,'if (biome == null || biome.isBlank() || biome.equals(parent.getLoadKey())) { return parent; }',
            'IrisBiome loaded = data.getBiomeLoader().load(biome);','if (loaded == null) { return parent; }',
            'IrisDimensionCarvingEntry entry = dimension.getCarvingEntryIndex().get(carving);','return data.getBiomeLoader().load(carving);',
            'case INHERIT_ONLY -> surfaceObjects;','case REPLACE -> new KList<>(topObjectOverrides);','case MERGE, REPLACE -> bottomObjectOverrides;')
    require(s,'int minAlt = Math.max(0, entry.getMinHeightAboveSurface() - worldMin);',
            'int maxAlt = Math.max(minAlt, entry.getMaxHeightAboveSurface() - worldMin);',
            'int minDepth = Math.max(0, entry.getBottomDepthMin());','int maxDepth = Math.max(minDepth, entry.getBottomDepthMax());',
            'Integer minAbsoluteY = entry.getMinAbsoluteY();','Integer maxAbsoluteY = entry.getMaxAbsoluteY();',
            'botY = minAbsoluteY - worldMin;','topY = maxAbsoluteY - worldMin;',
            'int maxThickness = Math.max(1, entry.getMaxThickness());')
    require(d,'double widthLimit = Math.min(clampedWidth - MIN_WIDTH, MAX_WIDTH - clampedWidth);',
            'return Math.max(0.0D, Math.min(Math.min(MAX_VARIATION_AMPLITUDE, widthLimit), amplitude));')
    values.update({'height.context':'absoluteWorldY','pair.rule':'editorOrdered','nested.policy':'readOnly'})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__': Path(sys.argv[8]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:8])))
