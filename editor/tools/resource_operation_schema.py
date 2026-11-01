"""Build a closed, source-bound reference schema; unknown shapes/defaults fail closed.
The Java inventory is lexical. Only rules below authorize resource-reference semantics.
"""
from dataclasses import asdict, dataclass
from pathlib import Path
import hashlib, json, re, sys
from java_model_inventory import inventory, tokens, matching

POLICY = json.loads((Path(__file__).resolve().parents[1]/'policy/resource-operation-models.json').read_text())
FILES = POLICY['files']
MODELS = {name for name, row in POLICY['classes'].items() if row['kind']=='class'}
ENUMS = {name for name, row in POLICY['classes'].items() if row['kind']=='enum'}
ROOTS = {'dimensions':'IrisDimension','regions':'IrisRegion','biomes':'IrisBiome',
         'structures':'IrisStructure','jigsaw-pools':'IrisJigsawPool','jigsaw-pieces':'IrisJigsawPiece',
         'generators':'IrisGenerator','expressions':'IrisExpression','image-maps':'IrisImageMap','blocks':'IrisBlockData'}
REFS = {model:family for family,model in ROOTS.items()} | {'IrisObject':'objects','IrisImage':'images'}
# An annotation alone does not establish loader semantics. These exceptions are audited in the pinned source.
OVERRIDES = {
 'IrisBlockData.block':'block-ref',
 'IrisBlockData.data':'opaque-object','IrisBlockData.tileData':'opaque-object','IrisLoot.customNbt':'opaque-object',
 'IrisImageMap.colors':'context-map:regions,biomes','IrisImageMap.fallbackTarget':'context-string:regions,biomes',
 'IrisBiomeGeneratorLink.generator':'fallback-ref:generators|default',
 'IrisDimension.upperDimension':'sentinel-ref:dimensions|none',
 # Provider settings are interpreted by external code; this model cannot certify its pack dependencies.
 'IrisDimension.terrainTransform':'unsupported:external-terrain-transform',
 'IrisDimensionStack.dimensions':'list:reserved-ref:dimensions|none',
 'IrisDimensionCarvingEntry.biome':'trimmed-ref:biomes',
 'IrisImageMapBinding.map':'trimmed-ref:image-maps',
 'IrisStructurePlacement.structures':'list:ref:structures',
 # These are runtime comparisons/dual-domain values, not ordinary loader lookups.
 # A nonempty occurrence blocks the affected target family until its complete semantics are certified.
 'IrisFloatingChildBiomes.carving':'guard:biomes',
 'IrisSubterrainFeature.allowedRegions':'list:guard:regions',
 'IrisDepositGenerator.includedBiomes':'list:guard:biomes',
 'IrisDepositGenerator.excludedBiomes':'list:guard:biomes',
 'IrisStructurePlacement.caveBiomes':'list:guard:biomes',
 'IrisObjectPlacement.allowedCollisions':'list:guard:objects',
 'IrisObjectPlacement.forbiddenCollisions':'list:guard:objects',
}
FOREIGN_REFS = {'IrisSpawner','IrisLootTable','IrisMarker'}
NUMBERS = {'int','long','double','float','Integer','Long','Double','Float','short','byte','Short','Byte'}

def source_path(root,name,relative):
 path=root/(name+'.java')
 if path.exists():return path
 if relative.startswith('@volmlib/'):
  return root/'shared/src/main/java/art/arcane/volmlib'/relative.removeprefix('@volmlib/')
 return root/'core/src/main/java/art/arcane/iris'/relative

def fingerprint(model):
 data={'kind':model.kind,'parent':model.parent,'customNoarg':model.custom_noarg,'fields':[asdict(f) for f in model.fields]}
 return hashlib.sha256(json.dumps(data,sort_keys=True,separators=(',',':')).encode()).hexdigest()

def descriptor(owner,field,texts):
 qualified=owner+'.'+field.name
 kind=field.type
 array=re.fullmatch(r'KList<(.+)>',kind)
 element=array[1] if array else kind
 ref=re.search(r'@RegistryListResource\((\w+)\.class\)',field.annotations)
 if ref:
  assert element=='String','Unsupported resource representation: '+qualified
  assert ref[1] in REFS or ref[1] in FOREIGN_REFS,'Unreviewed resource family: '+qualified
  result='ref:'+REFS[ref[1]] if ref[1] in REFS else 'string'
 elif element in MODELS:result='object:'+element
 elif element in ENUMS or element=='String':result='string'
 elif element in NUMBERS:result='number'
 elif element in ['boolean','Boolean']:result='boolean'
 elif element=='MantleFlag':result='string' # IrisData registers the pinned MantleFlagAdapter (JSON string).
 elif element in {'TriState','StudioMode','CarvingMode','TopShapeMode','FloatingBottomPaletteMode','OverrideMode'}:
  assert inventory(texts[owner],element).kind=='enum','Changed nested enum: '+qualified
  result='string'
 elif element=='KMap<String,String>':result='map:string' # Native IDs/settings, no local resource lookup.
 else:result='unsupported:'+element
 if array:result='list:'+result
 return OVERRIDES.get(qualified,result)

def families(kind,potential):
 while kind.startswith(('list:','map:')):kind=kind.split(':',1)[1]
 if kind=='block-ref':return {'blocks'}
 if kind.startswith(('ref:','fallback-ref:','sentinel-ref:','reserved-ref:','trimmed-ref:')):
  return {kind.split(':',1)[1].split('|',1)[0]}
 if kind.startswith(('guard:','context-')):return set(kind.split(':',1)[1].split(','))
 if kind.startswith('object:'):return potential[kind[7:]]
 if kind.startswith('unsupported:'):return set(REFS.values())
 return set()

class UnresolvedDefault(ValueError):pass
@dataclass
class ObjectDefault:
 model:str
 overrides:dict

def compact(expression):return ''.join(t.value for t in tokens(expression))
def arguments(text):
 """Split a restricted initializer call without confusing nested calls, strings or generics."""
 items=tokens(text);out=[];start=0;depth=0
 for t in items:
  if t.value in ['(','[','{','<']:depth+=1
  elif t.value in [')',']','}','>']:depth-=1
  elif t.value==',' and depth==0:out.append(text[start:t.start]);start=t.end
 if text[start:].strip():out.append(text[start:])
 return out

def evaluate(expression,kind,potential):
 """Interpret only audited initializer forms; never run Java or assume arbitrary factory behavior."""
 if expression is None or compact(expression)=='null':return None
 expression=compact(expression)
 if expression.startswith('"') and expression.endswith('"'):return json.loads(expression)
 if not families(kind,potential):return None
 if expression in ['newKList<>()','newKMap<>()']:return [] if 'KList' in expression else {}
 style=r'(?:NoiseStyle\.\w+\.style\(\)|newIrisGeneratorStyle\(NoiseStyle\.\w+\))(?:\.zoomed\([-+\d.DFdf]+\))?'
 if re.fullmatch(style,expression):return ObjectDefault('IrisGeneratorStyle',{})
 if kind=='object:IrisStyledRange':
  # Both range helpers and the all-args constructor set only min/max and a style built above.
  call=re.fullmatch(r'(?:newIrisStyledRange|range)\((.*)\)',expression)
  if call:
   args=arguments(call[1])
   if len(args)==3 and all(re.fullmatch(r'[-+\d.DFdf]+',x) for x in args[:2]):
    value=ObjectDefault('IrisGeneratorStyle',{}) if expression.startswith('range(') and re.fullmatch(r'[-+\d.DFdf]+',args[2]) else evaluate(args[2],'object:IrisGeneratorStyle',potential)
    if isinstance(value,ObjectDefault):return ObjectDefault('IrisStyledRange',{'style':value})
 # The explicit field object is constructed first; only a small source-audited set of chain methods is accepted.
 call=re.match(r'new(\w+)(?:<[^>]*>)?\(',expression)
 if not call:raise UnresolvedDefault('Unreviewed default factory')
 items=tokens(expression);opening=next(i for i,t in enumerate(items) if t.value=='(');close=matching(items,opening)
 args=arguments(expression[items[opening].end:items[close].start]);name=call[1]
 if name=='KList':value=[evaluate(a,kind.removeprefix('list:'),potential) for a in args] if args else []
 elif name=='IrisBlockData' and len(args)==1 and args[0].startswith('"'):
  value=ObjectDefault(name,{'block':json.loads(args[0])})
 elif name in MODELS and not args:value=ObjectDefault(name,{})
 else:raise UnresolvedDefault('Unreviewed resource-bearing constructor')
 tail=expression[items[close].end:]
 while tail:
  chain=re.match(r'\.(\w+)\(',tail)
  if not chain:raise UnresolvedDefault('Unreviewed default chain')
  ts=tokens(tail);op=next(i for i,t in enumerate(ts) if t.value=='(');end=matching(ts,op)
  args=arguments(tail[ts[op].end:ts[end].start]);method=chain[1]
  if isinstance(value,list) and method=='qadd' and len(args)==1:value.append(evaluate(args[0],kind.removeprefix('list:'),potential))
  elif isinstance(value,ObjectDefault) and value.model=='IrisMaterialPalette' and method=='qclear' and not args:value.overrides['palette']=[]
  elif isinstance(value,ObjectDefault) and value.model=='IrisMaterialPalette' and method=='qadd' and len(args)==1:
   if 'palette' not in value.overrides:raise UnresolvedDefault('Palette append without audited clear')
   value.overrides['palette'].append(ObjectDefault('IrisBlockData',{'block':json.loads(args[0])}))
  elif isinstance(value,ObjectDefault) and value.model=='IrisBiomePaletteLayer' and method=='zero' and not args:value.overrides['palette']=[]
  elif isinstance(value,ObjectDefault) and value.model=='IrisRiverMaterialConfig' and method=='setPalette' and len(args)==1:value.overrides['palette']=evaluate(args[0],'object:IrisMaterialPalette',potential)
  elif isinstance(value,ObjectDefault) and value.model=='IrisRiverMaterialConfig' and method in ['setEnabled','setDepth'] and len(args)==1 and re.fullmatch(r'(?:true|false|[-+\d]+)',args[0]):pass
  else:raise UnresolvedDefault('Unreviewed resource-bearing initializer method')
  tail=tail[ts[end].end:]
 return value

def extract(root):
 texts={};evidence={};models={}
 for name,relative in FILES.items():
  data=source_path(root,name,relative).read_bytes();texts[name]=data.decode();evidence[relative]=hashlib.sha256(data).hexdigest()
 for name,row in POLICY['classes'].items():
  model=inventory(texts[name],name)
  assert fingerprint(model)==row['declarationSha256'],'Changed serialized declaration/default/annotation: '+name
  assert not model.custom_noarg,'Custom no-arg constructor needs explicit default policy: '+name
  models[name]=model
 # Inherited members are inventoried with the same rules. Shadowing would make JSON/default semantics ambiguous.
 merged={}
 def fields(name,active=()):
  assert name not in active,'Cyclic model inheritance'
  if name not in merged:
   m=models[name];inherited=fields(m.parent,active+(name,)).copy() if m.parent else {}
   for f in m.fields:
    assert f.name not in inherited,'Serialized field shadow: '+name+'.'+f.name
    inherited[f.name]=(name,f)
   merged[name]=inherited
  return merged[name]
 result={'root.'+family:model for family,model in ROOTS.items()};definitions={}
 for name in sorted(MODELS):
  definitions[name]={}
  for field,(owner,f) in fields(name).items():
   kind=descriptor(owner,f,texts);definitions[name][field]=(f,kind)
   result[name+'.'+field]=kind
  result[name+'.fields']=','.join(definitions[name])
 potential={name:set() for name in MODELS}
 while True:
  next_={n:set().union(*(families(k,potential) for _,k in fs.values())) for n,fs in definitions.items()}
  if next_==potential:break
  potential=next_
 def implicit(kind,value,pointer='',active=()):
  if value is None:
   if kind.startswith('fallback-ref:'):
    family,key=kind.split(':',1)[1].split('|',1);return [(family,pointer,key)]
   return []
  if kind.startswith('list:'):
   if not isinstance(value,list):raise UnresolvedDefault('Unknown list default')
   return [r for i,v in enumerate(value) for r in implicit(kind[5:],v,pointer+'/'+str(i),active)]
  if kind.startswith('object:'):
   name=kind[7:]
   if not isinstance(value,ObjectDefault) or value.model!=name:raise UnresolvedDefault('Unknown object default')
   if name in active:raise UnresolvedDefault('Recursive non-null default')
   refs=[]
   for field,(f,child) in definitions[name].items():
    if not families(child,potential):continue
    v=value.overrides[field] if field in value.overrides else evaluate(f.default,child,potential)
    refs+=implicit(child,v,pointer+'/'+field,active+(name,))
   return refs
  if kind=='block-ref' or kind.startswith(('ref:','trimmed-ref:','fallback-ref:','sentinel-ref:','reserved-ref:')):
   if not isinstance(value,str):raise UnresolvedDefault('Non-string resource default')
   family='blocks' if kind=='block-ref' else kind.split(':',1)[1].split('|',1)[0]
   if kind.startswith('fallback-ref:') and not value.strip():value=kind.split('|',1)[1]
   if not value or (kind.startswith('sentinel-ref:') and value.lower()==kind.split('|',1)[1]):return []
   return [(family,pointer,value)]
  if kind.startswith(('context-','guard:')) and value:raise UnresolvedDefault('Context-dependent default')
  if kind.startswith('unsupported:'):raise UnresolvedDefault('Unsupported default type')
  return []
 for name,fs in definitions.items():
  for field,(f,kind) in fs.items():
   key=name+'.'+field
   if not families(kind,potential):continue
   try:refs=implicit(kind,evaluate(f.default,kind,potential))
   except (UnresolvedDefault,ValueError) as failure:
    result[key+'.defaultBlock']=','.join(sorted(families(kind,potential)))
    result[key+'.defaultReason']=str(failure);continue
   if refs:
    result[key+'.implicit.count']=str(len(refs))
    for i,(family,pointer,value) in enumerate(refs):result[key+'.implicit.'+str(i)]=family+'|'+pointer.lstrip('/')+'|'+value
    if len(refs)==1:result[key+'.implicit']=result[key+'.implicit.0'] # Existing diagnostic contract.
 return result,evidence

if __name__=='__main__':
 root=Path(sys.argv[1]);output=Path(sys.argv[2]);manifest=Path(sys.argv[3])
 values,evidence=extract(root);lock=json.loads(Path('iris.lock.json').read_text());bound=json.loads(manifest.read_text())
 assert bound['irisRevision']==lock['iris']['revision'] and bound['volmlibRevision']==lock['volmlib']['revision'] and bound['sources']==evidence,'Unreviewed source drift'
 output.write_bytes(('\n'.join(k+'='+v for k,v in sorted(values.items()))+'\n').encode('ascii'))
 print('Generated closed typed resource-operation schema from '+str(len(evidence))+' pinned sources')
