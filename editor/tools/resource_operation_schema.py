"""Build a closed, source-bound reference schema; unknown shapes/defaults fail closed.
The Java inventory is lexical. Only rules below authorize resource-reference semantics.
"""
from dataclasses import dataclass
from pathlib import Path
import hashlib, json, re, sys
from java_model_inventory import inventory, tokens, matching, annotation_arguments

from iris_source_inventory import POLICY, FILES, MODELS, ENUMS, JSON_ROOTS, source_path, fingerprint, owner_folder, load_inventory

READ_ONLY_ROOTS = {family:JSON_ROOTS[family] for family in ['loot','spawners','entities','markers','mods']}
ROOTS = {family:model for family,model in JSON_ROOTS.items() if family not in READ_ONLY_ROOTS}
OWNERS = JSON_ROOTS
# Exact root ordering document, outside every loader/snippet root. Strings cannot encode typed pack lookups.
METADATA = {'Schema.json':'list:string'}
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
 # Each comparison needs a consumer-specific rule; uncertified cases retain family guards.
 'IrisFloatingChildBiomes.carving':'dual-carving:biomes',
 'IrisSubterrainFeature.allowedRegions':'list:exact-key:regions',
 'IrisDepositGenerator.includedBiomes':'list:dual-deposit:biomes',
 'IrisDepositGenerator.excludedBiomes':'list:dual-deposit:biomes',
 'IrisStructurePlacement.caveBiomes':'list:cave-match:biomes',
 'IrisObjectPlacement.allowedCollisions':'list:exact-key:objects',
 'IrisObjectPlacement.forbiddenCollisions':'list:exact-key:objects',
}
FOREIGN_REFS = set(READ_ONLY_ROOTS.values())
NUMBERS = {'int','long','double','float','Integer','Long','Double','Float','short','byte','Short','Byte'}

def descriptor(owner,field,texts):
 qualified=owner+'.'+field.name
 kind=field.type
 array=re.fullmatch(r'KList<(.+)>',kind)
 element=array[1] if array else kind
 ref_args=annotation_arguments(field.annotations,'RegistryListResource')
 ref=None
 if ref_args is not None:
  assert len(ref_args)==3 and ref_args[1:]==('.', 'class'),'Unreviewed resource annotation: '+qualified
  ref=ref_args[0]
 if ref:
  assert element=='String','Unsupported resource representation: '+qualified
  assert ref in REFS or ref in FOREIGN_REFS,'Unreviewed resource family: '+qualified
  result='ref:'+REFS[ref] if ref in REFS else 'string'
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
 if owner.startswith('IrisMod') and ref and ref in REFS:
  result=('list:' if array else '')+'guard:'+REFS[ref]
 if qualified=='IrisMod.forDimension':result='guard:dimensions'
 return OVERRIDES.get(qualified,result)

def families(kind,potential):
 while kind.startswith(('list:','map:')):kind=kind.split(':',1)[1]
 if kind=='block-ref':return {'blocks'}
 if kind.startswith(('ref:','fallback-ref:','sentinel-ref:','reserved-ref:','trimmed-ref:','exact-key:','dual-carving:','dual-deposit:','cave-match:')):
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

def audit_object_collision_context(texts):
 """Bind exact comparisons to loaded marker identities, never arbitrary matching pack strings."""
 def require(name,*contracts):
  source=compact(texts[name])
  for contract in contracts:assert compact(contract) in source,'Changed object collision consumer: '+name+' '+contract
 require('IrisObjectPlacementRunner','PlacedObject p = engine.resolveObjectPlacementMarker(i, k, placementMarker);',
         'IrisObject o = p.getObject();','String key = o.getLoadKey();',
         'if (config.getForbiddenCollisions().contains(key) && !config.getAllowedCollisions().contains(key)) { return -1; }')
 require('Engine','StructurePlacementMarker.Decoded marker = StructurePlacementMarker.decode(objectAt);',
         'String object = marker.objectKey();','if (object.startsWith("procedural/")) { return null; }',
         'return new PlacedObject(null, getData().getObjectLoader().load(object), id, x, z);')
 require('ObjectResourceLoader','t.setLoadKey(name);',
         'private IrisObject loadRaw(String name) { File file = resolveFile(name, ".iob"); return file == null ? null : loadFile(file, name); }')
 require('MantleObjectComponent','String key = object == null ? null : object.getLoadKey();','return key + "@" + id;')
 require('PlacedObject','@AllArgsConstructor','private IrisObjectPlacement placement;','private IrisObject object;','private int id;','private int xx;','private int zz;')
 require('StructurePlacementMarker','int separator = marker.indexOf(\'@\');',
         'if (separator <= 0 || separator != marker.lastIndexOf(\'@\') || separator == marker.length() - 1) { return null; }',
         'String objectKey = marker.substring(0, separator);',
         'return new Decoded(objectKey, Integer.parseInt(marker.substring(separator + 1)), null);',
         'return new Decoded(objectKey, placementId, structureKey);')

def audit_cave_qualified_lookup(texts):
 """Qualified comparisons and the unmodified raw compatibility lookup are separate consumers."""
 def require(name,*contracts):
  source=compact(texts[name])
  for contract in contracts:assert compact(contract) in source,'Changed cave compatibility lookup: '+name+' '+contract
 require('ResourceLoader','this.folderName = folderName;',
         'return resolveFile(name, extension, getFolders(name));',
         'if (i.getName().equals(folderName)) { fc.add(i); break; }',
         'if (rc.contains(":")) { for (File i : folders.copy()) { if (!rc.startsWith(i.getName() + ":")) { folders.remove(i); } } }',
         'File exact = new File(folder, name + extension);')
 require('IrisData','this.biomeLoader = registerLoader(IrisBiome.class, replacement);',
         'r = new ResourceLoader<>(dataFolder, this, rr.getFolderName(), rr.getTypeName(), registrant, options);')
 require('IrisStructurePlacement','String key = caveBiomes.get(index); IrisBiome biome = data.getBiomeLoader().load(key);')

def extract(root):
 catalog=load_inventory(root);models,texts,evidence=catalog.models,catalog.texts,catalog.evidence
 audit_object_collision_context(texts)
 audit_cave_qualified_lookup(texts)
 for name,model in models.items():assert not model.custom_noarg,'Custom no-arg constructor needs explicit default policy: '+name
 fields=catalog.fields
 result={'root.'+family:model for family,model in ROOTS.items()} | {'owner.'+family:model for family,model in OWNERS.items()} | {'metadata.'+path:kind for path,kind in METADATA.items()};definitions={}
 for label,name in catalog.snippets().items():result['snippet.'+label]=name;result[name+'.snippet']=label
 for name in sorted(MODELS):
  definitions[name]={}
  for field,(owner,f) in fields(name).items():
   assert field not in {'fields','snippet'},'Serialized field collides with schema control metadata: '+name+'.'+field
   kind=descriptor(owner,f,texts);definitions[name][field]=(f,kind)
   result[name+'.'+field]=kind
  result[name+'.fields']=','.join(definitions[name])
 # IrisBiome's absent derivative stays a native default, never an inserted JSON field.
 derivative=definitions['IrisBiome']['derivative'][0].default
 default_derivative=json.loads(compact(derivative))
 assert isinstance(default_derivative,str) and re.fullmatch(r'[A-Za-z0-9:/_.-]+',default_derivative),'Unreviewed biome derivative default'
 result['context.biome.derivative.default']=default_derivative
 result['context.objectCollision.policy']='exactLoadKeyWithProceduralGuard'
 result['context.cave.qualifiedCompatibility']='rawBiomeFolderFilter'
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
  if kind.startswith(('context-','guard:','exact-key:','dual-carving:','dual-deposit:','cave-match:')) and value:raise UnresolvedDefault('Context-dependent default')
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
