"""Reject dimension-type default, sentinel, null-state and consumer drift."""
import sys
from pathlib import Path
from extract_dimension_type_fields import extract

sources=[Path(path).read_bytes() for path in sys.argv[1:]];result=extract(*sources).decode()
for token in ['states=DEFAULT,TRUE,FALSE\n','environments=NORMAL,NETHER,THE_END,CUSTOM\n','fixedTime.maximum=9223372036854775807\n','fixedTime.null=timeCycle\n','cloudHeight.null=cloudsDisabled\n','fullbright.policy=copyAmbientOne\n']:assert token in result,token
mutations=[(0,b'private IrisDimensionTypeOptions dimensionOptions = new IrisDimensionTypeOptions();',b'private IrisDimensionTypeOptions dimensionOptions = null;'),
           (0,b'private IrisEnvironment environment = IrisEnvironment.NORMAL;',b'private IrisEnvironment environment = IrisEnvironment.NETHER;'),
           (0,b'private boolean fullbright = false;',b'private boolean fullbright = true;'),(0,b'options = options.copy().ambientLight(1.0f);',b'options.ambientLight(1.0f);'),
           (0,b'default -> Dimension.OVERWORLD;',b'default -> Dimension.END;'),(1,b'private Long fixedTime = -1L;',b'private Long fixedTime = null;'),
           (1,b'@MaxNumber(Long.MAX_VALUE)',b'@MaxNumber(Integer.MAX_VALUE)'),(1,b'private float ambientLight = -1;',b'private float ambientLight = 0;'),
           (1,b'fixedTime != null && fixedTime == -1L',b'fixedTime == null'),(1,b'cloudHeight != null && cloudHeight == -1',b'cloudHeight == null'),
           (1,b'private TriState bedWorks = DEFAULT;',b'private TriState bedWorks = FALSE;'),(1,b'if (skylight == DEFAULT)',b'if (skylight == FALSE)'),
           (1,b'if (fixedTime != null) json.put("fixed_time", fixedTime);',b'json.put("fixed_time", fixedTime);'),(2,b'CUSTOM',b'OTHER'),
           (3,b'options.resolve(OPTIONS.get(dimension)).toJson()',b'options.toJson()'),(4,b'if (!replacementType.similar(activeType))',b'if (false)')]
for index,old,new in mutations:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try:extract(*changed)
    except ValueError:continue
    raise AssertionError('Accepted dimension-type drift: '+str(old))
print('PASS: dimension-type declarations/bounds, inherit versus null states, environment choices/base mapping, fullbright copy and native resolution/hotload consumers reject drift')
