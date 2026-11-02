"""Deferred shared-inventory contracts: inheritance and closure are independent of edit policy."""
from pathlib import Path
import sys
from java_model_inventory import Model, Field
from iris_source_inventory import Inventory, load_inventory, JSON_ROOTS

def model(name,fields=(),parent=None):
    return Model(name,'class',parent,tuple(Field(n,t,None,'') for n,t in fields),False,'')

def rejects(action):
    try:action()
    except AssertionError:return
    raise AssertionError('Accepted ambiguous shared declaration graph')

root=model('IrisBlockData',[('palette','KList<IrisMaterialPalette>')],'IrisRegistrant')
base=model('IrisRegistrant',[('inherited','String')])
palette=model('IrisMaterialPalette',[('backup','IrisBlockData')])
catalog=Inventory({m.name:m for m in [root,base,palette]},{},{})
assert list(catalog.fields(root.name)) == ['inherited','palette']
assert catalog.fields(root.name)['inherited'][0] == base.name
assert catalog.reachable(root.name) == [root.name,palette.name]
rejects(lambda:Inventory({root.name:model(root.name,[('inherited','String')],base.name),base.name:base},{},{}).fields(root.name))
rejects(lambda:Inventory({root.name:model(root.name,(),base.name),base.name:model(base.name,(),root.name)},{},{}).fields(root.name))
rejects(lambda:Inventory({root.name:model(root.name,[('future','KList<IrisFuture>')])},{},{}).children(root.name))
if len(sys.argv)>1:
    pinned=load_inventory(Path(sys.argv[1]),verify_sources=True)
    assert len(JSON_ROOTS)==15 and len(pinned.snippets())==73
    assert sum(len(m.fields) for m in pinned.models.values())==1462
    assert all(root in pinned.models for root in JSON_ROOTS.values())
print('PASS: shared source declaration inheritance, owner attribution, finite recursive type closure, shadow/cyclic-inheritance/unknown-nested rejection and pinned model graph')
