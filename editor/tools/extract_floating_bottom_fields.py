"""Audit nested underside layer declaration, mode/fallback rules and native consumers."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact


def extract(entry,modifier,layer,slope):
    e,m,l,s=(raw.decode('utf-8') for raw in [entry,modifier,layer,slope])
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text): raise ValueError('Changed floating bottom palette contract: '+contract)
    require(e,'@NoArgsConstructor');require(l,'@NoArgsConstructor')
    match=re.search(r'@ArrayType\(min = (\d+), type = IrisBiomePaletteLayer.class\)\s+@Description\(("(?:[^"\\]|\\.)*")\)\s+private KList<IrisBiomePaletteLayer> bottomPalette = new KList<>\(\);',e)
    if not match or match.group(1)!='1': raise ValueError('Changed bottom palette list declaration')
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f','list.default':'[]','list.annotatedMinimum':'1','list.description':json.loads(match.group(2))}
    for name,raw in [('entry',entry),('modifier',modifier),('layer',layer),('slope',slope)]:values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    require(m,'if (entry == null || entry.getBottomPaletteMode() != FloatingBottomPaletteMode.CUSTOM || entry.getBottomPalette() == null || entry.getBottomPalette().isEmpty()) { return null; }',
            'return generatePaletteLayers(dimension, entry.getBottomPalette(), wx, wz, random.nextParallelRNG(0xB0770B), paletteDepth, data, complex);',
            'double layerDepth = heightGenerator.fit(layer.getMinHeight(), layer.getMaxHeight(), wx / layer.getZoom(), wz / layer.getZoom());',
            'IrisSlopeClip slopeClip = layer.getSlopeCondition();',
            'if (slopeClip != null && !slopeClip.isDefault() && complex != null && !slopeClip.isValid(complex.getSlopeStream().get(wx, wz))) { layerDepth = 0; }',
            'if (layerDepth <= 0) { continue; }',
            'generated.add(layer.get(random.nextParallelRNG(i + j), (wx + j) / layer.getZoom(), j, (wz - j) / layer.getZoom(), data));',
            'if (mode == FloatingBottomPaletteMode.CUSTOM && bottomDepth < topDepth) { if (bottomBlocks != null && !bottomBlocks.isEmpty()) { return paletteBlock(bottomBlocks, bottomDepth, fallbackSolid); } return paletteBlock(topBlocks, bottomDepth, fallbackSolid); }')
    require(l,'private KList<IrisBlockData> palette = new KList<IrisBlockData>().qadd(new IrisBlockData("GRASS_BLOCK"));',
            'private IrisGeneratorStyle style = NoiseStyle.STATIC.style();','private IrisSlopeClip slopeCondition = new IrisSlopeClip();')
    require(s,'public boolean isDefault() { return minimumSlope <= 0 && maximumSlope >= 10; }')
    values.update({'mode':'CUSTOM','selection':'bottomDepthLessThanTopDepth','emptyFallback':'topPaletteAtBottomDepth','nullList':'emptyFallback','nullSlope':'noGate','block.default':'GRASS_BLOCK','style.default':'STATIC'})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__': Path(sys.argv[5]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:5])))
