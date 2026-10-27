"""Mutation checks for dimension height constructor contracts and strict envelope margins."""
import sys
from pathlib import Path
from extract_dimension_height_fields import extract
sources=[Path(path).read_bytes() for path in sys.argv[1:]]
data=extract(*sources).decode()
for token in ['context.min=-64\n','context.max=320\n','class.min=16\n','class.max=32\n','span.minimum=16\n','span.maximum=4064\n','minimumY.minimum=-2032\n','minimumY.maximum=2031\n','step=16\n','logical.default=256\n','envelope.rule=strictInside\n']:
    assert token in data
mutations=[(0,b'new IrisRange(-64, 320)',b'new IrisRange(-80, 320)'),(0,b'int logicalHeight = 256',b'int logicalHeight = 128'),
           (1,b'@AllArgsConstructor',b''),(1,b'double min = 16',b'int min = 16'),
           (2,b'if (logicalHeight > height)',b'if (logicalHeight >= height)'),(2,b'if (logicalHeight < 0)',b'if (logicalHeight <= 0)'),
           (2,b'height < MIN_HEIGHT || height > MAX_HEIGHT',b'height <= MIN_HEIGHT || height > MAX_HEIGHT'),
           (2,b'(minY & (HEIGHT_STEP - 1)) != 0',b'(minY & HEIGHT_STEP) != 0'),(2,b'HEIGHT_STEP = 16',b'HEIGHT_STEP = 15'),
           (3,b'range.optDouble("min", 16D)',b'range.optDouble("min", -64D)'),(3,b'logicalHeight", 256D',b'logicalHeight", 128D'),
           (4,b'containedPools ? Math.max(depth, verticalRadius) : depth',b'containedPools ? verticalRadius : depth'),
           (4,b'configuredHeight.minimum() - lowerEnvelope <= dimensionRange.minimum()',b'configuredHeight.minimum() - lowerEnvelope < dimensionRange.minimum()'),
           (4,b'configuredHeight.maximum() + headroom >= dimensionRange.maximum()',b'configuredHeight.maximum() + headroom > dimensionRange.maximum()'),
           (4,b'doubleValue(height, "min", -64D)',b'doubleValue(height, "min", 16D)'),
           (4,b'DEFAULT_DEEP_FLUID.getHeadroom()',b'DEFAULT_DEEP_FLUID.getDepth()'),
           (4,b'doubleValue(range, "min", defaultMinimum)',b'doubleValue(range, "min", 16D)')]
for index,old,new in mutations:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try: extract(*changed)
    except (ValueError,ArithmeticError): continue
    raise AssertionError('Accepted source drift: '+str(old))
for index in [0,1]:
    changed=sources.copy();changed[index]=changed[index].replace(b'@NoArgsConstructor',b'')
    try: extract(*changed)
    except ValueError: continue
    raise AssertionError('Accepted changed constructor')
print('PASS: contextual/class dimension bounds, native height/span/alignment/logical bindings and strict deep-fluid envelope formulas reject drift')
