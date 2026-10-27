"""Audit floating noise contextual defaults, optional states and native style consumers."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact

FIELDS=['footprintStyle','edgeTaperVariationStyle','pickerStyle','altitudeStyle','topShapeStyle','bottomStyle','wallWarpStyle','carveStyle']
OPTIONAL=['wallWarpStyle','carveStyle']


def extract(entry,style,sample,edge):
    e,g,s,d=(raw.decode('utf-8') for raw in [entry,style,sample,edge])
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text): raise ValueError('Changed floating noise contract: '+contract)
    require(e,'@NoArgsConstructor');require(g,'@NoArgsConstructor','public IrisGeneratorStyle(NoiseStyle s) { this.style = s; }','public IrisGeneratorStyle zoomed(double z) { this.zoom = z; return this; }')
    default=re.search(r'private NoiseStyle style = NoiseStyle\.([A-Z_]+);',g)
    if not default: raise ValueError('Missing style class default')
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f','fields':','.join(FIELDS),'optional':','.join(OPTIONAL),'classStyle':default.group(1)}
    for name,raw in [('entry',entry),('style',style),('sample',sample),('edge',edge)]:values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    for field in FIELDS:
        match=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private IrisGeneratorStyle '+field+r' = ([^;]+);',e)
        if not match: raise ValueError('Changed floating style declaration')
        description,token=match.groups();values[field+'.description']=json.loads(description)
        if field in OPTIONAL:
            if token!='null': raise ValueError('Changed optional style default')
            values[field+'.default']='null';values[field+'.enableStyle']=default.group(1)
        else:
            initializer=re.fullmatch(r'NoiseStyle\.([A-Z_]+)\.style\(\)(?:\.zoomed\(([0-9.]+)D\))?',token)
            if not initializer: raise ValueError('Changed contextual style initializer')
            values[field+'.default']=initializer.group(1);values[field+'.enableStyle']=initializer.group(1)
            if initializer.group(2): values[field+'.zoomOverride']=initializer.group(2)
    for method,field,salt in [('Footprint','FootprintStyle','0xF007B17DL'),('Picker','PickerStyle','0x91C4E72DL'),('Altitude','AltitudeStyle','0xA17DEBBL'),('TopShape','TopShapeStyle','0x70970601DEFL'),('Bottom','BottomStyle','0xB0770075CAFEL')]:
        require(e,'public CNG get'+method+'Cng(long baseSeed, IrisData data) { return get'+field+'().create(baseSeed ^ '+salt+', data); }')
    for method,salt in [('WallWarp','0xA117BA17E0FL'),('Carve','0xCA5EC1EE5EL')]:
        require(e,'public CNG get'+method+'Cng(long baseSeed, IrisData data) { IrisGeneratorStyle style = get'+method+'Style(); if (style == null) { return null; } return style.create(baseSeed ^ '+salt+', data); }')
    require(e,'IrisGeneratorStyle style = getEdgeTaperVariationStyle();','CNG variation = amplitude > 0.0D && style != null ? style.create(baseSeed ^ 0xED6E7A9E5L, data) : null;')
    require(s,'CNG footprintCng = entry.getFootprintCng(baseSeed, data);','CNG picker = reference.getPickerCng(baseSeed, data);',
            'CNG altitudeCng = entry.getAltitudeCng(baseSeed, data);','CNG bottomCng = entry.getBottomCng(baseSeed, data);',
            'CNG topCng = entry.getTopShapeCng(baseSeed, data);','CNG wallWarp = entry.getWallWarpCng(baseSeed, data);',
            'CNG carve = carvingProfileSampler == null && !entry.hasCarvingReference() ? entry.getCarveCng(baseSeed, data) : null;',
            'return carvingProfileSampler == null && (entry == null || !entry.hasCarvingReference()) && carve != null && carveThreshold < 1.0;')
    require(d,'double widthLimit = Math.min(clampedWidth - MIN_WIDTH, MAX_WIDTH - clampedWidth);','return Math.max(0.0D, Math.min(Math.min(MAX_VARIATION_AMPLITUDE, widthLimit), amplitude));')
    values.update({'optional.disable':'null','entry.policy':'existingInlineOnly'})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__': Path(sys.argv[5]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:5])))
