"""Reject source drift in layer constructors, annotation limits and generator-context behavior."""
import sys
from pathlib import Path
from extract_biome_layer_fields import extract
sources=[Path(path).read_bytes() for path in sys.argv[1:]]
data=extract(*sources).decode()
for value in ['list.minimum=0\n','layers.defaultCount=1\n','seaLayers.defaultCount=0\n','caveCeilingLayers.defaultCount=0\n','palette.defaultBlock=GRASS_BLOCK\n','style.default=STATIC\n','minHeight.minimum=0\n','maxHeight.minimum=1\n','minHeight.maximum=2032\n','maximumSlope.default=10\n','ceiling.rule=independentCeilingGenerators\n','slope.context=surfaceOnly\n']:
    assert value in data
mutations=[(0,b'.qadd(new IrisBiomePaletteLayer());',b';'),(0,b'KList<IrisBiomePaletteLayer> seaLayers = new KList<>()',b'KList<IrisBiomePaletteLayer> seaLayers = null'),
           (1,b'private int minHeight = 1',b'private double minHeight = 1'),(1,b'private IrisSlopeClip slopeCondition = new IrisSlopeClip();',b'private IrisSlopeClip slopeCondition = null;'),
           (1,b'min = 1, type = IrisBlockData.class',b'min = 0, type = IrisBlockData.class'),(1,b'NoiseStyle.STATIC.style()',b'null'),
           (1,b'double scaledX = x / localZoom;',b'double scaledX = x * localZoom;'),(2,b'return minimumSlope <= 0 && maximumSlope >= 10;',b'return minimumSlope == 0 && maximumSlope == 10;'),
           (2,b'!(minimumSlope > slope) && !(maximumSlope < slope)',b'!(minimumSlope >= slope) && !(maximumSlope <= slope)'),
           (3,b'int min() default 0;',b'int min() default 1;'),(4,b'biome.getLayerCeilingHeightGenerators(), ceilingLayers, 7235',b'biome.getLayerCeilingHeightGenerators(), biome.getLayers(), 7235'),
           (4,b'hgen.fit(layer.getMinHeight(), layer.getMaxHeight(), wx / zoom, wz / zoom)',b'hgen.fit(1, 1, wx / zoom, wz / zoom)'),
           (5,b'this.block = b;',b'this.block = "STONE";'),(6,b'!(layer instanceof String)',b'!(layer instanceof Number)')]
for index,old,new in mutations:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try: extract(*changed)
    except (ValueError,ArithmeticError): continue
    raise AssertionError('Accepted source drift: '+str(old))
for index in [0,1,2,5]:
    changed=sources.copy();changed[index]=changed[index].replace(b'@NoArgsConstructor',b'')
    try: extract(*changed)
    except ValueError: continue
    raise AssertionError('Accepted changed constructors')
print('PASS: biome layer list/default/thickness/palette annotations, slope gate semantics and independent ceiling/sea generator bindings reject drift')
