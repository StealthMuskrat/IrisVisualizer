"""Reject changes to derivative defaults, scatter shape, fallback and registry bindings."""
import sys
from pathlib import Path
from extract_biome_derivative_fields import extract
sources=[Path(path).read_bytes() for path in sys.argv[1:]]
result=extract(*sources).decode()
for contract in ['derivative.default=minecraft:the_void\n','vanillaDerivative.default=null\n','biomeScatter.default=[]\n','biomeSkyScatter.default=[]\n','biomeScatter.minimumEntries=1\n']:
    assert contract in result
mutations=[(0,b'private String derivative = "minecraft:the_void";',b'private String derivative = "minecraft:plains";'),
           (0,b'private String vanillaDerivative = null;',b'private String vanillaDerivative = "minecraft:plains";'),
           (0,b'KList<String> biomeScatter = new KList<>()',b'KList<String> biomeScatter = null'),
           (0,b'min = 1, type = String.class',b'min = 2, type = String.class'),
           (0,b'return resolved == null ? getDerivative() : resolved;',b'return resolved;'),
           (0,b'biomeStyle.createNoCache',b'childStyle.createNoCache'),
           (1,b'key.trim()',b'key'),(1,b'"minecraft:" + trimmed',b'"other:" + trimmed'),
           (1,b'return resolved == null ? namespacedBiomeKey(derivative) : resolved;',b'return resolved;'),
           (1,b'return "minecraft:the_void";',b'return "minecraft:plains";'),
           (1,b'return "minecraft:beach";',b'return "minecraft:plains";'),
           (1,b'key.contains("ocean")',b'key.equals("ocean")'),
           (1,b'key.endsWith("shore")',b'key.endsWith("coast")'),
           (1,b'RegistryUtil.lookup(Biome.class)',b'RegistryUtil.lookup(Other.class)'),
           (1,b'if (biomeScatter.isEmpty())',b'if (biomeScatter.size() == 1)'),
           (1,b'if (biomeSkyScatter.isEmpty())',b'if (biomeSkyScatter.size() == 1)'),
           (1,b'biome.getBiomeScatterResolved(), x, y, z',b'biome.getBiomeSkyScatterResolved(), x, y, z'),
           (1,b'physicalBiomeResourceKey(engine.getDimension(), logicalKey)',b'physicalBiomeResourceKey(null, logicalKey)')]
for index,old,new in mutations:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try: extract(*changed)
    except ValueError: continue
    raise AssertionError('Accepted derivative source drift: '+str(old))
print('PASS: derivative/scatter defaults, list declarations, ground/sky fallbacks, biome-style selection, structure-role and registry/physical-key bindings reject drift')
