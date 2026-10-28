"""Pin expression authoring fields, engine choices and native precedence/coordinate contracts."""
import hashlib,json,re,sys
from pathlib import Path
from extract_river_material_fields import compact
CLASSES={'root':'IrisExpression','variables':'IrisExpressionLoad','functions':'IrisExpressionFunction'}
FIELDS={'root':{'variables':('KList<IrisExpressionLoad>','new KList<>()'),'functions':('KList<IrisExpressionFunction>','new KList<>()'),'expression':('String','null')},'variables':{'name':('String','""'),'staticValue':('double','-1'),'styleValue':('IrisGeneratorStyle','null'),'engineStreamValue':('IrisEngineStreamType','null'),'engineValue':('IrisEngineValueType','null')},'functions':{'name':('String','null'),'styleValue':('IrisGeneratorStyle','null'),'engineStreamValue':('IrisEngineStreamType','null'),'args':('int','2')}}
FILES=list(CLASSES.values())+['IrisEngineStreamType','IrisEngineValueType','ExpressionNoise']
def read_sources(root):return {name:(Path(root)/(name+'.java')).read_bytes() for name in FILES}
def method(text,signature):
 start=text.index(signature);start=text.index('{',start);depth=1;end=start+1
 while depth:
  if text[end]=='{':depth+=1
  elif text[end]=='}':depth-=1
  end+=1
 return text[start:end]
def extract(sources):
 texts={n:b.decode('utf-8') for n,b in sources.items()};values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
 for n in FILES:values[n+'SourceSha256']=hashlib.sha256(sources[n]).hexdigest()
 def require(text,*items):
  for item in items:
   if compact(item) not in compact(text):raise ValueError('Changed expression contract: '+item)
 for group,fields in FIELDS.items():
  text=texts[CLASSES[group]]
  for field,(t,default) in fields.items():
   m=re.search(r'(?P<annotations>(?:\s*@[^\n]+\n)+)\s*private '+re.escape(t)+r' '+field+r'(?: = (?P<default>[^;]+))?;',text)
   if not m or (m['default'] or 'null')!=default:raise ValueError('Changed expression field/default: '+group+'.'+field)
   d=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)',m['annotations'])
   if not d:raise ValueError('Missing expression description')
   prefix=group+'.'+field+'.';values[prefix+'type']=t;values[prefix+'default']=json.loads(default) if default.startswith('"') else default;values[prefix+'description']=json.loads(d[1])
   bound=re.search(r'@MinNumber\(([^)]+)\)',m['annotations'])
   if bound:values[prefix+'minimum']=bound[1]
 for name,field in [('IrisEngineStreamType','engineStreamValue'),('IrisEngineValueType','engineValue')]:
  text=texts[name];items=re.findall(r'@Description\(("(?:[^"\\]|\\.)*")\)\s*([A-Z][A-Z_0-9]+)\(',text);constants=re.findall(r'^    ([A-Z][A-Z_0-9]+)\(',text,re.M)
  expected=('SLOPE,NATURAL_HEIGHT,HEIGHT,HEIGHT_OR_FLUID,OVERLAY_NOISE,REGION_STYLE,REGION_IDENTITY,RIVER_DISTANCE,RIVER_FLOW,RIVER_CARVE_WEIGHT,RIVER_WATER_SURFACE' if name=='IrisEngineStreamType' else 'ENGINE_HEIGHT,ENGINE_MIN_HEIGHT,ENGINE_MAX_HEIGHT,FLUID_HEIGHT').split(',')
  if not constants or constants!=[n for d,n in items] or constants!=expected:raise ValueError('Incomplete engine source choices')
  values[field+'.choices']=','.join(constants)
  for description,n in items:values[field+'.'+n+'.description']=json.loads(description)
 root=texts['IrisExpression'];load=texts['IrisExpressionLoad'];function=texts['IrisExpressionFunction']
 require(root,'@ArrayType(type = IrisExpressionLoad.class, min = 1)','@ArrayType(type = IrisExpressionFunction.class, min = 1)','scope.addInvocationVariable(i.getName());','scope.addInvocationVariable("x");','scope.addInvocationVariable("y");','scope.addInvocationVariable("z");','if (!f.isValid()) continue;','parser.registerFunction(f.getName(), f);','return parser.parse(getExpression(), scope);','return "expressions";')
 require(method(root,'public double evaluate(RNG rng, double x, double z)'),'arguments[index++] = x; arguments[index++] = z; arguments[index] = -1;')
 require(method(root,'public double evaluate(RNG rng, double x, double y, double z)'),'arguments[index++] = x; arguments[index++] = y; arguments[index] = z;')
 for signature in ['public double getValue(RNG rng, IrisData data, double x, double z)','public double getValue(RNG rng, IrisData data, double x, double y, double z)']:
  body=method(load,signature);order=[body.index(s) for s in ['if (engineValue != null)','if (engineStreamValue != null)','if (styleValue != null)','return staticValue;']]
  if order!=sorted(order):raise ValueError('Changed variable precedence')
  require(body,'requireEngine(data)','engineValue.get(engine)','engineStreamValue.get(engine)).get(x, z)')
 require(load,'return style(data, rng).noise(x, z);','return style(data, rng).noise(x, y, z);','throw new IllegalStateException(')
 require(function,'@MinNumber(2)','return styleValue != null || engineStreamValue != null;','if (engineStreamValue != null) return 2;','return Math.max(args, 2);','return coordinates -> stream.get(coordinates[0], coordinates[1]);','return styleValue.create(new RNG(key.seed), key.data, key.engine)::noise;','throw new IllegalStateException(')
 provider=method(function,'private Provider createProvider(FunctionKey key)')
 if provider.index('if (engineStreamValue != null)')>provider.index('if (styleValue != null)'):raise ValueError('Changed function precedence')
 require(texts['ExpressionNoise'],'return expression.evaluate(rng, x, -1);','return expression.evaluate(rng, x, z);','return expression.evaluate(rng, x, y, z);')
 values.update({'variable.policy':'engineValueThenStreamThenStyleThenStatic','function.policy':'streamThenStyle','function.empty':'ignored','function.streamArgs':'2','coordinates.1d':'x,-1,-1','coordinates.2d':'x,z,-1','coordinates.3d':'x,y,z','folder':'expressions','execution.policy':'disabled'})
 return ''.join(k+'='+json.dumps(v,ensure_ascii=True)[1:-1]+'\n' for k,v in values.items()).encode('ascii')
if __name__=='__main__':Path(sys.argv[2]).write_bytes(extract(read_sources(sys.argv[1])))
