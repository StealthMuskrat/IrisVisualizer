"""Audit general dimension palette constructor defaults and block weight annotations."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact


def extract(dimension,palette,block):
    d,p,b=(raw.decode('utf-8') for raw in [dimension,palette,block])
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text): raise ValueError('Changed dimension palette contract: '+contract)
    for text in [d,p,b]: require(text,'@NoArgsConstructor')
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for name,raw in [('dimension',dimension),('palette',palette),('block',block)]: values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    for name in ['rockPalette','fluidPalette']:
        field=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private IrisMaterialPalette '+name+r' = new IrisMaterialPalette\(\).qclear\(\).qadd\(("(?:[^"\\]|\\.)*")\);',d)
        if not field: raise ValueError('Changed dimension palette declaration/default: '+name)
        values[name+'.block']=json.loads(field.group(2));values[name+'.description']=json.loads(field.group(1))
    require(p,'public IrisMaterialPalette qclear() { palette.clear(); return this; }','public IrisMaterialPalette qadd(String b) { palette.add(new IrisBlockData(b)); return this; }')
    declared=re.search(r'@Required\s+@ArrayType\(min = (\d+), type = IrisBlockData.class\)\s+@Description\("[^"\n]*"\)\s+private KList<IrisBlockData> palette = new KList<IrisBlockData>\(\).qadd\(new IrisBlockData\(("[^"\n]*")\)\);',p)
    weight=re.search(r'@MinNumber\((\d+)\)\s+@MaxNumber\((\d+)\)\s+@Description\("[^"\n]*"\)\s+private int weight = (\d+);',b)
    if not declared or declared.group(1)!='1' or not weight: raise ValueError('Changed palette list/weight declaration')
    require(b,'public IrisBlockData(String b) { this.block = b; }','@RegistryListBlockType @Required @Description("The block to use") private String block = "air";')
    values.update({'palette.minimum':declared.group(1),'palette.classBlock':json.loads(declared.group(2)),
                   'weight.minimum':weight.group(1),'weight.maximum':weight.group(2),'weight.default':weight.group(3)})
    if not int(values['weight.minimum'])<=int(values['weight.default'])<=int(values['weight.maximum']): raise ValueError('Invalid weight default/bounds')
    return ''.join(key+'='+value.replace('\\','\\\\').replace('\n','\\n').replace('\r','\\r')+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__': Path(sys.argv[4]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:4])))
