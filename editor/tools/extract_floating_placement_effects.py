"""Audit excavation, water and anchor controls against schema and native consumers."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact

FIELDS=['bore','boreExtendMinY','boreExtendMaxY','smartBore','meld','waterloggable','underwater','onwater','fromBottom']
ALL_CONTEXT=['bore','boreExtendMinY','boreExtendMaxY','smartBore','meld','waterloggable']

def extract(placement,runner,mantle,object_source,shaping,blocks):
    sources=[placement,runner,mantle,object_source,shaping,blocks]
    p,r,m,o,s,b=[raw.decode('utf-8') for raw in sources]
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text): raise ValueError('Changed placement effect contract: '+contract)
    require(p,'@NoArgsConstructor')
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f','fields':','.join(FIELDS),'allContextFields':','.join(ALL_CONTEXT)}
    for name,raw in zip(['placement','runner','mantle','object','shaping','blocks'],sources): values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    for field in FIELDS:
        kind='int' if field.startswith('boreExtend') else 'boolean'
        match=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private '+kind+' '+field+r'\s*(?:=\s*([^;]+))?;',p)
        if not match: raise ValueError('Changed effect declaration: '+field)
        help_text,default=match.groups()
        if field=='fromBottom':
            if default is not None: raise ValueError('Changed implicit fromBottom default')
            default='false'
        elif default!=('0' if kind=='int' else 'false'): raise ValueError('Changed effect default')
        values[field+'.default']=default;values[field+'.description']=json.loads(help_text)
        require(p,'p.set'+field[0].upper()+field[1:]+'('+field+');')
        if kind=='int':
            annotations=re.search(r'((?:    @[^\n]+\n)+)    private int '+field+r' = 0;',p)
            if not annotations: raise ValueError('Missing excavation annotations')
            for name,annotation in [('minimum','MinNumber'),('maximum','MaxNumber')]:
                found=re.search('@'+annotation+r'\(([^)]+)\)',annotations.group(1))
                if not found: raise ValueError('Missing excavation bound')
                values[field+'.'+name]=found.group(1)
    require(r,'if (config.isBore()) {','for (int j = minimumY - config.getBoreExtendMinY(); j <= maximumY + config.getBoreExtendMaxY(); j++) { placer.set(i, j, k, IrisObject.States.air()); }',
            'if (config.isMeld() && !rawStructurePiece && !placer.isSolid(xx, yy, zz)) { continue; }',
            'boolean waterlogCandidate = config.isWaterloggable() || config.isUnderwater();',
            'return IrisProceduralBlocks.normalizeWaterlogging(state, placer.get(x, y, z), enabled);',
            '} else if (config.isFromBottom()) {','y = (self.getH() + 1) + rty;',
            'if (yv >= 0 && config.isBottom() && !rawStructurePiece && alignment != IrisObjectPlacementAlignment.FLOOR)',
            'if (!config.isForcePlace() && !config.isUnderwater() && !config.isOnwater() && placer.isUnderwater(x, z))',
            'placer.getHighest(x, z, self.getLoader(), config.isUnderwater())')
    if r.count('config.isOnwater()')!=2 or 'isOnwater(' in m: raise ValueError('Changed onwater behavior in the floating path')
    require(o,'IrisObject placedObject = config.isSmartBore() ? IrisObjectShaping.smartBoredVariant(this) : this;')
    require(s,'IrisObject variant = self.copy();','ensureSmartBored(variant);','return variant;')
    require(b,'boolean authored = "true".equals(current);','boolean submerged = existing != null && (existing.isWater() || "true".equals(propertyValue(existing, "waterlogged")));',
            'String value = submerged && (enabled || authored) ? "true" : "false";')
    require(m,'IrisObjectPlacement floatingPlacement = placement.toPlacement(obj.getLoadKey());','obj.place(xx, -1, zz, transaction, floatingPlacement, rng,',
            'anchored.setForcePlace(true);','inverted.setForcePlace(true);','anchored.setBottom(false);','inverted.setBottom(false);')
    if re.search(r'(floatingPlacement|anchored|inverted)\s*\.\s*set(?:Bore|BoreExtendMinY|BoreExtendMaxY|SmartBore|Meld|Waterloggable)\s*\(',m): raise ValueError('Changed preserved effect settings')
    notes={
        'bore':'Clears the transformed object bounds before placement, including when force placement is active. Mode and warp affect the bounds; geometry is not previewed.',
        'boreExtendMinY':'Used only when bore is enabled. -1 raises the lower cleared boundary by one block. Editing this extension does not enable bore.',
        'boreExtendMaxY':'Used only when bore is enabled. Editing this extension does not enable bore.',
        'smartBore':'Iris selects a copied smart-bored runtime variant. The editor preserves the source .iob bytes and does not calculate this variant.',
        'meld':'Keeps blocks only where the destination is already solid. STRUCTURE_PIECE skips this test; force placement does not bypass it.',
        'waterloggable':'Native normalization uses this flag OR underwater and the existing destination water. Authored wet block states can stay wet while submerged even with this flag off; this does not create fluid.',
        'underwater':'Affects eligible native height queries and enables waterlogging normalization. Its placement guards depend on mode and force settings; it does not override a FLOATING absolute-Y anchor.',
        'onwater':'In this floating path, bypasses underwater and surface-support guards. It does not independently choose a fluid-level anchor here.',
        'fromBottom':'Overrides the usual mode-based anchor, including FLOATING. Native Y uses object height + 1 + rotated center offset, without adding the dimension minimum.'}
    for field in FIELDS:values[field+'.contextHelp']=notes[field]
    values['fromBottom.policy']='implicitFalse'
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__': Path(sys.argv[7]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:7])))
