"""Deferred coverage generation contracts: policy stores only audited classifications/reasons."""
from pathlib import Path
import json,sys,tempfile
from field_coverage import run

source=Path(sys.argv[1]);root=Path(__file__).resolve().parents[2];policy=root/'editor/policy/field-policy.json';java=root/'editor/src/main/java/dev/irisvisualizer/editor'
with tempfile.TemporaryDirectory(prefix='iris-coverage-') as folder:
    temp=Path(folder);output=temp/'generated';totals=run(source,policy,java,output)
    assert totals=={'fields':1462,'generation':1237,'implemented':1237,'read-only':196,'unclassified':29,'missingPolicies':0}
    assert (output/'field-coverage.properties').read_bytes()==(root/'editor/src/main/resources/field-coverage.properties').read_bytes()
    assert (output/'coverage-report.md').read_bytes()==(root/'editor/coverage-report.md').read_bytes()
    text=(output/'field-coverage.properties').read_text()
    assert 'treeSettings.fields=enabled,mode\n' in text
    assert 'entity.passengers.kind=list:object:IrisEntity\n' in text
    assert 'mod.overrideFluidHeight.classification=unclassified\n' in text
    for change in ['schema','editable','missing','unknown','null','blank']:
        data=json.loads(policy.read_text())
        if change=='schema':data['biome']['name']['default']='invented'
        elif change=='editable':data['biome']['name']['classification']='generation'
        elif change=='missing':data['biome']['invented']={'classification':'read-only','reason':'Not declared'}
        elif change=='unknown':data['invented']={}
        elif change=='null':data['biome']['name']=None
        else:data['biome']['name']['reason']='  '
        # Turning a read-only name into generation is visible as pending, never falsely implemented.
        altered=temp/'policy.json';altered.write_text(json.dumps(data))
        if change=='editable':
            pending=run(source,altered,java,temp/change)
            assert pending['generation']==1238 and pending['implemented']==1237
            continue
        try:run(source,altered,java,temp/change)
        except ValueError:pass
        else:raise AssertionError('Accepted invalid policy shape/source boundary: '+change)
print('PASS: complete shared-source coverage regeneration, exact policy-only structure, missing/unknown declarations, explicit pending controls and honest unclassified scope')
