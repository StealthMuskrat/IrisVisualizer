"""Pin procedural decorators, weighted foliage and native accent selection."""
import hashlib,json,re,sys
from pathlib import Path
from extract_river_material_fields import compact
CLASSES = {'decorators': ('IrisTreeDecorator', 'IrisTreeDecoratorTarget'), 'weightedSecondaryLeaves': ('IrisTreeSecondaryLeaf', None), 'accents': ('IrisRuinDecorator', 'IrisRuinDecoratorTarget')}
FILES=[name for name,enum in CLASSES.values()]+[enum for name,enum in CLASSES.values() if enum]+['IrisProceduralTree','IrisRuin','TreeDecoratorApplier','RuinDecoratorApplier','TreeBlockResolver','ProceduralTreeGenerator','RuinGenerator','IrisProceduralBlocks']
def read_sources(root):return {name:(Path(root)/(name+'.java')).read_bytes() for name in FILES}
def extract(sources):
    texts={name:raw.decode('utf-8') for name,raw in sources.items()};values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for name in FILES:values[name+'SourceSha256']=hashlib.sha256(sources[name]).hexdigest()
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text):raise ValueError('Changed accent contract: '+contract)
    for group,(name,enum) in CLASSES.items():
        text=texts[name];fields=['block','weight'] if group=='weightedSecondaryLeaves' else ['target','block','palette','chance']+(['length','axisAware'] if group=='decorators' else ['scatterRadius'])
        for field in fields:
            match=re.search(r'(?P<annotations>(?:\s*@[^\n]+\n)+)\s*private (?P<type>\w+) '+field+r' = (?P<default>[^;]+);',text)
            if not match:raise ValueError('Missing accent field '+group+'.'+field)
            t,default=match['type'],match['default'];prefix=group+'.'+field+'.';desc=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)',match['annotations'])
            if not desc:raise ValueError('Missing accent description')
            values[prefix+'type']=t;values[prefix+'description']=json.loads(desc.group(1))
            if field=='palette':
                if (t,default)!=('IrisMaterialPalette','null'):raise ValueError('Changed accent palette default')
                values[prefix+'default']='null'
            elif field=='target':
                if t!=enum:raise ValueError('Changed target type')
                items=re.findall(r'@Description\(("(?:[^"\\]|\\.)*")\)\s*([A-Z_0-9]+)\s*[,;}]',texts[enum]);constants=re.findall(r'^    ([A-Z_0-9]+)\s*[,;}]?',texts[enum],re.M)
                if not items or [name for _,name in items]!=constants or not default.startswith(t+'.') or default.split('.')[-1] not in constants:raise ValueError('Incomplete accent targets')
                values[prefix+'default']=default.split('.')[-1];values[prefix+'choices']=','.join(constants)
                for description,name in items:values[prefix+name+'.description']=json.loads(description)
            else:
                expected='String' if field=='block' else 'boolean' if field=='axisAware' else 'double' if field=='chance' else 'int'
                if t!=expected:raise ValueError('Changed accent scalar type')
                values[prefix+'default']=json.loads(default) if t=='String' else default
            for annotation,key in [('MinNumber','minimum'),('MaxNumber','maximum')]:
                bound=re.search('@'+annotation+r'\(([^)]+)\)',match['annotations'])
                if bound:values[prefix+key]=bound[1]
    require(texts['IrisProceduralTree'],'private KList<IrisTreeDecorator> decorators = new KList<>();','private KList<IrisTreeSecondaryLeaf> weightedSecondaryLeaves = new KList<>();')
    require(texts['IrisRuin'],'private KList<IrisRuinDecorator> accents = new KList<>();')
    require(texts['TreeDecoratorApplier'],'if (decorators == null || decorators.isEmpty())','switch (dec.getTarget())','IrisProceduralBlocks.paletteSet(dec.getPalette())')
    require(texts['RuinDecoratorApplier'],'switch (decorator.getTarget())','IrisProceduralBlocks.resolve(decorator.getBlock(), decorator.getPalette(),','rng.chance(decorator.getChance())')
    resolver=texts['TreeBlockResolver'];require(resolver,'long total = 0;','total += Math.max(0, s.getWeight());','if (total <= 0) { return null; }','long cumulative = 0;','cumulative += Math.max(0, s.getWeight());','double r = rng.nextDouble() * total;','resolveBlock(dec.getBlock(), dec.getPalette(),','String picked = pickWeighted(tree, new RNG(tree.getSeed() ^ positionHash(pos)));')
    require(resolver,'case SECONDARY_LEAF -> { NativeBlockState state = resolveSecondaryLeaf(tree, data, pos, paletteRng); if (state == null) { state = resolveBlock(tree.getLeaves(), tree.getLeavesPalette(), data, pos, paletteRng); } yield state; }')
    start=resolver[resolver.index('private static NativeBlockState resolveSecondaryLeaf'):resolver.index('private static String pickWeighted')]
    order=[start.index(s) for s in ['IrisProceduralBlocks.paletteSet(tree.getSecondaryLeavesPalette())','tree.getWeightedSecondaryLeaves() != null','tree.getSecondaryLeaves() != null']]
    if order!=sorted(order):raise ValueError('Changed secondary leaf precedence')
    require(texts['ProceduralTreeGenerator'],'TreeDecoratorApplier.apply(canvas, tree, baseSeed, branchEndpoints);')
    require(texts['RuinGenerator'],'RuinDecoratorApplier.apply(canvas, decorator, data,')
    values.update({'weight.policy':'longSumNonnegative','secondary.policy':'secondaryPaletteThenWeightedThenSingle','allZero.policy':'primaryLeafFallback','lists.null':'empty','palette.policy':'sharedClassDefault','execution.policy':'disabled'})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')
if __name__=='__main__':Path(sys.argv[2]).write_bytes(extract(read_sources(sys.argv[1])))
