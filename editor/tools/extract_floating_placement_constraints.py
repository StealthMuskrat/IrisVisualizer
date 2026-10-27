"""Audit floating placement guard bounds, native gating and all-context coordinate warp."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact

def extract(placement,clamp,slope,carving,runner,mantle,style):
    sources=[placement,clamp,slope,carving,runner,mantle,style]
    p,c,s,e,r,m,g=[raw.decode('utf-8') for raw in sources]
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text): raise ValueError('Changed floating constraint contract: '+contract)
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for name,raw in zip(['placement','clamp','slope','carving','runner','mantle','style'],sources):values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    def description(text,kind,field):
        match=re.search(r'@Description\(((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+)\)\s+private '+kind+' '+field+r'\s*(?:=[^;]+)?;',text)
        if not match: raise ValueError('Missing placement help: '+field)
        return ''.join(json.loads(token) for token in re.findall(r'"(?:[^"\\]|\\.)*"',match.group(1)))
    for field,kind,default in [('rotateTowardsSlope','boolean','false'),('requireSurfaceSupport','boolean','true'),('surfaceSupportBuffer','int','2'),('surfaceSupportDepth','int','2')]:
        require(p,'private '+kind+' '+field+' = '+default+';')
        values[field+'.default']=default;values[field+'.description']=description(p,kind,field)
        if kind=='int':
            match=re.search(r'((?:    @[^\n]+\n)+)    private int '+field+r' = '+default+';',p)
            if not match: raise ValueError('Changed support annotations')
            for name,annotation in [('minimum','MinNumber'),('maximum','MaxNumber')]:
                bound=re.search('@'+annotation+r'\(([^)]+)\)',match.group(1))
                if not bound: raise ValueError('Missing support bound')
                values[field+'.'+name]=bound.group(1)
    for field,kind,text,members in [('clamp','IrisObjectLimit',c,['minimumHeight','maximumHeight']),('slopeCondition','IrisSlopeClip',s,['minimumSlope','maximumSlope'])]:
        require(p,'private '+kind+' '+field+' = new '+kind+'();');require(text,'@NoArgsConstructor')
        values[field+'.description']=description(p,kind,field)
        for member in members:
            match=re.search(r'((?:    @[^\n]+\n)+)    private '+('int' if field=='clamp' else 'double')+' '+member+r' = ([^;]+);',text)
            if not match: raise ValueError('Changed placement bound member')
            annotations,default=match.groups();values[field+'.'+member+'.default']=default
            for name,annotation in [('minimum','MinNumber'),('maximum','MaxNumber')]:
                bound=re.search('@'+annotation+r'\(([^)]+)\)',annotations)
                if not bound: raise ValueError('Missing placement bound')
                values[field+'.'+member+'.'+name]=bound.group(1)
    require(c,'return h <= maximumHeight && l >= minimumHeight;')
    require(s,'return minimumSlope <= 0 && maximumSlope >= 10;','if (isDefault()) { return true; }','return !(minimumSlope > slope) && !(maximumSlope < slope);')
    require(p,'private CarvingMode carvingSupport = CarvingMode.SURFACE_ONLY;')
    values['carvingSupport.default']='SURFACE_ONLY';values['carvingSupport.description']=description(p,'CarvingMode','carvingSupport')
    body=e.split('public enum CarvingMode {',1)[1].split(';',1)[0]
    pattern=r'\s*@Description\(("(?:[^"\\]|\\.)*")\)\s+([A-Z][A-Z_]*)(?:,|(?=\s*$))'
    matches=list(re.finditer(pattern,body))
    if not matches or re.sub(pattern,'',body).strip() or len({x.group(2) for x in matches})!=len(matches): raise ValueError('Changed carving mode enum')
    values['carvingSupport.choices']=','.join(x.group(2) for x in matches)
    for match in matches:values['carvingSupport.'+match.group(2)+'.description']=json.loads(match.group(1))
    require(e,'return this.equals(ANYWHERE) || this.equals(SURFACE_ONLY);')
    require(p,'private IrisGeneratorStyle warp = new IrisGeneratorStyle(NoiseStyle.FLAT);','p.setWarp(warp);',
            'p.setClamp(clamp);','p.setSlopeCondition(slopeCondition);','p.setRotateTowardsSlope(rotateTowardsSlope);',
            'p.setCarvingSupport(carvingSupport);','p.setRequireSurfaceSupport(requireSurfaceSupport);','p.setSurfaceSupportBuffer(surfaceSupportBuffer);','p.setSurfaceSupportDepth(surfaceSupportDepth);')
    values['warp.default']='FLAT';values['warp.description']=description(p,'IrisGeneratorStyle','warp')
    match=re.search(r'@MinNumber\(([^)]+)\)\s+@Description\("(?:[^"\\]|\\.)*"\)\s+private double multiplier = ([^;]+);',g)
    if not match: raise ValueError('Changed warp multiplier field')
    values['warp.multiplier.minimum']=match.group(1);values['warp.multiplier.default']=match.group(2)
    require(g,'private NoiseStyle style = NoiseStyle.FLAT;')
    require(r,'boolean evaluateSlopeCondition = !config.isForcePlace() && !config.getSlopeCondition().isDefault();',
            'if (evaluateSlopeCondition || config.isRotateTowardsSlope()) { Engine placementEngine = requireSlopeEngine(placer);',
            'double newRotation = config.getRotation().getYAxis().getMin() + slopeRotationY;',
            'if (!config.isForcePlace() && !rawStructurePiece && !config.getClamp().canPlace(y + rty + ty, y - rty + ty))',
            'if (surfaceAnchored && !config.isForcePlace() && !config.isFromBottom() && config.getMode() != ObjectPlaceMode.FLOATING && !rawStructurePiece && !config.isUnderwater() && !config.isOnwater() && config.isRequireSurfaceSupport()',
            'config.getSurfaceSupportBuffer(), config.getSurfaceSupportDepth()',
            'case SURFACE_ONLY -> placer.isCarved(x, y, z);','case CARVING_ONLY -> !isCarvedCaveAnchor(placer, x, y, z);','case ANYWHERE -> false;',
            'boolean warped = !config.getWarp().isFlat();','double warpHalf = warped ? config.getWarp().getMultiplier() / 2D : 0D;',
            'xx += surfaceWarp.fitDouble(-warpHalf, warpHalf, i.getX() + x, i.getY() + y, i.getZ() + z);',
            'zz += surfaceWarp.fitDouble(-warpHalf, warpHalf, i.getZ() + z, i.getY() + y, i.getX() + x);')
    require(m,'anchored.setForcePlace(true);','inverted.setForcePlace(true);','IrisObjectPlacement floatingPlacement = placement.toPlacement(obj.getLoadKey());')
    if re.search(r'(floatingPlacement|anchored|inverted)\s*\.\s*setWarp',m): raise ValueError('Changed preserved warp')
    values.update({'guards.context':'floatingObjectsOnly','warp.context':'allFourCollections','bounds.policy':'orderedEditorSubset'})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__': Path(sys.argv[8]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:8])))
