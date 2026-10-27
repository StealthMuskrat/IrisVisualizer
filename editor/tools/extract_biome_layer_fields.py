"""Audit inline biome layer defaults/annotations and the pinned per-context generator bindings."""
import hashlib
import json
import re
import sys
from decimal import Decimal
from pathlib import Path
from extract_river_material_fields import compact


def extract(biome,layer,slope,array,generator,block,validator):
    b,l,s,a,g,bd,v=(raw.decode('utf-8') for raw in [biome,layer,slope,array,generator,block,validator])
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text): raise ValueError('Changed biome layer contract: '+contract)
    for text in [b,l,s,bd]: require(text,'@NoArgsConstructor')
    require(a,'int min() default 0;');require(l,'@AllArgsConstructor');require(s,'@AllArgsConstructor')
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f','list.minimum':'0'}
    for name,raw in [('biome',biome),('layer',layer),('slope',slope),('array',array),('generator',generator),('block',block),('validator',validator)]: values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    for name in ['layers','seaLayers','caveCeilingLayers']:
        default='new KList<IrisBiomePaletteLayer>().qadd(new IrisBiomePaletteLayer())' if name=='layers' else 'new KList<>()'
        field=re.search(r'@ArrayType\(type = IrisBiomePaletteLayer.class\)\s+@Description\(("(?:[^"\\]|\\.)*")\)\s+private KList<IrisBiomePaletteLayer> '+name+' = '+re.escape(default)+r';',b)
        if not field: raise ValueError('Changed biome layer list declaration/default: '+name)
        values[name+'.description']=json.loads(field.group(1));values[name+'.defaultCount']='1' if name=='layers' else '0'
    require(b,'@Required @ArrayType(type = IrisBiomePaletteLayer.class)')
    for name,kind,text in [('minHeight','int',l),('maxHeight','int',l),('zoom','double',l),('minimumSlope','double',s),('maximumSlope','double',s)]:
        field=re.search(r'((?:    @[^\n]+\n|[ \t]*\n)+)    private '+kind+' '+name+r' = ([^;]+);',text)
        if not field: raise ValueError('Changed layer/slope scalar declaration: '+name)
        annotations,default=field.groups();help_text=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)',annotations)
        minimum=re.search(r'@MinNumber\(([^)]+)\)',annotations);maximum=re.search(r'@MaxNumber\(([^)]+)\)',annotations)
        if not help_text or not minimum or name!='zoom' and not maximum: raise ValueError('Missing layer scalar annotations')
        values[name+'.default']=str(Decimal(default.removesuffix('D')));values[name+'.minimum']=str(Decimal(minimum.group(1)));values[name+'.description']=json.loads(help_text.group(1))
        if maximum: values[name+'.maximum']=str(Decimal(maximum.group(1)))
    style=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private IrisGeneratorStyle style = NoiseStyle.([A-Z_]+).style\(\);',l)
    palette=re.search(r'@Required\s+@ArrayType\(min = (\d+), type = IrisBlockData.class\)\s+@Description\("[^"\n]*"\)\s+private KList<IrisBlockData> palette = new KList<IrisBlockData>\(\).qadd\(new IrisBlockData\(("[^"\n]*")\)\);',l)
    if not style or not palette or palette.group(1)!='1': raise ValueError('Changed layer style/palette declarations')
    values['style.default']=style.group(2);values['style.description']=json.loads(style.group(1));values['palette.defaultBlock']=json.loads(palette.group(2))
    require(l,'private IrisSlopeClip slopeCondition = new IrisSlopeClip();','double localZoom = zoom;','double scaledX = x / localZoom;','double scaledY = y / localZoom;','double scaledZ = z / localZoom;',
            'ignored -> style.create(new RNG(generatorSeed), data, engine)')
    require(bd,'public IrisBlockData(String b) { this.block = b; }')
    require(s,'public boolean isDefault() { return minimumSlope <= 0 && maximumSlope >= 10; }',
            'public boolean isValid(double slope) { if (isDefault()) { return true; } return !(minimumSlope > slope) && !(maximumSlope < slope); }')
    require(g,'IrisSlopeClip sc = layer.getSlopeCondition();','if (!sc.isDefault())','if (!sc.isValid(surfaceSlope))',
            'hgen.fit(layer.getMinHeight(), layer.getMaxHeight(), wx / zoom, wz / zoom)')
    for name in ['generateSeaLayers','generateCeilingLayers']:
        method=re.search(r'static KList<NativeBlockState> '+name+r'\(.*?\n    \}',g,re.DOTALL)
        if not method or 'getSlopeCondition()' in method.group(0): raise ValueError('Changed sea/ceiling slope behavior')
        require(method.group(0),'hgen.fit(layer.getMinHeight(), layer.getMaxHeight(), wx / zoom, wz / zoom)')
    require(g,'getHeightGenerators(biome.getLayerCeilingHeightGenerators(), ceilingLayers, 7235, random, rdata)')
    require(v,'validateLayerArray(biome, "layers", biomeKey, blockingErrors);','validateLayerArray(biome, "caveCeilingLayers", biomeKey, blockingErrors);',
            'if (!(layer instanceof JSONObject) && !(layer instanceof String))')
    values['ceiling.rule']='independentCeilingGenerators';values['slope.context']='surfaceOnly'
    return ''.join(key+'='+value.replace('\\','\\\\').replace('\n','\\n').replace('\r','\\r')+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__': Path(sys.argv[8]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:8])))
