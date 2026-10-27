"""Reject changes in deposit schema, native defaults and placement semantics."""
import sys
from pathlib import Path
from extract_deposit_generator_fields import extract

sources=[Path(path).read_bytes() for path in sys.argv[1:]]
result=extract(*sources).decode()
for token in ['palette.policy=uniformEntries\n','dispatch.policy=dimensionRegionBiomeAdditive\n','minHeight.default=1\n','maxHeight.default=75\n','varience.maximum=64\n','heightDistribution.default=CLIPPED_UNIFORM\n','surface.policy=oreOnlyBiomeOverride\n']:assert token in result,token
mutations=[(0,b'private int minHeight = 1;',b'private int minHeight = 0;'),(0,b'@MaxNumber(2048)',b'@MaxNumber(2049)'),
           (0,b'private boolean replaceBedrock = false;',b'private boolean replaceBedrock = true;'),
           (0,b'blockData.add(bx);',b'blockData.add(bx); blockData.add(bx);'),
           (0,b'configured.equals(biome.getLoadKey())',b'configured.equalsIgnoreCase(biome.getLoadKey())'),
           (0,b'includedBiomes != null && !includedBiomes.isEmpty()',b'includedBiomes != null'),
           (0,b'return surfaceBiome.canReplaceSurfaceOre(state);',b'return true;'),
           (1,b'    VANILLA_SCATTERED',b'    RANDOM'),(2,b'    TRIANGLE',b'    RANDOM'),(3,b'    FULL_HEIGHT',b'    RANDOM'),(4,b'    CAVE',b'    RANDOM'),
           (5,b'private KList<IrisDepositGenerator> deposits = new KList<>();',b'private KList<IrisDepositGenerator> deposits;'),
           (6,b'private KList<IrisDepositGenerator> deposits = new KList<>();',b'private KList<IrisDepositGenerator> deposits;'),
           (7,b'private KList<IrisDepositGenerator> deposits = new KList<>();',b'private KList<IrisDepositGenerator> deposits;'),
           (8,b'getMinPerChunk(), generator.getMaxPerChunk() + 1',b'getMinPerChunk(), generator.getMaxPerChunk()'),
           (8,b'generators.addAll(region.getDeposits());',b''),(8,b'getPerClumpSpawnChance() < clumpRng.d()',b'getPerClumpSpawnChance() <= clumpRng.d()'),
           (8,b'return minHeight + localY;',b'return localY;'),(8,b'candidateY > surfaceY + clearance',b'candidateY > surfaceY'),
           (8,b'return minimum + rng.nextInt(maximum - minimum + 1);',b'return minimum + rng.nextInt(maximum - minimum);'),
           (8,b'!k.isReplaceBedrock()',b'k.isReplaceBedrock()'),(8,b'!state.isAir() && !state.isFluid()',b'!state.isAir()'),
           (8,b'sample < Math.min(1D, chance)',b'sample <= Math.min(1D, chance)')]
for index,old,new in mutations:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try:extract(*changed)
    except ValueError:continue
    raise AssertionError('Accepted deposit drift: '+str(old))
changed=sources.copy();changed[0]+=b'\n getWeight();'
try:extract(*changed)
except ValueError:pass
else:raise AssertionError('Accepted weighted deposit palette')
print('PASS: all deposit declarations/defaults/bounds/enums, unweighted materials, additive dispatch, inclusive local heights/counts, spawn/exposure/host/biome and bedrock contracts reject drift')
