"""Pin native imported feature and structure-set filter data and resolution semantics."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact

def extract(*sources):
    d,f,b,s,c,o,v,p=[raw.decode('utf-8') for raw in sources]
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for name,raw in zip(['dimension','features','biomeRule','steps','structures','frequency','validator','packValidator'],sources):values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text):raise ValueError('Changed native generation filter contract: '+contract)
    for text,prefix,fields in [(f,'',{'steps':'IrisDecorationStep','disabledSteps':'IrisDecorationStep','biomeRules':'IrisImportedFeatureBiomeRule'}),
                               (b,'rule.',{'biomes':'String','disabled':'String','disabledExact':'String'}),
                               (c,'',{'frequencyOverrides':'IrisStructureSetFrequencyOverride'})]:
        for field,kind in fields.items():
            match=re.search(r'@ArrayType\(type = '+kind+r'\.class, min = 1\)\s*@Description\(("(?:[^"\\]|\\.)*")\)\s*private KList<'+kind+'> '+field+r' = new KList<>\(\);',text)
            if not match:raise ValueError('Changed native list declaration '+field)
            values[prefix+field+'.description']=json.loads(match.group(1))
    require(d,'private IrisImportedFeatureControl importedFeatures = new IrisImportedFeatureControl();','private IrisImportedStructureControl importedStructures = new IrisImportedStructureControl();')
    require(f,'private boolean enabled = false;', 'if (!enabled) { return false; }',
            'if (step == null) { // A step this Iris build does not know about (newer Minecraft): allow it unless the pack // narrowed generation to an explicit step list. return allowed.isEmpty(); }',
            'if (!allowed.isEmpty() && !allowed.contains(step)) { return false; }','return !denied.contains(step);',
            'rule != null && rule.appliesTo(biomeKey, biomeTags) && rule.denies(featureKey)')
    require(b,'private static final String TAG_PREFIX = "#";', 'biomeTags.contains(normalizedEntry.substring(TAG_PREFIX.length()))',
            'normalizedEntry.endsWith(":") ? normalizedBiome.startsWith(normalizedEntry) : normalizedBiome.equals(normalizedEntry)',
            'IrisImportedStructureControl.matchesKey(entry, featureKey)', '!normalize(entry).isEmpty() && normalize(entry).equals(normalizedFeature)',
            'return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);')
    steps=re.findall(r'@Description\(("(?:[^"\\]|\\.)*")\)\s*([A-Z_]+)\("([a-z_]+)"\)',s)
    expected=['RAW_GENERATION','LAKES','LOCAL_MODIFICATIONS','UNDERGROUND_STRUCTURES','SURFACE_STRUCTURES','STRONGHOLDS','UNDERGROUND_ORES','UNDERGROUND_DECORATION','FLUID_SPRINGS','VEGETAL_DECORATION','TOP_LAYER_MODIFICATION']
    if [name for _,name,_ in steps]!=expected or re.findall(r'^\s*([A-Z_]+)\("',s,re.M)!=expected:raise ValueError('Changed decoration steps')
    for description,name,key in steps:
        if key!=name.lower():raise ValueError('Changed step serialized key')
        values['step.'+name+'.description']=json.loads(description)
    values['steps']=','.join(expected)
    require(s,'return ordinal < 0 || ordinal >= values.length ? null : values[ordinal];')
    match=re.search(r'@RegistryListVanillaStructureSet\s*@Description\(("(?:[^"\\]|\\.)*")\)\s*private String structureSet = "";',o)
    if not match:raise ValueError('Changed exact structure-set key')
    values['structureSet.description']=json.loads(match.group(1))
    match=re.search(r'@MinNumber\((0\.01)\)\s*@MaxNumber\((16)\)\s*@Description\(("(?:[^"\\]|\\.)*")\)\s*private double multiplier = 1D;',o)
    if not match:raise ValueError('Changed structure-set multiplier')
    values.update({'multiplier.minimum':match.group(1),'multiplier.maximum':match.group(2),'multiplier.description':json.loads(match.group(3)),'multiplier.default':'1'})
    require(c,'double multiplier = 1D;', 'normalizeKey(override.getStructureSet()).equals(normalizedKey)', 'multiplier = override.getMultiplier();', 'return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);',
            "return boundary == '/' || boundary == '_';")
    require(v,'!PackValidator.RESOURCE_KEY_PATTERN.matcher(key.trim()).matches()', 'path, override, "multiplier", 0.01D, 16D, blockingErrors')
    require(p,'static final Pattern RESOURCE_KEY_PATTERN = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");')
    values.update({'step.policy':'denyAfterAllow','frequency.policy':'lastNormalizedKeyWins','registry.policy':'syntaxOnly'})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__':Path(sys.argv[9]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:9])))
