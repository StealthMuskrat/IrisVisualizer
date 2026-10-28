"""Reject native child type/default, order/salt and centering drift."""
import sys
from pathlib import Path
from extract_noise_fracture_fields import extract

raw=Path(sys.argv[1]).read_bytes();result=extract(raw).decode()
for token in ['minimumEntries=1\n','default.policy=absentEmpty\n','sampling.policy=orderedEnabledChildren\n','child.style=IRIS\n','native.optimization=separateFromEditorLimits\n']:assert token in result,token
mutations=[(b'@ArrayType(min = 1, type = IrisNoiseGenerator.class)',b'@ArrayType(min = 0, type = IrisNoiseGenerator.class)'),
           (b'private KList<IrisNoiseGenerator> fracture = new KList<>();',b'private KList<IrisNoiseGenerator> fracture = null;'),
           (b'private long seed = 0;',b'private long seed = 1;'),(b'NoiseStyle.IRIS.style()',b'NoiseStyle.FLAT.style()'),
           (b'int g = 33;',b'int g = 34;'),(b'g += 819;',b'g += 1;'),(b'i.getOpacity() / 2D',b'i.getOpacity()'),
           (b'i.getNoise(superSeed + seed + g, zv, xv, data, engine)',b'i.getNoise(superSeed + seed + g, xv, zv, data, engine)'),
           (b'if (i.isEnabled())',b'if (true)'),(b'salt += 819;',b'salt += 1;'),
           (b'child.applyAsDouble(zv, xv)',b'child.applyAsDouble(xv, zv)'),
           (b'g.addAll(i.getAllComposites());',b'g.add(i);'),(b'private static final int MAXIMUM_FRACTURE_NODES = 32;',b'private static final int MAXIMUM_FRACTURE_NODES = 64;')]
for old,new in mutations:
    assert old in raw,old
    try:extract(raw.replace(old,new))
    except ValueError:continue
    raise AssertionError('Accepted noise-fracture drift: '+str(old))
print('PASS: recursive noise list type/default, child defaults, ordered seed salts, enabled gates, coordinate swap/centering and batch consumers reject drift')
