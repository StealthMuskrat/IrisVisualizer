"""Pin recursive noise lists and canonical/batch coordinate-fracture consumers."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact

def extract(raw):
    source=raw.decode('utf-8')
    def require(*contracts):
        for contract in contracts:
            if compact(contract) not in compact(source):raise ValueError('Changed noise-fracture contract: '+contract)
    match=re.search(r'@ArrayType\(min = (1), type = IrisNoiseGenerator.class\)\s*@Description\(("(?:[^"\\]|\\.)*")\)\s*private KList<IrisNoiseGenerator> fracture = new KList<>\(\);',source)
    if not match:raise ValueError('Changed fracture list declaration')
    require('@NoArgsConstructor','private long seed = 0;','private IrisGeneratorStyle style = NoiseStyle.IRIS.style();',
            'if (!enabled) { return offsetY; }',
            'double x = xv; double z = zv; int g = 33; for (IrisNoiseGenerator i : fracture) { if (i.isEnabled()) { double fractureOffset = i.getOpacity() / 2D; x += i.getNoise(superSeed + seed + g, xv, zv, data, engine) - fractureOffset; z += i.getNoise(superSeed + seed + g, zv, xv, data, engine) - fractureOffset; } g += 819; } return sample(getGenerator(superSeed, data, engine), x, z);',
            'DoubleBinaryOperator[] children = new DoubleBinaryOperator[fracture.size()]; double[] offsets = new double[fracture.size()]; int salt = 33; for (int index = 0; index < children.length; index++) { IrisNoiseGenerator child = fracture.get(index); if (child.isEnabled()) { children[index] = child.prepareBatch(superSeed + seed + salt, data, engine); offsets[index] = child.getOpacity() / 2D; } salt += 819; }',
            'for (int index = 0; index < children.length; index++) { DoubleBinaryOperator child = children[index]; if (child != null) { x += child.applyAsDouble(xv, zv) - offsets[index]; z += child.applyAsDouble(zv, xv) - offsets[index]; } } return sample(generator, x, z);',
            'g.add(this); for (IrisNoiseGenerator i : getFracture()) { g.addAll(i.getAllComposites()); }',
            'private static final int MINIMUM_FRACTURE_DEPTH = 4;','private static final int MAXIMUM_FRACTURE_NODES = 32;',
            'if (hasDeepFracture())','if (samples.prepare(this, superSeed, data, engine))')
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f','sourceSha256':hashlib.sha256(raw).hexdigest(),
            'description':json.loads(match.group(2)),'minimumEntries':match.group(1),'default.policy':'absentEmpty',
            'sampling.policy':'orderedEnabledChildren','seed.initialSalt':'33','seed.saltStep':'819','child.style':'IRIS','child.seed':'0',
            'native.optimization':'separateFromEditorLimits'}
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__':Path(sys.argv[2]).write_bytes(extract(Path(sys.argv[1]).read_bytes()))
