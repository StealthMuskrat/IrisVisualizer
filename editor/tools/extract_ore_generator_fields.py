"""Bind ore controls to native constructor defaults, material/range semantics and terrain dispatch."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact

def extract(ore,band,palette,biome,region,dimension,biome_ores,bands,terrain):
    sources=[ore,band,palette,biome,region,dimension,biome_ores,bands,terrain]
    o,r,p,b,g,d,bo,bs,t=[raw.decode('utf-8') for raw in sources]
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text):raise ValueError('Changed ore contract: '+contract)
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for name,raw in zip(['ore','range','palette','biome','region','dimension','biomeOres','bands','terrain'],sources):values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    for field in ['palette','chanceStyle','generateSurface','threshold','range']:
        declaration=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private [\w<>]+ '+field+r'\s*=\s*([^;]+);',o)
        if not declaration:raise ValueError('Changed ore field '+field)
        values[field+'.description']=json.loads(declaration.group(1))
    require(o,'@NoArgsConstructor','private IrisMaterialPalette palette = new IrisMaterialPalette().qclear();','private IrisGeneratorStyle chanceStyle = new IrisGeneratorStyle(NoiseStyle.STATIC);',
            'private boolean generateSurface = false;','private double threshold = 0.5;','private IrisRange range = new IrisRange(30, 80);',
            '@MinNumber(0) @MaxNumber(1) @Description("Noise cutoff: a block becomes ore when the sampled noise is at or below this value, so higher means more ore. 0 disables, 1 replaces everything in range.") private double threshold = 0.5;',
            'if (threshold <= 0 || !range.contains(y)) { return null; }','if (palette.getPalette().isEmpty()) { return null; }',
            'if (chance(rng, data).sample(x, y, z) > threshold) { return null; }','return palette.get(rng, x, y, z, data);')
    require(r,'@NoArgsConstructor','private double min = 16;','private double max = 32;','return v >= min && v <= max;')
    require(p,'private IrisGeneratorStyle style = NoiseStyle.STATIC.style();','private double zoom = 5;','private KList<IrisBlockData> palette = new KList<IrisBlockData>().qadd(new IrisBlockData("STONE"));')
    for parent in [b,g,d]:require(parent,'private KList<IrisOreGenerator> ores = new KList<>();')
    require(b,'return IrisBiomeOres.generateOres(this, x, y, z, rng, data, surface);')
    for parent in [g,d,bo]:
        require(parent,'if (oreGenerator.isGenerateSurface() == surface) { filtered.add(oreGenerator); }',
                'NativeBlockState ore = oreGenerator.generate(x, y, z, rng, data); if (ore != null) { return ore; }')
    require(bs,'int minY = (int) Math.ceil(min);','int maxY = (int) Math.floor(max);',
            'if (generator.getRange().contains(y)) { scratch[count++] = generator; }',
            'for (IrisOreGenerator candidate : candidates) { NativeBlockState ore = candidate.generate(x, y, z, rng, data); if (ore != null) { return ore; } }')
    for phase in ['Surface','Underground']:
        require(t,'if (biome'+phase+'Ores.contains(y)) { ore = biome'+phase+'Ores.generate(realX, y, realZ, rng, data); } if (ore == null && region'+phase+'Ores.contains(y)) { ore = region'+phase+'Ores.generate(realX, y, realZ, rng, data); } if (ore == null && dimension'+phase+'Ores.contains(y)) { ore = dimension'+phase+'Ores.generate(realX, y, realZ, rng, data); } return ore;')
    require(t,'NativeBlockState ore = hasSurfaceOres ? surfaceOre(realX, i, realZ) : null; if (ore != null) { h.setRaw(xf, i, zf, ore); continue; }')
    values.update({'palette.contextDefault':'empty','chanceStyle.default':'STATIC','generateSurface.default':'false','threshold.default':'0.5','threshold.minimum':'0','threshold.maximum':'1',
                   'range.context.min':'30','range.context.max':'80','range.class.min':'16','range.class.max':'32','range.policy':'inclusiveLocalY','surface.policy':'exclusivePassLists','order.policy':'firstNonNull'})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__':Path(sys.argv[10]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:10])))
