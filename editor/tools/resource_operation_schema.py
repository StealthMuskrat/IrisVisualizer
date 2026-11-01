"""Generate a closed reference-owner schema for guarded typed resource operations.
Unknown owners/fields/types block operations; this is not full Iris reference coverage.
"""
from pathlib import Path
import hashlib,json,re,sys
FILES={
 'IrisRegistrant':'pack/loading/IrisRegistrant.java','ResourceLoader':'pack/loading/ResourceLoader.java','ObjectResourceLoader':'pack/loading/ObjectResourceLoader.java',
 'IrisStructure':'structure/placement/IrisStructure.java','IrisJigsawPool':'structure/jigsaw/IrisJigsawPool.java','IrisJigsawPiece':'structure/jigsaw/IrisJigsawPiece.java','IrisJigsawPieceEntry':'structure/jigsaw/IrisJigsawPieceEntry.java','IrisJigsawConnector':'structure/jigsaw/IrisJigsawConnector.java','IrisJigsawPieceRules':'structure/jigsaw/IrisJigsawPieceRules.java','IrisPosition':'pack/value/IrisPosition.java','IrisJigsawWorkcell':'structure/jigsaw/IrisJigsawWorkcell.java','IrisJigsawThemeSet':'structure/jigsaw/IrisJigsawThemeSet.java',
 'IrisDirection':'pack/value/IrisDirection.java','JigsawJoint':'structure/jigsaw/JigsawJoint.java','IrisJigsawMode':'structure/jigsaw/IrisJigsawMode.java','IrisJigsawCompatibility':'structure/jigsaw/IrisJigsawCompatibility.java','IrisJigsawBranchFailurePolicy':'structure/jigsaw/IrisJigsawBranchFailurePolicy.java','ObjectPlaceMode':'structure/object/ObjectPlaceMode.java','IrisJigsawWorkcellArchetype':'structure/jigsaw/IrisJigsawWorkcellArchetype.java'}
MODELS={'IrisStructure','IrisJigsawPool','IrisJigsawPiece','IrisJigsawPieceEntry','IrisJigsawConnector','IrisJigsawPieceRules','IrisPosition','IrisJigsawWorkcell','IrisJigsawThemeSet'}
ENUMS={'IrisDirection','JigsawJoint','IrisJigsawMode','IrisJigsawCompatibility','IrisJigsawBranchFailurePolicy','ObjectPlaceMode','IrisJigsawWorkcellArchetype'}
REFS={'IrisJigsawPool':'jigsaw-pools','IrisJigsawPiece':'jigsaw-pieces','IrisObject':'objects'}
# Qualified paths with @volmlib belong to the separately pinned dependency.
FILES.update({
 'IrisGenerator':'generation/noise/IrisGenerator.java',
 'IrisNoiseGenerator':'generation/noise/IrisNoiseGenerator.java',
 'IrisGeneratorStyle':'generation/noise/IrisGeneratorStyle.java',
 'IrisInterpolator':'generation/noise/IrisInterpolator.java',
 'IrisExpression':'generation/noise/IrisExpression.java',
 'IrisExpressionLoad':'generation/noise/IrisExpressionLoad.java',
 'IrisExpressionFunction':'generation/noise/IrisExpressionFunction.java',
 'NoiseStyle':'generation/noise/NoiseStyle.java',
 'InterpolationMethod':'@volmlib/util/interpolation/InterpolationMethod.java',
 'IrisEngineStreamType':'generation/runtime/IrisEngineStreamType.java',
 'IrisEngineValueType':'generation/runtime/IrisEngineValueType.java',
 'IrisImageMap':'generation/image/IrisImageMap.java',
 'IrisImageMapOrigin':'generation/image/IrisImageMapOrigin.java',
 'IrisBlockData':'generation/block/IrisBlockData.java',
 'IrisMaterialPalette':'generation/terrain/IrisMaterialPalette.java',
 'IrisObjectReplace':'structure/object/IrisObjectReplace.java',
 'ImageResourceLoader':'pack/loading/ImageResourceLoader.java',
 'IrisImageMapRuntime':'generation/image/IrisImageMapRuntime.java',
 'IrisImageMapCompiler':'generation/image/IrisImageMapCompiler.java',
})
for name in ['IrisImageMapType','IrisImageMapRotation','IrisImageMapSampling',
             'IrisImageMapOutOfBounds','IrisImageMapAlpha','IrisImageMapUnknownColor']:
 FILES[name]='generation/image/'+name+'.java'
 ENUMS.add(name)
MODELS.update({'IrisGenerator','IrisNoiseGenerator','IrisGeneratorStyle','IrisInterpolator',
               'IrisExpression','IrisExpressionLoad','IrisExpressionFunction','IrisImageMap',
               'IrisImageMapOrigin','IrisBlockData','IrisMaterialPalette','IrisObjectReplace'})
ENUMS.update({'NoiseStyle','InterpolationMethod','IrisEngineStreamType','IrisEngineValueType'})
REFS.update({'IrisExpression':'expressions','IrisImageMap':'image-maps','IrisImage':'images'})
ROOTS={'structures':'IrisStructure','jigsaw-pools':'IrisJigsawPool','jigsaw-pieces':'IrisJigsawPiece',
       'generators':'IrisGenerator','expressions':'IrisExpression','image-maps':'IrisImageMap','blocks':'IrisBlockData'}
EXPECTED_REFS={
 'IrisStructure.startPool':'ref:jigsaw-pools','IrisJigsawPool.fallback':'ref:jigsaw-pools',
 'IrisJigsawPiece.object':'ref:objects','IrisJigsawPieceEntry.piece':'ref:jigsaw-pieces',
 'IrisJigsawConnector.pool':'ref:jigsaw-pools','IrisGeneratorStyle.expression':'ref:expressions',
 'IrisGeneratorStyle.imageMap':'ref:image-maps','IrisImageMap.source':'ref:images'}
OVERRIDES={
 'IrisBlockData.block':'block-ref',
 'IrisBlockData.data':'opaque-object','IrisBlockData.tileData':'opaque-object',
 # Runtime selects region/biome targets using the owning binding; surface targets use the vanilla registry,
 # not the block-resource loader. Never guess a resource family from the string spelling.
 'IrisImageMap.colors':'context-map:regions,biomes',
 'IrisImageMap.fallbackTarget':'context-string:regions,biomes'}
IMPLICIT={
 'IrisBlockData.block':'blocks||air',
 'IrisMaterialPalette.palette':'blocks|0/block|STONE',
 'IrisObjectReplace.replace':'blocks|palette/0/block|STONE'}

def source_path(root,name,relative):
 path=root/(name+'.java')
 if path.exists(): return path
 if relative.startswith('@volmlib/'):
  return root/'shared/src/main/java/art/arcane/volmlib'/relative.removeprefix('@volmlib/')
 return root/'core/src/main/java/art/arcane/iris'/relative

def extract(root):
 seen_refs={};defaults={}
 result={'root.'+family:model for family,model in ROOTS.items()};evidence={}
 for name,relative in FILES.items():
  data=source_path(root,name,relative).read_bytes();evidence[relative]=hashlib.sha256(data).hexdigest()
  text=re.sub(r'/\*.*?\*/','',data.decode(),flags=re.S)
  if name=='IrisRegistrant':
   for declaration in re.finditer(r'^    (?:private|protected|public) (?P<mods>(?:(?:transient|static|final|volatile) )*)[\w<>?, ]+ \w+\s*(?:=[^;]+)?;',text,re.M):
    assert {'transient','static'} & set(declaration['mods'].split()),'Unreviewed serialized inherited field'
  if name in ENUMS:assert re.search(r'\benum\s+'+name+r'\b',text),'Changed enum kind'
  if name not in MODELS:continue
  fields=[]
  for match in re.finditer(r'^    private (?P<mods>(?:(?:transient|static|final|volatile) )*)(?P<type>[\w<>?, ]+) (?P<name>\w+)\s*(?:=\s*(?P<default>[^;]+))?;',text,re.M):
   if any(x in match['mods'].split() for x in ['static','transient']):continue
   field=match['name'];kind=match['type'].strip();qualified=name+'.'+field
   before=text[:match.start()];previous=max(before.rfind(';'),before.rfind('}'),before.rfind('{'))
   annotations=before[previous+1:];ref=re.search(r'@RegistryListResource\((Iris\w+)\.class\)',annotations)
   array=re.fullmatch(r'KList<(.+)>',kind);element=array[1] if array else kind
   default=(match['default'] or 'null').strip();defaults[qualified]=default
   if ref and ref[1] in REFS:
    assert element=='String','Unsupported resource-reference representation'
    descriptor='ref:'+REFS[ref[1]];seen_refs[qualified]=descriptor
    assert default in ['null','""','new KList<>()'],'Nonempty implicit resource reference default'
   elif element in MODELS:descriptor='object:'+element
   elif element in ENUMS or element=='String':descriptor='string'
   elif element in ['int','long','double','float','Integer','Long','Double','Float']:descriptor='number'
   elif element in ['boolean','Boolean']:descriptor='boolean'
   else:descriptor='unsupported:'+element
   if array:descriptor='list:'+descriptor
   fields.append(field);result[qualified]=OVERRIDES.get(qualified,descriptor)
  assert fields,'No direct model fields';result[name+'.fields']=','.join(fields)
 assert seen_refs==EXPECTED_REFS,'Changed certified reference fields'
 # These implicit block aliases must be reviewed without materializing omitted JSON fields.
 assert defaults['IrisBlockData.block']=='"air"'
 assert defaults['IrisMaterialPalette.palette']=='new KList<IrisBlockData>().qadd(new IrisBlockData("STONE"))'
 assert defaults['IrisObjectReplace.replace']=='new IrisMaterialPalette()'
 for field,value in IMPLICIT.items():result[field+'.implicit']=value
 # Explicit shape overrides are tied to their actual serialized representations.
 assert 'IrisBlockData.data' in result and 'IrisBlockData.tileData' in result
 assert defaults['IrisBlockData.data']==defaults['IrisBlockData.tileData']=='new KMap<>()'
 assert defaults['IrisImageMap.colors']=='new KMap<>()' and defaults['IrisImageMap.fallbackTarget']=='""'
 return result,evidence

if __name__=='__main__':
 root=Path(sys.argv[1]);output=Path(sys.argv[2]);manifest=Path(sys.argv[3])
 values,evidence=extract(root);lock=json.loads(Path('iris.lock.json').read_text());bound=json.loads(manifest.read_text())
 assert bound['irisRevision']==lock['iris']['revision'] and bound['volmlibRevision']==lock['volmlib']['revision'] and bound['sources']==evidence,'Unreviewed source drift'
 output.write_bytes(('\n'.join(k+'='+v for k,v in sorted(values.items()))+'\n').encode('ascii'))
 print('Generated closed typed resource-operation schema from '+str(len(evidence))+' pinned sources')
