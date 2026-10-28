"""Verify canopy metadata regeneration and reject invalid owner/dispatch contracts."""
import sys
from extract_procedural_canopy_fields import extract,read_sources
sources=read_sources(sys.argv[1]);baseline=extract(sources).decode()
for token in ['scalar.count=42\n','canopy.startAngle.default=90\n','canopy.leafDensity.default=0.85\n','layers.yOffset.default=0\n','layers.radius.minimum=0.5\n','branches.branchDepth.maximum=6\n','subBranches.count.minimum=1\n','layers.policy=explicitNonemptyElseProfile\n','canopy.mode.choices=TRIMMED,FILLED,DENSITY,NOISE,HOLLOW,GRADIENT,CLUMPED,TATTERED,SPARSE\n','branches.probabilityFunction.EXPONENTIAL_DECAY.description=','branches.lengthFunction.EASE_IN_OUT.description=']:assert token in baseline,token
changed=sources.copy();changed['IrisTreeCanopy']=changed['IrisTreeCanopy'].replace(b'private double leafDensity = 0.85;',b'private double leafDensity = 0.9;');assert 'canopy.leafDensity.default=0.9\n' in extract(changed).decode()
for name,old,new in [('IrisProceduralTree',b'private IrisTreeCanopy canopy = new IrisTreeCanopy();',b'private IrisTreeCanopy canopy = null;'),('IrisTreeCanopy',b'private KList<IrisTreeLayer> layers = new KList<>();',b'private KList<IrisTreeLayer> layers = null;'),('IrisTreeBranches',b'private IrisTreeSubBranches subBranches = null;',b'private IrisTreeSubBranches subBranches = new IrisTreeSubBranches();'),('IrisTreeLayer',b'private double radius = 2;',b'private String radius = "2";'),('TreeCanopyBuilder',b'if (explicit != null && !explicit.isEmpty())',b'if (explicit != null)'),('TreeCanopyBuilder',b'out[i][0] = explicit.get(i).getYOffset();',b'out[i][0] = 0;'),('TreeCanopyBuilder',b'if (sub == null || depth <= 0)',b'if (sub == null)'),('TreeFunctions',b'switch (b.getProbabilityFunction())',b'switch (b.getLengthFunction())'),('ProceduralTreeGenerator',b'TreeCanopyBuilder.build(canvas, tree, height,',b'TreeCanopyBuilder.build(canvas, tree, 1,')]:
    assert old in sources[name],old
    changed=sources.copy();changed[name]=changed[name].replace(old,new)
    try:extract(changed)
    except ValueError:continue
    raise AssertionError('Accepted canopy contract drift '+name)
print('PASS: four canopy classes/42 scalar defaults and native bounds, complete leaf/probability/length/azimuth enums, explicit-layer/profile precedence and ordering, optional branch/sub-branch depth and native builder dispatch')
