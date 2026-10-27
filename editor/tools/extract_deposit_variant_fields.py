"""Bind deposit variants and biome exterior-host overrides to pinned placement consumers."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact

def extract(variant,modifier,generator,biome_ores,dimension,region,biome):
    sources=[variant,modifier,generator,biome_ores,dimension,region,biome]
    v,m,g,bo,d,r,b=[raw.decode('utf-8') for raw in sources]
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text):raise ValueError('Changed variant/host contract: '+contract)
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for name,raw in zip(['variant','modifier','generator','biomeOres','dimension','region','biome'],sources):values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    require(v,'@Snippet("deposit-variant")','@NoArgsConstructor','private KMap<String, String> remap = new KMap<>();')
    for field in ['minHeight','maxHeight']:
        declaration=re.search(r'@MinNumber\((-2048)\)\s+@MaxNumber\((8192)\)\s+@Description\(("(?:[^"\\]|\\.)*")\)\s+private int '+field+r' = (0);',v)
        if not declaration:raise ValueError('Changed variant band '+field)
        values[field+'.minimum'],values[field+'.maximum']=declaration.group(1),declaration.group(2)
        values[field+'.description']=json.loads(declaration.group(3));values[field+'.default']=declaration.group(4)
    description=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private KMap<String, String> remap',v)
    if not description:raise ValueError('Changed remap help')
    values['remap.description']=json.loads(description.group(1))
    require(v,'if (ore == null || remap == null || remap.isEmpty()) { return null; }','return map.get(IrisProceduralBlocks.materialKey(ore));',
            'NativeBlockState source = B.getStateOrNull(entry.getKey(), false); NativeBlockState target = B.getStateOrNull(entry.getValue(), true);',
            'if (source == null || target == null) { continue; } out.put(IrisProceduralBlocks.materialKey(source), target);')
    for parent in [d,r,b]:require(parent,'private KList<IrisDepositVariant> depositVariants = new KList<>();')
    require(m,'int worldY = absoluteWorldY(getEngine().getMinHeight(), localY);','return minHeight + localY;',
            'IrisBiome biome = getEngine().getCaveBiome(worldX, localY, worldZ, carvingState); if (biome != null) { NativeBlockState match = matchDepositVariant(biome.getDepositVariants(), ore, worldY); if (match != null) { return match; } } IrisRegion region = context.getRegion().get(nx, nz); if (region != null) { NativeBlockState match = matchDepositVariant(region.getDepositVariants(), ore, worldY); if (match != null) { return match; } } if (dimension != null) { NativeBlockState match = matchDepositVariant(dimension.getDepositVariants(), ore, worldY); if (match != null) { return match; } }',
            'for (IrisDepositVariant variant : variants) { if (y < variant.getMinHeight() || y > variant.getMaxHeight()) { continue; } NativeBlockState swapped = variant.remapOrNull(ore, getData()); if (swapped != null) { return swapped; } }',
            'NativeBlockState finalBlock = remapped != null ? remapped : B.toDeepSlateOre(current, ore);',
            'if (oreDeposit) { candidateSurfaceBiome = context.getBiome().get(nx, nz); exteriorSurface = k.hasSurfaceReplaceableBlocks(candidateSurfaceBiome) && isTerrainSurface(data, nx, ny, nz, columnSurface); }',
            'if (terrainSurface && generator.hasSurfaceReplaceableBlocks(surfaceBiome)) { return generator.canReplaceSurface(state, surfaceBiome); } return generator.canReplace(state);')
    require(b,'private KList<String> surfaceOreReplaceableBlocks = null;','return IrisBiomeOres.hasSurfaceOreReplaceableBlocks(this);','return IrisBiomeOres.canReplaceSurfaceOre(this, state);')
    require(bo,'return biome.getSurfaceOreReplaceableBlocks() != null;',
            'if (!hasSurfaceOreReplaceableBlocks(biome)) { return true; } return biome.getSurfaceOreReplaceableBlockData().aquire(() -> resolveSurfaceOreReplaceableBlocks(biome)).contains(IrisProceduralBlocks.materialKey(state));',
            'for (String key : biome.getSurfaceOreReplaceableBlocks()) { NativeBlockState state = B.getStateOrNull(key, false); if (state != null) { resolved.add(IrisProceduralBlocks.materialKey(state)); } }')
    require(g,'if (surfaceBiome != null && surfaceBiome.hasSurfaceOreReplaceableBlocks()) { return true; } return surfaceReplaceableBlocks != null && !surfaceReplaceableBlocks.isEmpty();',
            'if (surfaceBiome != null && surfaceBiome.hasSurfaceOreReplaceableBlocks()) { return surfaceBiome.canReplaceSurfaceOre(state); } return canReplaceSurface(state);')
    values.update({'height.policy':'inclusiveWorldY','dispatch.policy':'biomeRegionDimensionFirstRemap','source.policy':'materialIdentity','hosts.policy':'nullInheritEmptyForbid','hosts.scope':'oreDepositExteriorSurface'})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__':Path(sys.argv[8]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:8])))
