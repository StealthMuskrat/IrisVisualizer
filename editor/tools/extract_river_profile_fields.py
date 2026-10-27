"""Audit profile defaults/list requirements against native ID and fluid palette checks."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact


def extract(rivers,profile,palette,block,validator):
    r,p,pa,b,v=(raw.decode('utf-8') for raw in [rivers,profile,palette,block,validator])
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text):
                raise ValueError('Changed profile contract: '+contract)
    for text in [r,p,pa,b]:
        require(text,'@NoArgsConstructor')
    parent=re.search(r'@ArrayType\(min = (\d+), type = IrisRiverProfile.class\)\s+@Description\(("(?:[^"\\]|\\.)*")\)\s+private KList<IrisRiverProfile> profiles = new KList<IrisRiverProfile>\(\).qadd\(new IrisRiverProfile\(\)\);',r)
    identifier=re.search(r'@Required\s+@Description\(("(?:[^"\\]|\\.)*")\)\s+private String id = ("(?:[^"\\]|\\.)*");',p)
    fluid=re.search(r'@Required\s+@Description\(("(?:[^"\\]|\\.)*")\)\s+private IrisMaterialPalette fluidPalette = new IrisMaterialPalette\(\).qclear\(\).qadd\(("(?:[^"\\]|\\.)*")\);',p)
    if not parent or not identifier or not fluid or parent.group(1)!='1':
        raise ValueError('Changed profile list/default/required declarations')
    require(pa,'public IrisMaterialPalette qclear() { palette.clear(); return this; }', 'public IrisMaterialPalette qadd(String b) { palette.add(new IrisBlockData(b)); return this; }')
    require(b,'public IrisBlockData(String b) { this.block = b; }','private int weight = 1;')
    maximum=re.search(r'private static final int MAX_PROFILES = (\d+);',v)
    pattern=re.search(r'private static final Pattern ID_PATTERN = Pattern.compile\(("[^"\n]+")\);',v)
    if not maximum or maximum.group(1)!='64' or not pattern or json.loads(pattern.group(1))!='[a-z0-9][a-z0-9_-]{0,63}':
        raise ValueError('Changed native profile limits/ID syntax')
    methods={name:re.search(r'private static '+kind+' '+name+r'\(.*?\n    \}',v,re.DOTALL) for name,kind in [('validateProfiles','Set<String>'),('validateFluidPalette','void'),('requiredId','String'),('definitelyNotFluid','boolean')]}
    if any(method is None for method in methods.values()):
        raise ValueError('Missing native profile validation')
    require(methods['validateProfiles'].group(0),'if (!rivers.has("profiles")) { return Set.of("default"); }',
            'if (!(rawProfiles instanceof JSONArray profiles))', 'if (profiles.length() > MAX_PROFILES)',
            'JSONObject profile = profiles.optJSONObject(index);','if (profile == null)',
            'String id = requiredId(profilePath, profile, errors);','if (id != null && !profileIds.add(id))',
            'validateFluidPalette(profilePath, profile, true, errors);')
    require(methods['requiredId'].group(0),'Object rawId = object.opt("id");','if (!(rawId instanceof String id) || !ID_PATTERN.matcher(id).matches())')
    require(methods['validateFluidPalette'].group(0),'if (!owner.has("fluidPalette")) { if (required)',
            'if (!(rawPalette instanceof JSONObject palette))','Object rawEntries = palette.opt("palette");',
            'if (!(rawEntries instanceof JSONArray entries) || entries.length() == 0)', 'if (entries.length() > 64)',
            'JSONObject entry = entries.optJSONObject(index);', 'if (entry == null)',
            'if (!(rawBlock instanceof String block) || block.isBlank())','else if (definitelyNotFluid(block))',
            'validateOptionalIntegerRange(entryPath, entry, "weight", 1, 64, errors);')
    require(methods['definitelyNotFluid'].group(0),'''String normalized = block.trim().toLowerCase(Locale.ROOT);
        int stateStart = normalized.indexOf('[');
        if (stateStart >= 0) { normalized = normalized.substring(0, stateStart); }
        int namespaceSeparator = normalized.indexOf(':');
        String namespace = namespaceSeparator < 0 ? "minecraft" : normalized.substring(0, namespaceSeparator);
        String key = namespaceSeparator < 0 ? normalized : normalized.substring(namespaceSeparator + 1);
        return "minecraft".equals(namespace) && !"water".equals(key) && !"lava".equals(key) && !"bubble_column".equals(key);''')
    require(v,'if (enabled && profileIds.isEmpty())')
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for name,raw in [('rivers',rivers),('profile',profile),('palette',palette),('block',block),('validator',validator)]:
        values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    values.update({'profiles.minimum':parent.group(1),'profiles.maximum':maximum.group(1),'profiles.description':json.loads(parent.group(2)),
                   'id.default':json.loads(identifier.group(2)),'id.pattern':json.loads(pattern.group(1)),
                   'fluid.default':json.loads(fluid.group(2)),'fluid.minimumEntries':'1','fluid.maximumEntries':'64',
                   'fluid.rule':'invertedDefinitelyNotFluid','fluid.minecraftChoices':'water,lava,bubble_column',
                   'weight.default':'1','weight.minimum':'1','weight.maximum':'64'})
    if values['id.default']!='default' or values['fluid.default']!='water':
        raise ValueError('Changed contextual profile defaults')
    return ''.join(key+'='+value.replace('\\','\\\\').replace('\n','\\n').replace('\r','\\r')+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__':
    Path(sys.argv[6]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:6])))
