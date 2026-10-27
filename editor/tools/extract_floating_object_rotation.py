"""Audit contextual rotation defaults and the consumers that preserve or replace them."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact

def extract(rotation,axis,placement,mantle,runner):
    sources=[rotation,axis,placement,mantle,runner]
    r,a,p,m,c=[raw.decode('utf-8') for raw in sources]
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text): raise ValueError('Changed floating rotation contract: '+contract)
    require(r,'@NoArgsConstructor')
    require(a,'@NoArgsConstructor','private transient boolean forceLock = false;',
            'return min == max && min == 0;','return min == max && !isUnlimited();',
            'double resolvedInterval = interval < 1 ? 1 : interval;',
            'double resolvedInterval = interval <= 0 ? 1 : interval;',
            'Math.min(min, max), Math.max(min, max)',
            'return Math.toRadians(max);')
    require(p,'private IrisObjectRotation rotation = new IrisObjectRotation();','p.setRotation(rotation);')
    require(m,'IrisObjectPlacement floatingPlacement = placement.toPlacement(obj.getLoadKey());',
            'anchored.setRotation(ROTATION_NONE);','inverted.setRotation(invertedRotation);')
    floating=re.search(r'private void tryPlaceFloatingChunk\(.*?\n    \}',m,re.DOTALL)
    if not floating or re.search(r'floatingPlacement\s*\.\s*setRotation',floating.group()): raise ValueError('Floating rotation is newly overridden')
    require(c,'SpinKernel spin = new SpinKernel(config.getRotation(), spinx, spiny, spinz);',
            'double newRotation = config.getRotation().getYAxis().getMin() + slopeRotationY;',
            'config.setRotation(slopeRotation);','data = config.getRotation().rotate(data, spinx, spiny, spinz);')
    for member in ['xAxis','yAxis','zAxis']: require(r,'return enabled && '+member+'.isEnabled();')
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for name,raw in zip(['rotation','axis','placement','mantle','runner'],sources): values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    enabled=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private boolean enabled = (true|false);',r)
    if not enabled: raise ValueError('Missing rotation enabled declaration')
    values['rotation.enabled.default']=enabled.group(2)
    values['rotation.enabled.description']=json.loads(enabled.group(1))
    for field in ['enabled','min','max','interval']:
        kind='boolean' if field=='enabled' else 'double'
        match=re.search(r'((?:    @[^\n]+\n)+)    private '+kind+' '+field+r' = ([^;]+);',a)
        if not match: raise ValueError('Changed axis field: '+field)
        annotations,default=match.groups();values['axis.'+field+'.default']=default
        description=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)',annotations)
        if not description: raise ValueError('Missing axis description')
        values['axis.'+field+'.description']=json.loads(description.group(1))
        if field!='enabled':
            for name,pattern in [('minimum',r'@MinNumber\(([^)]+)\)'),('maximum',r'@MaxNumber\(([^)]+)\)')]:
                found=re.search(pattern,annotations)
                if not found: raise ValueError('Missing angle bounds')
                values['axis.'+field+'.'+name]=found.group(1)
    for field in ['xAxis','yAxis','zAxis']:
        match=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private IrisAxisRotationClamp '+field+r' = new IrisAxisRotationClamp\(([^)]*)\);',r)
        if not match: raise ValueError('Changed rotation axis')
        values['rotation.'+field+'.description']=json.loads(match.group(1))
        args=[x.strip() for x in match.group(2).split(',')] if match.group(2).strip() else []
        if field=='yAxis':
            if args!=['true','false','0','0','90']: raise ValueError('Changed contextual Y axis constructor')
            token={'enabled':True,'interval':90}
        else:
            if args: raise ValueError('Changed X/Z constructor')
            token={}
        values['rotation.'+field+'.default']=json.dumps(token,separators=(',',':'))
    description=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)\s+private IrisObjectRotation rotation = new IrisObjectRotation\(\);',p)
    if not description: raise ValueError('Missing placement rotation description')
    values['rotation.description']=json.loads(description.group(1))
    values.update({'context':'floatingObjectsOnly','axis.policy':'contextualCreationClassInline','angles.policy':'orderedEditorSubset'})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__': Path(sys.argv[6]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:6])))
