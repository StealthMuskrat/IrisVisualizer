"""Audit the bounded material editor against pinned constructors and hydrology validation."""
import hashlib
import re
import sys
from pathlib import Path

NAMES=['material','palette','block','banks','bed','underground','validator']
REVISION='c3359e0a16438b1bb8468901c6171dcefdf63f7f'

def compact(text):
    return re.sub(r'\s+','',text)

def extract(*sources):
    if len(sources)!=7:
        raise ValueError('Expected seven pinned sources')
    material,palette,block,banks,bed,underground,validator=[raw.decode('utf-8') for raw in sources]
    for text in [material,palette,block,banks,bed,underground]:
        if '@NoArgsConstructor' not in text:
            raise ValueError('Changed constructor defaults')
    def require(text,*contracts):
        text=compact(text)
        for contract in contracts:
            if compact(contract) not in text:
                raise ValueError('Changed material source contract: '+contract)
    require(material,'private boolean enabled = false;', 'private IrisMaterialPalette palette = new IrisMaterialPalette();',
            '@MinNumber(1) @MaxNumber(8) @Description("How many layers down from the surface the palette replaces, in blocks.") private int depth = 1;')
    require(palette,'@Required @ArrayType(min = 1, type = IrisBlockData.class) @Description("The palette of blocks to be used in this layer") private KList<IrisBlockData> palette = new KList<IrisBlockData>().qadd(new IrisBlockData("STONE"));',
            'public IrisMaterialPalette qclear() { palette.clear(); return this; }',
            'public IrisMaterialPalette qadd(String b) { palette.add(new IrisBlockData(b)); return this; }')
    require(block,'@RegistryListBlockType @Required @Description("The block to use") private String block = "air";',
            'public IrisBlockData(String b) { this.block = b; }')
    weight=re.search(r'@MinNumber\((\d+)\)\s+@MaxNumber\((\d+)\)\s+@Description\("[^"\n]*"\)\s+private int weight = (\d+);',block)
    if not weight or weight.groups()!=('1','1000','1'):
        raise ValueError('Changed block weight defaults/annotations')
    require(banks,'private IrisRiverMaterialConfig shoreMaterial = new IrisRiverMaterialConfig().setEnabled(true).setPalette(new IrisMaterialPalette().qclear().qadd("sand")).setDepth(2);',
            'private IrisRiverMaterialConfig bankMaterial = new IrisRiverMaterialConfig();')
    require(bed,'private IrisMaterialPalette paddingPalette = new IrisMaterialPalette().qclear().qadd("clay").qadd("dirt");',
            'private IrisRiverMaterialConfig material = new IrisRiverMaterialConfig();')
    require(underground,'private IrisRiverMaterialConfig bedMaterial = new IrisRiverMaterialConfig();')
    require(validator,'validateMaterial(path, banks, "shoreMaterial", errors);','validateMaterial(path, banks, "bankMaterial", errors);',
            'validateMaterial(path, underground, "bedMaterial", errors);','validateMaterial(path, bed, "material", errors);',
            'validateSolidPalette(path + ".paddingPalette", bed.opt("paddingPalette"), errors);')
    method=re.search(r'private static void validateMaterial\(.*?\n    \}',validator,re.DOTALL)
    solid=re.search(r'private static void validateSolidPalette\(.*?\n    \}',validator,re.DOTALL)
    fluid=re.search(r'private static boolean definitelyNotFluid\(.*?\n    \}',validator,re.DOTALL)
    if not method or not solid or not fluid:
        raise ValueError('Missing native material validation methods')
    require(method.group(0),'JSONObject material = nestedObject(owner, field, path, errors);',
            'validateOptionalBoolean(materialPath, material, "enabled", errors);',
            'validateOptionalIntegerRange(materialPath, material, "depth", 1, 8, errors);',
            'if (material.has("palette")) { validateSolidPalette(materialPath + ".palette", material.opt("palette"), errors); }')
    require(solid.group(0),'if (!(rawPalette instanceof JSONObject palette))', 'Object rawEntries = palette.opt("palette");',
            'if (!(rawEntries instanceof JSONArray entries) || entries.length() == 0)',
            'JSONObject entry = entries.optJSONObject(index);', 'if (entry == null)',
            'Object rawBlock = entry.opt("block");', 'if (!(rawBlock instanceof String block) || block.isBlank())',
            'else if (!definitelyNotFluid(block))', 'validateOptionalIntegerRange(entryPath, entry, "weight", 1, 64, errors);')
    expected='''private static boolean definitelyNotFluid(String block) {
        String normalized = block.trim().toLowerCase(Locale.ROOT);
        int stateStart = normalized.indexOf('[');
        if (stateStart >= 0) { normalized = normalized.substring(0, stateStart); }
        int namespaceSeparator = normalized.indexOf(':');
        String namespace = namespaceSeparator < 0 ? "minecraft" : normalized.substring(0, namespaceSeparator);
        String key = namespaceSeparator < 0 ? normalized : normalized.substring(namespaceSeparator + 1);
        return "minecraft".equals(namespace) && !"water".equals(key) && !"lava".equals(key) && !"bubble_column".equals(key);
    }'''
    if compact(fluid.group(0))!=compact(expected):
        raise ValueError('Changed native block namespace/fluid rule')
    values={'irisRevision':REVISION}
    for name,raw in zip(NAMES,sources):
        values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    values.update({'enabled.default':'false','depth.default':'1','depth.minimum':'1','depth.maximum':'8',
                   'weight.default':'1','weight.minimum':'1','weight.maximum':'64','weight.sourceAnnotationMaximum':'1000',
                   'block.namespace':'minecraft','block.excluded':'water,lava,bubble_column','palette.minimumEntries':'1',
                   'palette.blocks':'STONE','shore.enabled':'true','shore.depth':'2','shore.blocks':'sand','padding.blocks':'clay,dirt',
                   'surface.banks.shoreMaterial.summary':'Enabled, depth 2, sand.',
                   'surface.banks.bankMaterial.summary':'Disabled, depth 1, STONE.',
                   'surface.bed.material.summary':'Disabled, depth 1, STONE.',
                   'underground.bedMaterial.summary':'Disabled, depth 1, STONE.',
                   'surface.bed.paddingPalette.summary':'clay and dirt, weight 1 each.'})
    return ''.join(key+'='+value+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__':
    Path(sys.argv[8]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:8])))
