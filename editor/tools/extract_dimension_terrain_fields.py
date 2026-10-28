"""Pin terrain overlays and external transform descriptors without running native providers/noise."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact

def extract(dimension,shaped,complex_source,context,transform,registry):
    sources=[dimension,shaped,complex_source,context,transform,registry];d,s,c,t,f,r=[raw.decode('utf-8') for raw in sources]
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for name,raw in zip(['dimension','shaped','complex','context','transform','registry'],sources):values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text):raise ValueError('Changed dimension terrain contract: '+contract)
    require(d,'@ArrayType(min = 1, type = IrisShapedGeneratorStyle.class)', 'private KList<IrisShapedGeneratorStyle> overlayNoise = new KList<>();','private IrisTerrainTransform terrainTransform;')
    match=re.search(r'@Required\s*@Description\(("(?:[^"\\]|\\.)*")\)\s*private IrisGeneratorStyle generator = new IrisGeneratorStyle\(NoiseStyle.IRIS\);',s)
    if not match:raise ValueError('Changed overlay generator declaration')
    values['overlay.generator.description']=json.loads(match.group(1));values['overlay.generator.default']='IRIS'
    for field in ['min','max']:
        match=re.search(r'@Required\s*@MinNumber\((-?\d+)\)\s*@MaxNumber\((-?\d+)\)\s*@Description\(("(?:[^"\\]|\\.)*")\)\s*private int '+field+r' = (\d+);',s)
        if not match or match.group(1,2,4)!=('-2032','2032','0'):raise ValueError('Changed overlay bound '+field)
        values.update({'overlay.'+field+'.minimum':match.group(1),'overlay.'+field+'.maximum':match.group(2),'overlay.'+field+'.description':json.loads(match.group(3)),'overlay.'+field+'.default':match.group(4)})
    require(s,'return generator.create(rng, data).fitDouble(min, max, x, z);','return min == max || getGenerator().isFlat();')
    require(c,'value += style.get(rng, getData(), x, z);',
            'minimum += Math.min(style.getMin(), style.getMax());','maximum += Math.max(style.getMin(), style.getMax());',
            'return Math.max(Math.min(getInterpolatedHeight(engine, x, z, seed) + fluidHeight + overlayStream.get(x, z), engine.getHeight()), 0);',
            'TerrainTransformer transformer = engine.getDimension().getTerrainTransform() == null ? null : TerrainTransformRegistry.discover().resolve(engine.getDimension().getTerrainTransform());')
    require(t,'value += style.get(rng, dimensionData, x, z);','Math.min(interpolatedHeight + fluidHeight + overlayStream.get(x, z), localHeight)',
            'return mappedTerrainHeight(imageMapRuntime, proceduralHeight, x, z);')
    for field in ['id','version']:
        match=re.search(r'@Required\s*@Description\(("(?:[^"\\]|\\.)*")\)\s*private String '+field+r';',f)
        if not match:raise ValueError('Changed provider identity field '+field)
        values['transform.'+field+'.description']=json.loads(match.group(1))
    match=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s*private KMap<String, String> settings = new KMap<>\(\);',f)
    if not match:raise ValueError('Changed provider settings declaration')
    values['transform.settings.description']=json.loads(match.group(1))
    require(r,'if (descriptor == null) { return null; }',
            'ProviderIdentity identity = new ProviderIdentity(descriptor.getId(), descriptor.getVersion());',
            'TerrainTransformProvider provider = providers.get(identity);','if (provider == null)',
            'Map<String, String> settings = Map.copyOf(Objects.requireNonNull( descriptor.getSettings(), "terrain transform settings"));',
            'provider.create(settings)', 'if (radius < 0 || radius > 16)',
            'if (value == null || value.isBlank())', 'return value;',
            'private record ProviderIdentity(String id, String version)',
            'id = requireIdentity(id, "Terrain transform provider ID");',
            'version = requireIdentity(version, "Terrain transform provider version");',
            'TerrainTransformProvider.class, TerrainTransformProvider.class.getClassLoader()')
    identity=re.search(r'private static String requireIdentity\(.*?\n    \}',r,re.DOTALL)
    if not identity or '.trim()' in identity.group(0) or '.toLowerCase(' in identity.group(0):raise ValueError('Provider identity normalization changed')
    values.update({'overlay.policy':'sumFittedOverlays','overlay.bounds.policy':'equalOrReversedAllowed','transform.policy':'exactIdentityStringMap','transform.optional.policy':'nullDisables'})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__':Path(sys.argv[7]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:7])))
