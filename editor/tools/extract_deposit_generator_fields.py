"""Bind deposit authoring to pinned declarations, enum choices and native placement consumers."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact

DEFAULTS={'minHeight':'1','maxHeight':'75','heightDistribution':'IrisDepositHeightDistribution.CLIPPED_UNIFORM','placementScope':'IrisDepositPlacementScope.TERRAIN','surfaceClearance':'7',
          'minSize':'0','maxSize':'128','shape':'IrisDepositShape.IRIS','maxPerChunk':'3','minPerChunk':'0','spawnChance':'1','perClumpSpawnChance':'1','discardChanceOnAirExposure':'0',
          'palette':'new KList<>()','varience':'3','replaceableBlocks':'new KList<>()','surfaceReplaceableBlocks':'new KList<>()','biomeScope':'IrisDepositBiomeScope.CAVE',
          'includedBiomes':'new KList<>()','excludedBiomes':'new KList<>()','replaceBedrock':'false'}
BOUNDS={'minHeight':('-8192','8192'),'maxHeight':('-8192','8192'),'surfaceClearance':('0','256'),'minSize':('0','8192'),'maxSize':('0','8192'),
        'minPerChunk':('0','2048'),'maxPerChunk':('0','2048'),'spawnChance':('0','1'),'perClumpSpawnChance':('0','1'),'discardChanceOnAirExposure':('0','1'),'varience':('1','64')}

def extract(generator,shape,distribution,placement,biome_scope,dimension,region,biome,modifier):
    sources=[generator,shape,distribution,placement,biome_scope,dimension,region,biome,modifier]
    g,s,di,p,bs,d,r,b,m=[raw.decode('utf-8') for raw in sources]
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text):raise ValueError('Changed deposit contract: '+contract)
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for name,raw in zip(['generator','shape','distribution','placement','biomeScope','dimension','region','biome','modifier'],sources):values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    declarations=list(re.finditer(r'((?:    @[^\n]+\n)+)    private ([\w<>]+) (\w+) = ([^;]+);',g))
    if [item.group(3) for item in declarations]!=list(DEFAULTS):raise ValueError('Changed deposit field inventory')
    values['fields']=','.join(DEFAULTS)
    for item in declarations:
        annotations,kind,name,default=item.groups()
        if compact(default)!=compact(DEFAULTS[name]):raise ValueError('Changed deposit default '+name)
        description=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)',annotations)
        if not description:raise ValueError('Missing deposit help '+name)
        values[name+'.type']=kind;values[name+'.description']=json.loads(description.group(1))
        values[name+'.default']='[]' if kind.startswith('KList<') else default.rsplit('.',1)[-1] if kind.startswith('IrisDeposit') else default
        if name in BOUNDS:
            low=re.search(r'@MinNumber\((-?[0-9]+)\)',annotations);high=re.search(r'@MaxNumber\((-?[0-9]+)\)',annotations)
            if not low or not high or (low.group(1),high.group(1))!=BOUNDS[name]:raise ValueError('Changed deposit bounds '+name)
            values[name+'.minimum'],values[name+'.maximum']=BOUNDS[name]
    for name,text,expected in [('shape',s,['IRIS','VANILLA_ELLIPSOID','VANILLA_SCATTERED']),('heightDistribution',di,['CLIPPED_UNIFORM','UNIFORM','TRIANGLE']),
                               ('placementScope',p,['TERRAIN','ABOVE_TERRAIN','FULL_HEIGHT']),('biomeScope',bs,['SURFACE','CAVE'])]:
        choices=re.findall(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+([A-Z_]+)\s*(?:,|\n)',text)
        if [choice for _,choice in choices]!=expected:raise ValueError('Changed deposit choices '+name)
        values[name+'.choices']=','.join(expected)
        for description,choice in choices:values[name+'.'+choice+'.description']=json.loads(description)
    for parent in [d,r,b]:require(parent,'private KList<IrisDepositGenerator> deposits = new KList<>();')
    require(g,'@Snippet("deposit")','@ArrayType(min = 1, type = IrisBlockData.class)',
            'return getBlockData(rdata).get(rngv.i(0, getBlockData(rdata).size()));',
            'for (IrisBlockData ix : palette) { NativeBlockState bx = ix.getBlockData(rdata); if (bx != null) { blockData.add(bx); } }',
            'int s = rngv.i(clumpMinSize, clumpMaxSize + 1);','for (int i = 0; i < varience; i++)',
            'if (replaceableBlocks == null || replaceableBlocks.isEmpty()) { return true; }',
            'if (surfaceReplaceableBlocks == null || surfaceReplaceableBlocks.isEmpty()) { return true; }',
            'if (surfaceBiome != null && surfaceBiome.hasSurfaceOreReplaceableBlocks()) { return surfaceBiome.canReplaceSurfaceOre(state); } return canReplaceSurface(state);',
            'IrisBiome selected = biomeScope == IrisDepositBiomeScope.SURFACE ? surfaceBiome : caveBiome;',
            'if (includedBiomes != null && !includedBiomes.isEmpty() && !matchesAnyBiome(selected, includedBiomes)) { return false; } return excludedBiomes == null || excludedBiomes.isEmpty() || !matchesAnyBiome(selected, excludedBiomes);',
            'String namespaced = configured.contains(":") ? configured : "minecraft:" + configured;',
            'if (configured.equals(biome.getLoadKey()) || namespaced.equals(biome.getDerivativeKey()) || namespaced.equals(biome.getVanillaDerivativeKey())) { return true; }')
    if 'getWeight(' in g:raise ValueError('Deposit palette now consumes weights')
    require(m,'IrisRegion region = context.getRegion().get(7, 7); IrisBiome biome = context.getBiome().get(7, 7);',
            'generators.addAll(getDimension().getDeposits()); generators.addAll(region.getDeposits()); generators.addAll(biome.getDeposits());',
            'if (generator.getSpawnChance() < rng.d()) { return new DepositPlan(generator, rng.getSeed(), false, 0); }',
            'int attempts = rng.i(generator.getMinPerChunk(), generator.getMaxPerChunk() + 1);',
            'if (k.getPerClumpSpawnChance() < clumpRng.d()) { continue; }',
            'return minHeight + localY;','return surfaceY - Math.max(0, surfaceClearance);',
            'case ABOVE_TERRAIN -> candidateY > surfaceY + clearance; case FULL_HEIGHT -> true; case TERRAIN -> candidateY <= surfaceY - clearance;',
            'int minimum = distribution == IrisDepositHeightDistribution.CLIPPED_UNIFORM ? Math.max(0, configuredMinimum) : configuredMinimum;',
            'int maximum = distribution == IrisDepositHeightDistribution.CLIPPED_UNIFORM ? Math.min(clippedMaximum, configuredMaximum) : configuredMaximum;',
            'return minimum + rng.nextInt(maximum - minimum + 1);','return minimum + rng.nextInt(upperHalf + 1) + rng.nextInt(lowerHalf + 1);',
            'return state != null && !state.isAir() && !state.isFluid();',
            'if (!k.isReplaceBedrock() && IrisProceduralBlocks.materialKey(current).equals("minecraft:bedrock")) { continue; }',
            'if (oreDeposit) { candidateSurfaceBiome = context.getBiome().get(nx, nz); exteriorSurface = k.hasSurfaceReplaceableBlocks(candidateSurfaceBiome) && isTerrainSurface(data, nx, ny, nz, columnSurface); }',
            'if (terrainSurface && generator.hasSurfaceReplaceableBlocks(surfaceBiome)) { return generator.canReplaceSurface(state, surfaceBiome); } return generator.canReplace(state);',
            'return adjacentToAir && chance > 0D && sample < Math.min(1D, chance);')
    values.update({'palette.policy':'uniformEntries','dispatch.policy':'dimensionRegionBiomeAdditive','height.policy':'inclusiveLocalY','surface.policy':'oreOnlyBiomeOverride'})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__':Path(sys.argv[10]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:10])))
