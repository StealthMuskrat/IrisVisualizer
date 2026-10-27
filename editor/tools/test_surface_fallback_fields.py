"""Fallback enum syntax and generation inheritance are audited without running a generator."""
import sys
from pathlib import Path
from extract_surface_fallback_fields import extract
sources=[Path(path).read_bytes() for path in sys.argv[1:]]
data=extract(*sources).decode()
for value in ['dimension.default=ROCK\n','biome.default=null\n','choices=ROCK,TOP_LAYER\n','TOP_LAYER.description=']:
    assert value in data
mutations=[(0,b'IrisSurfaceLayerFallback.ROCK;',b'null;'),(1,b'IrisSurfaceLayerFallback surfaceLayerFallback = null;',b'IrisSurfaceLayerFallback surfaceLayerFallback = IrisSurfaceLayerFallback.ROCK;'),
           (2,b'TOP_LAYER',b'ROCK'),(2,b'    ROCK,',b'    ROCK();'),
           (3,b'if (fallback == null)',b'if (fallback != null)'),(3,b'fallback = dim.getSurfaceLayerFallback();',b'fallback = IrisSurfaceLayerFallback.ROCK;'),
           (3,b'biome.getLayers().get(0), 0, 1,',b'biome.getLayers().get(0), 0, 2,')]
for index,old,new in mutations:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try: extract(*changed)
    except ValueError: continue
    raise AssertionError('Accepted source drift: '+str(old))
for index in [0,1]:
    changed=sources.copy();changed[index]=changed[index].replace(b'@NoArgsConstructor',b'')
    try: extract(*changed)
    except ValueError: continue
    raise AssertionError('Accepted changed constructor')
print('PASS: surface fallback enum/default help, nullable biome inheritance and runtime top-layer binding reject drift')
