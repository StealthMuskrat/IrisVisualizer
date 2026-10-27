"""Audit persisted derivative defaults and native scatter/structure fallback bindings."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact


def extract(biome, derivatives):
    b,d=(raw.decode('utf-8') for raw in [biome,derivatives])
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text): raise ValueError('Changed biome derivative contract: '+contract)
    require(b,'@NoArgsConstructor')
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f','biomeSourceSha256':hashlib.sha256(biome).hexdigest(),'derivativesSourceSha256':hashlib.sha256(derivatives).hexdigest()}
    for field in ['derivative','vanillaDerivative','biomeScatter','biomeSkyScatter']:
        kind='String' if field.endswith('erivative') else 'KList<String>'
        match=re.search(r'((?:    @[^\n]+\n)+)    private '+re.escape(kind)+' '+field+r' = ([^;]+);',b)
        if not match: raise ValueError('Changed derivative declaration: '+field)
        annotations,default=match.groups();description=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)',annotations)
        if not description: raise ValueError('Missing derivative help')
        require(annotations,'@RegistryListBiome')
        values[field+'.description']=json.loads(description.group(1))
        if kind=='String':
            expected='"minecraft:the_void"' if field=='derivative' else 'null'
            if default!=expected: raise ValueError('Changed derivative default')
            require(annotations,'@Required');values[field+'.default']='minecraft:the_void' if field=='derivative' else 'null'
        else:
            require(annotations,'@ArrayType(min = 1, type = String.class)')
            if default!='new KList<>()': raise ValueError('Changed scatter default')
            values[field+'.default']='[]';values[field+'.minimumEntries']='1'
    require(b,'return resolved == null ? getDerivative() : resolved;','return IrisBiomeDerivatives.getVanillaDerivativeKey(derivative, vanillaDerivative);',
            'return biomeScatterResolved.aquire(() -> IrisBiomeDerivatives.resolveBiomeKeys(biomeScatter));',
            'return biomeSkyScatterResolved.aquire(() -> IrisBiomeDerivatives.resolveBiomeKeys(biomeSkyScatter));',
            'return biomeStyle.createNoCache(new RNG(seed).nextParallelRNG(signature), loader);')
    require(d,'if (key == null || key.isBlank()) { return null; }','String trimmed = key.trim();',
            'return trimmed.indexOf(\':\') >= 0 ? trimmed : "minecraft:" + trimmed;',
            'return resolved == null ? namespacedBiomeKey(derivative) : resolved;',
            'if (biome.isSea() && !isVanillaSeaStructureBiome(key)) { return "minecraft:the_void"; }',
            'if (biome.isShore() && !isVanillaShoreStructureBiome(key)) { return "minecraft:beach"; }',
            'return key != null && (key.contains("ocean") || key.endsWith("river"));',
            'return key != null && (key.endsWith("beach") || key.endsWith("shore"));',
            'NamespacedKey namespacedKey = NamespacedKey.fromString(key);',
            'return namespacedKey == null ? null : RegistryUtil.lookup(Biome.class).get(namespacedKey);',
            'if (biomeScatter.isEmpty()) { return biome.getDerivative(); }',
            'if (biomeSkyScatter.isEmpty()) { return getGroundBiome(biome, rng, engine, x, y, z); }',
            'return biome.getBiomeGenerator(rng, engine).fit(biome.getBiomeScatterResolved(), x, y, z);',
            'return biome.getBiomeSkyScatterResolved().get(biome.getBiomeGenerator(rng, engine).fit(0, biomeSkyScatter.size() - 1, x, y, z));',
            'return engine.getData().physicalBiomeResourceKey(engine.getDimension(), logicalKey);')
    return ''.join(key+'='+value.replace('\\','\\\\').replace('\n','\\n').replace('\r','\\r')+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__': Path(sys.argv[3]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:3])))
