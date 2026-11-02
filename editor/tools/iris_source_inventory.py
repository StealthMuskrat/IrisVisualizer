"""One pinned serialized-declaration inventory for UI coverage and reference planning.
This module inventories source; it grants no edit, file-operation or generation authority.
"""
from dataclasses import asdict, dataclass
from pathlib import Path
import hashlib, json, re
from java_model_inventory import inventory, tokens, annotation_arguments

EDITOR = Path(__file__).resolve().parents[1]
POLICY = json.loads((EDITOR/'policy/resource-operation-models.json').read_text())
FILES = POLICY['files']
MODELS = {name for name,row in POLICY['classes'].items() if row['kind']=='class'}
ENUMS = {name for name,row in POLICY['classes'].items() if row['kind']=='enum'}
JSON_ROOTS = {'dimensions':'IrisDimension','regions':'IrisRegion','biomes':'IrisBiome',
 'structures':'IrisStructure','jigsaw-pools':'IrisJigsawPool','jigsaw-pieces':'IrisJigsawPiece',
 'generators':'IrisGenerator','expressions':'IrisExpression','image-maps':'IrisImageMap','blocks':'IrisBlockData',
 'loot':'IrisLootTable','spawners':'IrisSpawner','entities':'IrisEntity','markers':'IrisMarker','mods':'IrisMod'}

def source_path(root,name,relative):
 path=root/(name+'.java')
 if path.exists():return path
 if relative.startswith('@volmlib/'):
  return root/'shared/src/main/java/art/arcane/volmlib'/relative.removeprefix('@volmlib/')
 return root/'core/src/main/java/art/arcane/iris'/relative

def fingerprint(model):
 data={'kind':model.kind,'parent':model.parent,'customNoarg':model.custom_noarg,'annotations':model.annotations,'fields':[asdict(f) for f in model.fields]}
 return hashlib.sha256(json.dumps(data,sort_keys=True,separators=(',',':')).encode()).hexdigest()

def owner_folder(text):
 items=tokens(text);found=[]
 for i,t in enumerate(items):
  if i<1 or t.value!='getFolderName' or items[i-1].value!='String':continue
  body=[v.value for v in items[i+1:i+8]]
  if len(body)==7 and body[:4]==['(',')','{','return'] and body[5:]==[';','}']:found.append(json.loads(body[4]))
 assert len(found)==1,'Unreviewed resource-owner folder getter'
 return found[0]

@dataclass
class Inventory:
 models:dict
 texts:dict
 evidence:dict
 def fields(self,name,active=()):
  assert name not in active,'Cyclic model inheritance: '+name
  model=self.models[name];result=self.fields(model.parent,active+(name,)) if model.parent else {}
  for field in model.fields:
   assert field.name not in result,'Serialized field shadow: '+name+'.'+field.name
   result[field.name]=(name,field)
  return result
 def children(self,name):
  result=[]
  for _,field in self.fields(name).values():
   for child in re.findall(r'\b[A-Za-z_$][\w$]*\b',field.type):
    assert not child.startswith('Iris') or child in self.models,'Uninventoried nested source type: '+name+'.'+field.name+' -> '+child
    if child in MODELS and child not in result:result.append(child)
  return result
 def snippets(self):
  result={}
  for name in sorted(MODELS):
   arguments=annotation_arguments(self.models[name].annotations,'Snippet')
   if arguments is None:continue
   assert len(arguments)==1,'Unreviewed snippet annotation: '+name
   label=json.loads(arguments[0]);assert re.fullmatch(r'[a-z0-9-]+',label),'Unsupported snippet kind'
   assert label not in result,'Ambiguous snippet owner kind: '+label
   result[label]=name
  return result
 def reachable(self,name):
  result=[];pending=[name]
  while pending:
   current=pending.pop(0)
   if current in result:continue
   result.append(current);pending.extend(child for child in self.children(current) if child not in result)
  return result

def load_inventory(root,verify_sources=False):
 texts={};evidence={};models={}
 for name,relative in FILES.items():
  raw=source_path(root,name,relative).read_bytes();texts[name]=raw.decode('utf-8');evidence[relative]=hashlib.sha256(raw).hexdigest()
 for name,row in POLICY['classes'].items():
  model=inventory(texts[name],name)
  assert fingerprint(model)==row['declarationSha256'],'Changed serialized declaration/default/annotation: '+name
  models[name]=model
 for family,model in JSON_ROOTS.items():assert owner_folder(texts[model])==family,'Changed resource-owner folder: '+model
 if verify_sources:
  lock=json.loads((EDITOR.parent/'iris.lock.json').read_text());bound=json.loads((EDITOR/'policy/resource-operation-sources.json').read_text())
  assert bound['irisRevision']==lock['iris']['revision'] and bound['volmlibRevision']==lock['volmlib']['revision'] and evidence==bound['sources'],'Unreviewed source/revision drift'
 result=Inventory(models,texts,evidence)
 for name in MODELS:result.children(name) # Certify inherited and nested class closure even for otherwise unused models.
 return result
