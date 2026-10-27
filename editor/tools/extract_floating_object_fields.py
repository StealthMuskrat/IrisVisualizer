"""Audit floating placement lists, bounded object fields and actual runtime override rules."""
import hashlib
import json
import re
import sys
from decimal import Decimal
from pathlib import Path
from extract_river_material_fields import compact

LISTS=['extraObjects','floatingObjects','topObjectOverrides','bottomObjectOverrides']
POOLS=['place','allowedCollisions','forbiddenCollisions']
SCALARS=['chance','density','snow']


def extract(entry,placement,modes,mantle,loader,array,translate):
    e,p,m,c,l,a=(raw.decode('utf-8') for raw in [entry,placement,modes,mantle,loader,array])
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text): raise ValueError('Changed floating object contract: '+contract)
    require(e,'@NoArgsConstructor');require(p,'@NoArgsConstructor');require(a,'int min() default 0;')
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f','lists':','.join(LISTS),'pools':','.join(POOLS)}
    for name,raw in [('entry',entry),('placement',placement),('modes',modes),('mantle',mantle),('loader',loader),('array',array),('translate',translate)]:values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    for field in LISTS:
        match=re.search(r'@ArrayType\(min = 1, type = IrisObjectPlacement.class\)\s+@Description\(("(?:[^"\\]|\\.)*")\)\s+private KList<IrisObjectPlacement> '+field+r' = new KList<>\(\);',e)
        if not match: raise ValueError('Changed floating placement list')
        values[field+'.description']=json.loads(match.group(1));values[field+'.default']='[]'
    for field in POOLS:
        match=re.search(r'((?:    @[^\n]+\n)+)    private KList<String> '+field+r' = new KList<>\(\);',p)
        if not match: raise ValueError('Changed object reference pool')
        annotations=match.group(1);require(annotations,'@RegistryListResource(IrisObject.class)')
        require(annotations,'@ArrayType(min = 1, type = String.class)' if field=='place' else '@ArrayType(type = String.class)')
        if field=='place': require(annotations,'@Required')
        values[field+'.minimum']='1' if field=='place' else '0';values[field+'.default']='[]'
        help_text=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)',annotations)
        if not help_text: raise ValueError('Missing reference help')
        values[field+'.description']=json.loads(help_text.group(1))
    for field in SCALARS:
        match=re.search(r'((?:    @[^\n]+\n)+)    private (int|double) '+field+r' = ([^;]+);',p)
        if not match: raise ValueError('Changed placement scalar')
        annotations,kind,default=match.groups();values[field+'.type']=kind;values[field+'.default']=str(Decimal(default))
        minimum=re.search(r'@MinNumber\(([^)]+)\)',annotations);maximum=re.search(r'@MaxNumber\(([^)]+)\)',annotations)
        if not minimum or kind=='double' and not maximum: raise ValueError('Missing placement scalar bounds')
        values[field+'.minimum']=minimum.group(1);values[field+'.maximum']=maximum.group(1) if maximum else '2147483647'
        values[field+'.description']=json.loads(re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)',annotations).group(1))
    require(p,'private IrisStyledRange densityStyle = null;','private ObjectPlaceMode mode = ObjectPlaceMode.CENTER_HEIGHT;',
            'if (densityStyle == null) { return density; }','return (int) Math.round(densityStyle.get(rng, x, z, data));',
            'p.setMode(mode);','p.setChance(chance);','p.setDensity(density);','p.setSnow(snow);','p.setAllowedCollisions(allowedCollisions);','p.setForbiddenCollisions(forbiddenCollisions);',
            'if (pool.isEmpty()) { return null; }','return data.getObjectLoader().load(pool.get(random.nextInt(pool.size())));')
    body=m.split('public enum ObjectPlaceMode {',1)[1].rsplit('}',1)[0]
    pattern=r'\s*@Description\(("(?:[^"\\]|\\.)*")\)\s+([A-Z][A-Z_]*)(?:,|(?=\s*$))'
    matches=list(re.finditer(pattern,body))
    if not matches or re.sub(pattern,'',body).strip() or len({x.group(2) for x in matches})!=len(matches): raise ValueError('Changed object mode declaration')
    values['mode.choices']=','.join(x.group(2) for x in matches);values['mode.default']='CENTER_HEIGHT'
    for match in matches:values['mode.'+match.group(2)+'.description']=json.loads(match.group(1))
    for key in ['CENTER_HEIGHT','FLOATING']:
        if key not in values['mode.choices'].split(','):raise ValueError('Missing object mode')
    require(e,'case REPLACE -> new KList<>(topObjectOverrides);','merged.addAll(topObjectOverrides);','case INHERIT_ONLY -> surfaceObjects;',
            'case INHERIT_ONLY -> new KList<>();','case MERGE, REPLACE -> bottomObjectOverrides;')
    require(c,'KList<IrisObjectPlacement> floating = entry.getFloatingObjects();','KList<IrisObjectPlacement> extras = entry.getExtraObjects();',
            'KList<IrisObjectPlacement> surface = target != null ? entry.resolveTopObjects(target) : null;','KList<IrisObjectPlacement> bottom = target != null ? entry.resolveBottomObjects(target) : null;',
            'double perAttempt = placement.getChance();','if (!rng.chance(perAttempt + rng.d(-0.005, 0.005)))',
            'int density = placement.getDensity(rng, minX, minZ, data);',
            'IrisObjectPlacement floatingPlacement = placement.toPlacement(obj.getLoadKey());',
            'anchored.setMode(translateStiltModeForFloating(anchored.getMode()));','anchored.setTranslate(new IrisObjectTranslate());','anchored.setRotation(ROTATION_NONE);',
            'inverted.setMode(translateStiltModeForFloating(inverted.getMode()));','inverted.setTranslate(new IrisObjectTranslate());','inverted.setRotation(invertedRotation);',
            'case STRUCTURE_PIECE -> ObjectPlaceMode.CENTER_HEIGHT;')
    floating=re.search(r'private void tryPlaceFloatingChunk\(.*?\n    \}',c,re.DOTALL)
    if not floating or re.search(r'floatingPlacement\s*\.\s*setMode',floating.group()):raise ValueError('Changed floating configured-mode preservation')
    require(l,'m.addAll(getFiles(i, ".iob", true, visitedDirectories));','if (f.isFile() && f.getName().endsWith(ext))',
            'paths.add(name + f.getName().substring(0, f.getName().length() - ext.length()));')
    tr=translate.decode('utf-8');require(tr,'@NoArgsConstructor')
    require(p,'private IrisObjectTranslate translate = new IrisObjectTranslate();','p.setTranslate(translate);')
    for axis in ['x','y','yRandom','z']:
        match=re.search(r'((?:    @[^\n]+\n)+)    private int '+axis+r' = (0);',tr)
        if not match:raise ValueError('Changed translation declaration')
        annotations,default=match.groups();minimum=re.search(r'@MinNumber\(([^)]+)\)',annotations);maximum=re.search(r'@MaxNumber\(([^)]+)\)',annotations)
        if not minimum or not maximum:raise ValueError('Missing translation bounds')
        values['translate.'+axis+'.default']=default;values['translate.'+axis+'.minimum']=minimum.group(1);values['translate.'+axis+'.maximum']=maximum.group(1)
        values['translate.'+axis+'.description']=json.loads(re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)',annotations).group(1))
    values.update({'chance.context':'perAttemptWithJitter','floating.mode':'configured','newFloatingPreset':'FLOATING','objectKeys':'exactLowercaseExtension','nested.policy':'readOnly'})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__': Path(sys.argv[8]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:8])))
