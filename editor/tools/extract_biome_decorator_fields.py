"""Bind decorator authoring to native defaults, weighted/filter consumers and placement gates."""
import hashlib
import json
import re
import sys
import textwrap
from pathlib import Path
from extract_river_material_fields import compact
from field_coverage import declared_fields

DEFAULTS={'variance':('IrisGeneratorStyle','NoiseStyle.STATIC.style()'),'forcePlace':('boolean','false'),'forceBlock':('IrisBlockData',None),
          'whitelist':('KList<IrisBlockData>',None),'blacklist':('KList<IrisBlockData>',None),'slopeCondition':('IrisSlopeClip','new IrisSlopeClip()'),
          'absoluteMaxStack':('int','30'),'style':('IrisGeneratorStyle','NoiseStyle.STATIC.style()'),'heightVariance':('IrisGeneratorStyle','NoiseStyle.STATIC.style()'),
          'partOf':('IrisDecorationPart','IrisDecorationPart.NONE'),'stackMin':('int','1'),'stackMax':('int','1'),'scaleStack':('boolean','false'),
          'chance':('double','0.1'),'palette':('KList<IrisBlockData>','new KList<IrisBlockData>().qadd(new IrisBlockData("grass"))'),
          'topPalette':('KList<IrisBlockData>','new KList<>()'),'topThreshold':('double','1.0')}
BOUNDS={'stackMin':('1','2032'),'stackMax':('1','2032'),'chance':('0','1'),'topThreshold':('0.01','1.0')}

def extract(decorator,parts,biome,core,surface,slope,block,style):
    sources=[decorator,parts,biome,core,surface,slope,block,style]
    d,p,b,c,s,sl,bl,st=[raw.decode('utf-8') for raw in sources]
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text):raise ValueError('Changed decorator contract: '+contract)
    if declared_fields(d)!={name:kind for name,(kind,_) in DEFAULTS.items()}:raise ValueError('Changed decorator field inventory')
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for name,raw in zip(['decorator','parts','biome','core','surface','slope','block','style'],sources):values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    previous=0
    for name,(kind,default) in DEFAULTS.items():
        declaration=re.search(r'^    private '+re.escape(kind)+' '+name+r'(?: = ([^;]+))?;',d,re.MULTILINE)
        if not declaration or compact(declaration.group(1) or '')!=compact(default or ''):raise ValueError('Changed decorator default '+name)
        annotations=d[previous:declaration.start()];previous=declaration.end()
        descriptions=re.findall(r'@Description\(("""[\s\S]*?"""|"(?:[^"\\]|\\.)*")\)',annotations)
        if not descriptions:raise ValueError('Missing decorator help '+name)
        raw=descriptions[-1];description=textwrap.dedent(raw[3:-3]).strip() if raw.startswith('"""') else json.loads(raw)
        values[name+'.type']=kind;values[name+'.description']=description
        values[name+'.default']='null' if default is None else 'STATIC' if kind=='IrisGeneratorStyle' else 'NONE' if kind=='IrisDecorationPart' else '{}' if kind=='IrisSlopeClip' else '[]' if kind.startswith('KList') else default
        if name in BOUNDS:
            low=re.search(r'@MinNumber\(([^)]+)\)',annotations);high=re.search(r'@MaxNumber\(([^)]+)\)',annotations)
            if not low or not high or (low.group(1),high.group(1))!=BOUNDS[name]:raise ValueError('Changed decorator bounds '+name)
            values[name+'.minimum'],values[name+'.maximum']=BOUNDS[name]
    choices=re.findall(r'^    (NONE|SHORE_LINE|SEA_SURFACE|SEA_FLOOR|CEILING),',p,re.MULTILINE)
    if choices!=['NONE','SHORE_LINE','SEA_SURFACE','SEA_FLOOR','CEILING']:raise ValueError('Changed decoration parts')
    require(b,'private KList<IrisDecorator> decorators = new KList<>();','for (IrisDecorator d : decorators) { staging.computeIfAbsent(d.getPartOf(), k -> new KList<>()).add(d); }')
    require(d,'@Snippet("decorator")','private IrisGeneratorStyle variance = NoiseStyle.STATIC.style();',
            'if (stackMin == stackMax) { return stackMin; }','heightVariance.create(rng.nextParallelRNG(getBlockData(data).size() + stackMax + stackMin), data)',
            'if (getBlockData(data).isEmpty()) { return false; } double xx = x / style.getZoom(); double zz = z / style.getZoom(); return getGenerator(rng, data).fitDouble(0D, 1D, xx, zz) <= chance;',
            'for (IrisBlockData i : palette) { NativeBlockState bx = i.getBlockData(data); if (bx != null) { for (int n = 0; n < i.getWeight(); n++) { blockData.add(bx); } } }',
            'for (IrisBlockData i : topPalette) { NativeBlockState bx = i.getBlockData(data); if (bx != null) { for (int n = 0; n < i.getWeight(); n++) { blockDataTops.add(bx); } } }',
            'if (arr.length == 0) { return pickBlockData(rng, data, x, z); }',
            'if (list == null) { return new NativeBlockState[0]; }', 'resolved[i] = list.get(i).getBlockData(data);',
            'return getStackMax() > 1;')
    filter_method=d[d.index('private static NativeBlockState[] resolvePalette('):d.index('public NativeBlockState pickBlockData(')]
    if 'getWeight(' in filter_method:raise ValueError('Decorator host filters now consume weights')
    require(c,'IrisDecorator[] bucket = biome.getDecoratorBucket(part);','if (d.passesChanceGate(gRNG, realX, realZ, data)) { count++; if (count == 1 || colRng.nextInt(count) == 0) { picked = d; } }',
            'private static boolean matchesPalette(NativeBlockState[] palette, NativeBlockState surface) { if (surface == null) { return false; } for (int i = 0; i < palette.length; i++) { if (palette[i] == surface || surface.matches(palette[i])) { return true; } } return false; }',
            'if (decorator.getForceBlock() != null) { if (caveSkipFluid && B.isFluid(bdx)) { return; }',
            'if (!decorator.isForcePlace()) { if (decorator.getWhitelist() != null && !matchesPalette(decorator.getWhitelistArray(irisData), bdx)) { return; } if (decorator.getBlacklist() != null && matchesPalette(decorator.getBlacklistArray(irisData), bdx)) { return; } }',
            'stack = Math.min((int) Math.ceil((double) max * ((double) stack / 100)), decorator.getAbsoluteMaxStack());',
            'boolean top = threshold >= decorator.getTopThreshold();')
    require(s,'if (decorator.isForcePlace() || decorator.getSlopeCondition().isDefault()) { return true; }','return decorator.getSlopeCondition().isValid(slope);')
    require(sl,'private double minimumSlope = 0;','private double maximumSlope = 10;','return minimumSlope <= 0 && maximumSlope >= 10;')
    for axis in ['minimumSlope','maximumSlope']:
        if not re.search(r'@MinNumber\(0\)\s+@MaxNumber\(1024\)\s+@Description\("[^"\n]*"\)\s+private double '+axis,sl):raise ValueError('Changed slope bounds')
    weight=re.search(r'@MinNumber\(1\)\s+@MaxNumber\(1000\)\s+@Description\("[^"\n]*"\)\s+private int weight = 1;',bl)
    if not weight:raise ValueError('Changed block weights')
    require(st,'private NoiseStyle style = NoiseStyle.FLAT;')
    values.update({'palette.defaultBlock':'grass','partOf.choices':','.join(choices),'palette.policy':'weightedMainAndTop','filters.policy':'nullUnrestrictedEmptyWhitelistForbid',
                   'selection.policy':'reservoirAfterChance','absoluteMaxStack.minimum':'1','absoluteMaxStack.maximum':'2032','absoluteMaxStack.boundsPolicy':'editorPositiveCap'})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__':Path(sys.argv[9]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:9])))
