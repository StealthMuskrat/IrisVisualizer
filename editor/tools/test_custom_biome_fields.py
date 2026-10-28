"""Reject custom-biome defaults, enums and native datapack/selection consumer drift."""
import sys
from pathlib import Path
from extract_custom_biome_fields import extract

sources=[Path(path).read_bytes() for path in sys.argv[1:]];result=extract(*sources).decode()
for token in ['biome.skyColor.default=#79a8e1\n','biome.temperature.minimum=-3\n','spawn.maxCount.maximum=2147483647\n','particle.rarity.default=35\n','spawns.policy=independentOfSpawnRarity\n','colors.policy=fourAlwaysTwoOptional\n']:assert token in result,token
mutations=[(0,b'private double temperature = 0.8;',b'private double temperature = 0.5;'),(0,b'@MaxNumber(20)',b'@MaxNumber(10)'),
           (0,b'private String skyColor = "#79a8e1";',b'private String skyColor = "#000000";'),
           (0,b'if (!getGrassColor().isEmpty())',b'if (true)'),(0,b'1f / ambientParticle.getRarity()',b'ambientParticle.getRarity()'),
           (0,b'if (getSpawns() != null && getSpawns().isNotEmpty())',b'if (spawnRarity > 0 && getSpawns() != null && getSpawns().isNotEmpty())'),
           (0,b'contentGate.entity(key) == KeyStatus.MISSING',b'false'),(0,b'i.getGroup() == null ? IrisBiomeCustomSpawnType.MISC : i.getGroup()',b'i.getGroup()'),
           (0,b'IrisVanillaBiomeTags.tagsFor(vanillaDerivativeKey)',b'new KList<String>()'),(0,b'return id.toLowerCase();',b'return id;'),
           (1,b'private int minCount = 2;',b'private int minCount = 1;'),(1,b'type.trim().toLowerCase(Locale.ROOT)',b'type.trim()'),
           (2,b'private int rarity = 35;',b'private int rarity = 10;'),(3,b'    plains,',b'    flatlands,'),
           (4,b'    rain,',b'    rainfall,'),(5,b'    MISC',b'    OTHER'),
           (6,b'private KList<IrisBiomeCustom> customDerivitives;',b'private KList<IrisBiomeCustom> customDerivitives = new KList<>();'),
           (7,b'customDerivitives.size() - 1, x, y, z',b'customDerivitives.size(), x, y, z'),
           (8,b'customBiome.getEffectiveTags(derivativeKey)',b'customBiome.getTags()')]
for index,old,new in mutations:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try:extract(*changed)
    except ValueError:continue
    raise AssertionError('Accepted custom-biome drift: '+str(old))
print('PASS: custom biome/spawn/particle declarations, enums, color/selection rules, independent spawn emission/content filtering, particle probability and tag consumers reject drift')
