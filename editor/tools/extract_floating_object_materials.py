"""Audit object replacement rules and stilt/replacement material palette consumers."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact

def extract(placement,stilt,replacement,palette,block,runner,shaping):
    sources=[placement,stilt,replacement,palette,block,runner,shaping]
    p,s,r,pa,b,c,sh=[raw.decode('utf-8') for raw in sources]
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text):raise ValueError('Changed object material contract: '+contract)
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for name,raw in zip(['placement','stilt','replacement','palette','block','runner','shaping'],sources):values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    require(p,'@ArrayType(min = 1, type = IrisObjectReplace.class)','private KList<IrisObjectReplace> edit = new KList<>();','p.setEdit(edit);')
    require(s,'private IrisMaterialPalette palette;')
    require(r,'@NoArgsConstructor','@ArrayType(min = 1, type = IrisBlockData.class) @Required @Description("Find this block") private KList<IrisBlockData> find = new KList<>();',
            '@Required @Description("Replace it with this block palette") private IrisMaterialPalette replace = new IrisMaterialPalette();',
            'private boolean exact = false;','private float chance = 1;',
            'for (IrisBlockData i : find) { NativeBlockState bx = i.getBlockDataOrPlaceholder(rdata); if (bx != null) { b.add(bx); } }',
            'return getReplace().get(seed, x, y, z, rdata);')
    chance=re.search(r'@MinNumber\(([^)]+)\)\s+@MaxNumber\(([^)]+)\)\s+@Description\(("(?:[^"\\]|\\.)*")\)\s+private float chance = ([^;]+);',r)
    if not chance:raise ValueError('Changed replacement chance bounds')
    values.update({'chance.minimum':chance.group(1),'chance.maximum':chance.group(2),'chance.default':chance.group(4),'chance.description':json.loads(chance.group(3)),
                   'exact.default':'false','find.minimum':'1','edit.policy':'emptyDisables'})
    require(pa,'@NoArgsConstructor','private IrisGeneratorStyle style = NoiseStyle.STATIC.style();',
            'private KList<IrisBlockData> palette = new KList<IrisBlockData>().qadd(new IrisBlockData("STONE"));',
            'for (int i = 0; i < ix.getWeight(); i++) { blockData.add(bx); }',
            'double scaledX = x / zoom;','double scaledY = y / zoom;','double scaledZ = z / zoom;',
            'return getLayerGenerator(rng, rdata).fit(localBlockData, scaledX, scaledY, scaledZ);')
    zoom=re.search(r'@MinNumber\(([^)]+)\)\s+@Description\("(?:[^"\\]|\\.)*"\)\s+private double zoom = ([^;]+);',pa)
    weight=re.search(r'@MinNumber\(([^)]+)\)\s+@MaxNumber\(([^)]+)\)\s+@Description\("(?:[^"\\]|\\.)*"\)\s+private int weight = ([^;]+);',b)
    if not zoom or not weight:raise ValueError('Changed shared palette constraints')
    require(b,'private String block = "air";','public IrisBlockData(String b) { this.block = b; }')
    values.update({'palette.block':'STONE','palette.style':'STATIC','palette.zoom.default':zoom.group(2),'palette.zoom.minimum':zoom.group(1),'weight.default':weight.group(3),'weight.minimum':weight.group(1),'weight.maximum':weight.group(2)})
    require(c,'boolean hasEdits = !config.getEdit().isEmpty();','if (hasEdits) { for (IrisObjectReplace j : config.getEdit()) { if (rng.chance(j.getChance())) {',
            'j.isExact() ? k.matches(data) : IrisObjectShaping.materialKey(k).equals(IrisObjectShaping.materialKey(data))',
            'NativeBlockState newData = j.getReplace(rng, i.getX() + x, i.getY() + y, i.getZ() + z, rdata);',
            'data = BlockDataMergeSupport.merge(data, newData);',
            'if (settings != null && settings.getPalette() != null) { d = config.getStiltSettings().getPalette().get(rng, x, y, z, rdata); }',
            'if (!IrisObjectShaping.shouldStilt(sourceData, excludedStiltMaterials))',
            'if (d == null || !IrisObjectShaping.shouldStilt(d, excludedStiltMaterials))')
    require(sh,'return isStiltLayerBlock(state) && !state.isStorage() && !state.hasTileEntity() && !excludedMaterials.contains(materialKey(state));')
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__':Path(sys.argv[8]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:8])))
