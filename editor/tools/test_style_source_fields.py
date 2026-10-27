"""Reject source reference, precedence/fallback, folder and cache contract drift."""
import sys
from pathlib import Path
from extract_style_source_fields import extract

sources=[Path(path).read_bytes() for path in sys.argv[1:]];result=extract(*sources).decode()
for token in ['expression.family=expressions\n','imageMap.family=image-maps\n','cacheSize.maximum=8192\n','expression.fallback=baseOnUnloadedExpression\n','cache.policy=positiveOverrideOtherwiseCallerFallback\n']:assert token in result,token
mutations=[(0,b'private String expression = null;',b'private String expression = "";'),(0,b'@MaxNumber(8192)',b'@MaxNumber(4096)'),
           (0,b'} else if (getImageMap() != null)',b'} if (getImageMap() != null)'),(0,b'NoiseStyle.FLAT).create(rng)',b'NoiseStyle.STATIC).create(rng)'),
           (0,b'cacheSize > 0 ? cacheSize : Math.max(0, fallbackCacheSize)',b'cacheSize'),
           (0,b'cng.cached(effectiveCacheSize, key, data.getDataFolder(), quietCacheLog)',b'cng.cached(cacheSize, key, data.getDataFolder(), quietCacheLog)'),
           (1,b'return "expressions";',b'return "expression";'),(2,b'return "image-maps";',b'return "imageMaps";'),
           (3,b'this.expressionLoader = registerLoader(IrisExpression.class, replacement);',b'this.expressionLoader = null;'),
           (4,b'f.getName().endsWith(".json")',b'f.getName().endsWith(".JSON")')]
for index,old,new in mutations:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try:extract(*changed)
    except ValueError:continue
    raise AssertionError('Accepted style source drift: '+str(old))
print('PASS: nullable expression/image references, exact resource folders/JSON keys, expression-before-image/base fallback and positive cache override/caller fallback reject drift')
