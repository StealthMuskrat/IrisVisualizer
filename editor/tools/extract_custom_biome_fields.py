"""Pin custom-biome declarations, enum sets and native selection/datapack consumers."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact

FIELDS={
 'biome':{'id':('String','""',None,None),'temperature':('double','0.8','-3','3'),'humidity':('double','0.4','-3','3'),
          'spawns':('KList<IrisBiomeCustomSpawn>','new KList<>()',None,None),'tags':('KList<String>','new KList<>()',None,None),
          'downfallType':('IrisBiomeCustomPrecipType','IrisBiomeCustomPrecipType.rain',None,None),'ambientParticle':('IrisBiomeCustomParticle','null',None,None),
          'category':('IrisBiomeCustomCategory','IrisBiomeCustomCategory.plains',None,None),'spawnRarity':('int','0','0','20'),
          'skyColor':('String','"#79a8e1"',None,None),'fogColor':('String','"#c0d8e1"',None,None),'waterColor':('String','"#3f76e4"',None,None),
          'waterFogColor':('String','"#050533"',None,None),'grassColor':('String','""',None,None),'foliageColor':('String','""',None,None)},
 'spawn':{'type':('String','"minecraft:cow"',None,None),'minCount':('int','2','1',None),'maxCount':('int','5','1',None),'weight':('int','1','1','1000'),'group':('IrisBiomeCustomSpawnType','IrisBiomeCustomSpawnType.MISC',None,None)},
 'particle':{'particle':('String','"minecraft:flash"',None,None),'rarity':('int','35','1','10000')}
}
ENUMS=[('biome.category','IrisBiomeCustomCategory',['beach','desert','extreme_hills','forest','icy','jungle','mesa','mushroom','nether','none','ocean','plains','river','savanna','swamp','taiga','the_end']),
       ('biome.downfallType','IrisBiomeCustomPrecipType',['none','rain','snow']),
       ('spawn.group','IrisBiomeCustomSpawnType',['MONSTER','CREATURE','AMBIENT','AXOLOTLS','UNDERGROUND_WATER_CREATURE','WATER_CREATURE','WATER_AMBIENT','MISC'])]

def extract(custom,spawn,particle,category,precip,group,biome,derivatives,compiler):
    sources=[custom,spawn,particle,category,precip,group,biome,derivatives,compiler];c,s,p,cat,pre,g,b,d,dc=[raw.decode('utf-8') for raw in sources]
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for name,raw in zip(['custom','spawn','particle','category','precip','group','biome','derivatives','compiler'],sources):values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text):raise ValueError('Changed custom-biome contract: '+contract)
    for group_name,source in [('biome',c),('spawn',s),('particle',p)]:
        require(source,'@NoArgsConstructor')
        for name,(kind,initial,minimum,maximum) in FIELDS[group_name].items():
            match=re.search(r'((?:    @[^\n]+\n)+)    private '+re.escape(kind)+' '+name+' = '+re.escape(initial)+r';',source)
            if not match:raise ValueError('Changed custom declaration '+group_name+'.'+name)
            annotations=match.group(1);description=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)',annotations)
            if not description:raise ValueError('Missing custom description')
            key=group_name+'.'+name;values[key+'.description']=json.loads(description.group(1));values[key+'.type']=kind
            values[key+'.default']=json.loads(initial) if kind=='String' else initial if kind in ['int','double'] else '[]' if initial=='new KList<>()' else initial.rsplit('.',1)[-1]
            for annotation,expected,bound in [('MinNumber',minimum,'minimum'),('MaxNumber',maximum,'maximum')]:
                actual=re.search('@'+annotation+r'\(([^)]+)\)',annotations)
                if (actual.group(1) if actual else None)!=expected:raise ValueError('Changed custom bound '+key+'.'+bound)
                if expected is not None:values[key+'.'+bound]=expected
            if kind=='int' and maximum is None:values[key+'.maximum']='2147483647'
    for (key,name,expected),source in zip(ENUMS,[cat,pre,g]):
        if 'public enum '+name not in source:raise ValueError('Changed custom enum type')
        choices=re.findall(r'@Description\("(?:[^"\\]|\\.)*"\)\s+(\w+)\s*(?=,|\})',source)
        if choices!=expected:raise ValueError('Changed custom enum choices '+name)
        values[key+'.choices']=','.join(choices)
    require(b,'@ArrayType(min = 1, type = IrisBiomeCustom.class)','private KList<IrisBiomeCustom> customDerivitives;',
            'return getCustomDerivitives() != null && getCustomDerivitives().isNotEmpty();')
    require(d,'if (customDerivitives.size() == 1) { return customDerivitives.get(0); }',
            'return customDerivitives.get(biome.getBiomeGenerator(rng, engine).fit(0, customDerivitives.size() - 1, x, y, z));')
    for field in ['sky','fog','water','waterFog']:
        getter=field[0].upper()+field[1:]+'Color';output={'sky':'sky_color','fog':'fog_color','water':'water_color','waterFog':'water_fog_color'}[field]
        require(c,'effects.put("'+output+'", parseColor(get'+getter+'()));')
    require(c,'if (!getGrassColor().isEmpty()) { effects.put("grass_color", parseColor(getGrassColor())); }','if (!getFoliageColor().isEmpty()) { effects.put("foliage_color", parseColor(getFoliageColor())); }',
            'return Color.decode(v).getRGB() & 0x00FFFFFF;','return id.toLowerCase();',
            'j.put("temperature", getTemperature());','j.put("downfall", getHumidity());','j.put("creature_spawn_probability", getSpawnRarity());',
            'j.put("has_precipitation", getDownfallType() != IrisBiomeCustomPrecipType.none);',
            'j.put("category", getCategory().toString().toLowerCase());',
            'if (ambientParticle != null)', 'String particleKey = ambientParticle.getParticleKey();','particle.put("probability", 1f / ambientParticle.getRarity());',
            'if (getSpawns() != null && getSpawns().isNotEmpty())', 'String key = i.getTypeKey();',
            'if (contentGate.entity(key) == KeyStatus.MISSING)',
            'IrisBiomeCustomSpawnType group = i.getGroup() == null ? IrisBiomeCustomSpawnType.MISC : i.getGroup();',
            'o.put("type", key); o.put("weight", i.getWeight()); o.put("minCount", i.getMinCount()); o.put("maxCount", i.getMaxCount());',
            'resolved.addIfMissing(tag);','for (String inherited : IrisVanillaBiomeTags.tagsFor(vanillaDerivativeKey)) { resolved.addIfMissing(inherited); }',
            'return fixer.fixCustomBiome(this, j).toString(4);')
    require(s,'if (type == null || type.isBlank()) { return null; }','String normalized = type.trim().toLowerCase(Locale.ROOT);','return normalized.contains(":") ? normalized : "minecraft:" + normalized;')
    require(p,'if (particle == null || particle.isEmpty()) { return null; }','return particle.indexOf(\':\') >= 0 ? particle : "minecraft:" + particle;')
    require(dc,'String json = customBiome.generateJson(fixer, contentGate);','for (String tag : customBiome.getEffectiveTags(derivativeKey))',
            'String normalizedTag = normalizeRegistryKey(tag);','biomeTags.computeIfAbsent(resourceKey, ignored -> new TreeSet<>()).addAll(tags);')
    values.update({'custom.policy':'nonemptyCustomList','spawns.policy':'independentOfSpawnRarity','particle.policy':'reciprocalRarity','tags.policy':'authoredPlusNativeInherited',
                   'colors.policy':'fourAlwaysTwoOptional','editor.idPolicy':'simpleLowercaseLocalUnique','editor.registryPolicy':'fullNamespacedSyntaxOnly','editor.countPolicy':'orderedPair'})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__':Path(sys.argv[10]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:10])))
