"""Source mutation tests for independent deep-fluid defaults and native bounded rules."""
import sys
from pathlib import Path
from extract_deep_fluid_fields import extract
sources=[Path(path).read_bytes() for path in sys.argv[1:]]
data=extract(*sources).decode()
for token in ['list.minimum=0\n','list.maximum=64\n','id.default=deep_lava\n','fluid.default=lava\n','density.default=0.125\n','spacing.default=768\n','height.context.min=-192\n','height.context.max=32\n','height.context.style=IRIS\n','height.context.zoom=1024\n','height.class.min=16\n','height.class.max=32\n','height.class.style=STATIC\n','geometry.rule=spacingAndClearance\n']:
    assert token in data
mutations=[(0,b'private KList<IrisDeepFluidConfig> deepFluids = new KList<>();',b'private KList<IrisDeepFluidConfig> deepFluids = null;'),
           (1,b'String id = "deep_lava"',b'String id = "other"'),(1,b'.qadd("lava")',b'.qadd("water")'),
           (1,b'int spacing = 768',b'double spacing = 768'),(1,b'@MaxNumber(8192)',b'@MaxNumber(8193)'),
           (1,b'boolean containedPools = true',b'boolean containedPools = Boolean.TRUE'),(1,b'-192D',b'-191D'),
           (2,b'@AllArgsConstructor',b''),(2,b'private double min = 16;',b'private long min = 16;'),
           (3,b'this.style = s;',b'this.style = NoiseStyle.STATIC;'),(3,b'this.zoom = z;',b'this.zoom = 1;'),
           (4,b'MAX_DEEP_FLUIDS = 64',b'MAX_DEEP_FLUIDS = 65'),(4,b'!ids.add(id)',b'ids.add(id)'),
           (4,b'validateFluidPalette(fluidPath, deepFluid, true, errors);',b'validateFluidPalette(fluidPath, deepFluid, false, errors);'),
           (4,b'(horizontalRadius + channelWidth) * 2',b'horizontalRadius * 2'),(4,b'spacing < footprintDiameter',b'spacing <= footprintDiameter'),
           (4,b'depth + headroom >= verticalRadius * 2 + 1',b'depth + headroom > verticalRadius * 2 + 1'),
           (4,b'DEFAULT_DEEP_FLUID.getHeadroom()',b'DEFAULT_DEEP_FLUID.getDepth()'),
           (4,b'"headroom", 1, 63',b'"headroom", 1, 64'),(4,b'"shortChannels", errors',b'"shortChannel", errors'),
           (5,b"replace('-', '_')",b"replace('_', '-')")]
for index,old,new in mutations:
    assert old in sources[index],old
    changed=sources.copy();changed[index]=changed[index].replace(old,new)
    try: extract(*changed)
    except (ValueError,ArithmeticError): continue
    raise AssertionError('Accepted source drift: '+str(old))
for index in [0,1,2]:
    changed=sources.copy();changed[index]=changed[index].replace(b'@NoArgsConstructor',b'')
    try: extract(*changed)
    except ValueError: continue
    raise AssertionError('Accepted changed constructor')
print('PASS: independent deep-fluid list/scalar/default contracts, native spacing/clearance, contextual/class height constructors and reserved IDs reject drift')
