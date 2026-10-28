"""Reject default/null/range/axis context and native deformation dispatch drift."""
import sys
from extract_procedural_transform_fields import extract,read_sources
sources=read_sources(sys.argv[1]);baseline=extract(sources).decode()
for token in ['translate.x.default=0\n','axis.enabled.default=false\n','axis.interval.default=0\n','axis.yAxis.creation={\\"enabled\\":true,\\"interval\\":90}\n','clamp.minimumHeight.default=-2048\n','stiltSettings.yMax.default=0\n','vacuumSettings.radius.default=0\n','clamp.range.policy=ordered\n','axis.range.policy=equalOrReversed\n','execution.policy=disabled\n']:assert token in baseline,token
for name,old,new in [('IrisProceduralTree',b'placement.setTranslate(translate);',b'placement.setTranslate(null);'),('IrisRuin',b'private IrisObjectRotation rotation = new IrisObjectRotation();',b'private IrisObjectRotation rotation = null;'),('IrisFormation',b'placement.setClamp(clamp);',b'placement.setClamp(null);'),('IrisCoral',b'placement.setStiltSettings(stiltSettings);',b'placement.setStiltSettings(null);'),('IrisFungus',b'placement.setVacuumSettings(vacuumSettings);',b'placement.setVacuumSettings(null);'),('IrisCrystal',b'private IrisObjectTranslate translate = new IrisObjectTranslate();',b'private IrisObjectTranslate translate = null;'),('IrisObjectRotation',b'new IrisAxisRotationClamp(true, false, 0, 0, 90)',b'new IrisAxisRotationClamp()'),('IrisAxisRotationClamp',b'Math.min(min, max), Math.max(min, max)',b'min, max'),('IrisObjectLimit',b'return h <= maximumHeight && l >= minimumHeight;',b'return h <= maximumHeight;'),('IrisStiltSettings',b'private int yMax;',b'private Integer yMax;'),('IrisObjectVacuum',b'case VACUUM_FAST -> 8;',b'case VACUUM_FAST -> 9;'),('IrisObjectShaping',b'!state.hasTileEntity()',b'true')]:
    assert old in sources[name],old
    changed=sources.copy();changed[name]=changed[name].replace(old,new)
    try:extract(changed)
    except ValueError:continue
    raise AssertionError('Accepted procedural nested dispatch drift: '+name)
print('PASS: six procedural dispatch paths, five nested owners, required/optional null policy, implicit stilt defaults, contextual vs inline Y axes, normalized angles, ordered height limits and native deformation/exclusion contracts')
