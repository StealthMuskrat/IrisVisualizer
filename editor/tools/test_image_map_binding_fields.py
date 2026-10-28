"""Reject binding schema, trim/case/singleton checks and ordered mask composition drift."""
import sys
from pathlib import Path
from extract_image_map_binding_fields import extract
sources=[Path(path).read_bytes() for path in sys.argv[1:]];result=extract(*sources).decode()
for token in ['binding.application.default=CUSTOM\n','mask.operation.default=MULTIPLY\n','mask.threshold.default=0\n','key.policy=trimmedCaseSensitive\n','mask.policy=namedMaskBindings\n']:assert token in result,token
for index,old,new in [(0,b'private KList<IrisImageMapBinding> imageMaps = new KList<>();',b'private KList<IrisImageMapBinding> imageMaps = null;'),
                      (1,b'IrisImageMapApplication.CUSTOM',b'IrisImageMapApplication.BIOME'),
                      (1,b'private String key = "";',b'private String key = "default";'),
                      (2,b'IrisImageMapMaskOperation.MULTIPLY',b'IrisImageMapMaskOperation.ADD'),
                      (2,b'@MaxNumber(1)',b'@MaxNumber(2)'),(2,b'private double threshold = 0D;',b'private double threshold = 0.5D;'),
                      (3,b'    CUSTOM',b'    OTHER'),(4,b'    SUBTRACT',b'    DIFFERENCE'),
                      (5,b'bindings.putIfAbsent(runtimeBinding.key, runtimeBinding)',b'bindings.put(runtimeBinding.key, runtimeBinding)'),
                      (5,b'return value.trim();',b'return value.trim().toLowerCase();'),
                      (5,b'maskBinding.application != IrisImageMapApplication.MASK',b'false'),
                      (5,b'application == IrisImageMapApplication.MASK && masks != null && !masks.isEmpty()',b'false'),
                      (6,b'double weight = 1D;',b'double weight = 0D;'),(6,b'weight = clamp01(weight);',b''),
                      (6,b'if (inverted) {',b'if (false) {'),(6,b'return value >= threshold ? 1D : 0D;',b'return value > threshold ? 1D : 0D;')]:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try:extract(*changed)
    except ValueError:continue
    raise AssertionError('Accepted binding/mask drift: '+str(old))
print('PASS: image-map binding/mask defaults and enums, trimmed case-sensitive keys, singleton roles, named MASK resolution and ordered inversion/threshold/falloff/clamped composition reject drift')
