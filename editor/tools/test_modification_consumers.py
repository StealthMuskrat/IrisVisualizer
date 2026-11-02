"""Deferred source-audit contracts; does not run Iris, consume a pack or authorize recipe edits."""
from pathlib import Path
import copy,json,shutil,sys,tempfile
from audit_modification_consumers import ROOT,MANIFEST,audit,verify_policy,reference_sites,production_path

def rejects(action):
    try:action()
    except (ValueError,FileNotFoundError):return
    raise AssertionError('Expected modification source/policy drift rejection')

source=Path(sys.argv[1]);policy_file=ROOT/'editor/policy/field-policy.json';policy=json.loads(policy_file.read_text());manifest=json.loads(MANIFEST.read_text())
assert audit(source,policy_file)==(1778,30)
assert reference_sites('IrisMod x; // IrisMod\n String s="art.arcane.iris.pack.mod.IrisMod";',{'IrisMod'})=={'IrisMod':2}
assert reference_sites('/* IrisMod */ String s="other";',{'IrisMod'})=={}
assert reference_sites(r'\u0049risMod value;',{'IrisMod'})=={'IrisMod':1}
assert production_path('core/src/main/java/Any.java') and production_path('adapters/forge/src/versions/1.21.1/java/Any.java')
for excluded in ['core/src/test/java/Any.java','core/src/offline/java/Any.java','core/src/testFixtures/java/Any.java','build/Any.java']:
    assert not production_path(excluded)
for change in ['generation','unclassified','reason','missing']:
    altered=copy.deepcopy(policy)
    if change=='missing':del altered['mod']
    elif change=='reason':altered['mod']['overrideFluidHeight']['reason']=' '
    else:altered['mod']['overrideFluidHeight']['classification']=change
    rejects(lambda:verify_policy(altered))
changed=copy.deepcopy(manifest);changed['sources'].pop(next(iter(changed['sources'])));rejects(lambda:verify_policy(policy,changed))
build=ROOT/'build';build.mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix='mod-audit-',dir=build) as directory:
    temp=Path(directory);cache=temp/'src';shutil.copytree(source,cache)
    extra=cache/'core/src/main/java/UnexpectedRecipeConsumer.java';extra.parent.mkdir(parents=True,exist_ok=True);extra.write_text('class UnexpectedRecipeConsumer { IrisMod recipe; }')
    rejects(lambda:audit(cache,policy_file));extra.unlink()
    data=cache/'core/src/main/java/art/arcane/iris/pack/loading/IrisData.java';original=data.read_bytes();data.write_bytes(original+b'\n// source changed\n');rejects(lambda:audit(cache,policy_file));data.write_bytes(original)
    data.unlink();rejects(lambda:audit(cache,policy_file));data.write_bytes(original)
    lock=temp/'lock.json';lock.write_text(json.dumps({'iris':{'revision':'0'*40}}));rejects(lambda:verify_policy(policy,manifest,lock))
print('PASS: pinned complete production-source scope, exact blob/consumer evidence, lexer comment/literal/Unicode handling, read-only policy/revision guards and missing/extra/changed source rejection')
