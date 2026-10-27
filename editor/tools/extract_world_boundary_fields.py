"""Pin native world-border declarations, validation and Bukkit/modded application."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact

REVISION='c3359e0a16438b1bb8468901c6171dcefdf63f7f'

def extract(dimension,boundary,center,bukkit,modded):
    sources=[dimension,boundary,center,bukkit,modded];d,b,c,k,m=[raw.decode('utf-8') for raw in sources]
    values={'irisRevision':REVISION}
    for name,raw in zip(['dimension','boundary','center','bukkit','modded'],sources):values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text):raise ValueError('Changed world-border contract: '+contract)
    def declaration(text,field,kind,default,annotations=''):
        pattern=annotations+r'\s*@Description\(("(?:[^"\\]|\\.)*")\)\s*private '+kind+' '+field+' = '+re.escape(default)+r';'
        match=re.search(pattern,text)
        if not match:raise ValueError('Changed border declaration '+field)
        return json.loads(match.group(1))
    values['worldBoundary.description']=declaration(d,'worldBoundary','IrisWorldBoundary','null')
    values['center.description']=declaration(b,'center','IrisWorldBoundaryCenter','new IrisWorldBoundaryCenter()')
    require(b,'public static final double DEFAULT_DAMAGE_AMOUNT = 0.2D;','public static final double DEFAULT_DAMAGE_BUFFER = 5D;',
            'public static final double MAXIMUM_CENTER = 29_999_984D;','public static final double MAXIMUM_SIZE = 59_999_968D;')
    contracts=[('size','double','16_384D',r'@MinNumber\(1\)\s*@MaxNumber\(59_999_968\)','16384','1','59999968'),
               ('warningDistance','int','16',r'@MinNumber\(0\)\s*@MaxNumber\(Integer.MAX_VALUE\)','16','0','2147483647'),
               ('damageBuffer','double','DEFAULT_DAMAGE_BUFFER',r'@MinNumber\(0\)','5','0',None),
               ('damageAmount','double','DEFAULT_DAMAGE_AMOUNT',r'@MinNumber\(0\)','0.2','0',None)]
    for field,kind,default,annotations,number,minimum,maximum in contracts:
        values[field+'.description']=declaration(b,field,kind,default,annotations);values[field+'.default']=number;values[field+'.minimum']=minimum
        if maximum is not None:values[field+'.maximum']=maximum
    for axis in ['x','z']:
        field='center.'+axis;values[field+'.description']=declaration(c,axis,'double','0D',r'@MinNumber\(-29_999_984\)\s*@MaxNumber\(29_999_984\)')
        values[field+'.default']='0';values[field+'.minimum']='-29999984';values[field+'.maximum']='29999984'
    require(b,'IrisWorldBoundary source = Objects.requireNonNull(configured, "Configured worldBoundary"); source.validate(); return new IrisWorldBoundary().setCenter(new IrisWorldBoundaryCenter(source.getCenter().getX(), source.getCenter().getZ())).setSize(source.getSize()).setWarningDistance(source.getWarningDistance()).setDamageBuffer(source.getDamageBuffer()).setDamageAmount(source.getDamageAmount());',
            'if (center == null) { throw new IllegalArgumentException("worldBoundary.center is required"); }',
            'if (!Double.isFinite(center.getX()) || Math.abs(center.getX()) > MAXIMUM_CENTER || !Double.isFinite(center.getZ()) || Math.abs(center.getZ()) > MAXIMUM_CENTER)',
            'if (!Double.isFinite(size) || size < 1D || size > MAXIMUM_SIZE)', 'if (warningDistance < 0)',
            'if (!Double.isFinite(damageBuffer) || damageBuffer < 0D)', 'if (!Double.isFinite(damageAmount) || damageAmount < 0D)',
            'return center.getX() - size / 2D;', 'return center.getX() + size / 2D;', 'return center.getZ() - size / 2D;', 'return center.getZ() + size / 2D;')
    for source in [k,m]:
        require(source,'IrisWorldBoundary configuredBoundary = expectedDimension.getWorldBoundary(); if (configuredBoundary == null) { return; }',
                'boundary = IrisWorldBoundary.snapshot(configuredBoundary);', 'if (boundary == null) { return; }')
    require(k,'worldBorder.setCenter(boundary.getCenter().getX(), boundary.getCenter().getZ()); worldBorder.setSize(boundary.getSize()); worldBorder.setWarningDistance(boundary.getWarningDistance()); worldBorder.setDamageBuffer(boundary.getDamageBuffer()); worldBorder.setDamageAmount(boundary.getDamageAmount());')
    require(m,'NativeWorldBoundary.apply(world, new NativeWorldBoundary.Settings(boundary.getCenter().getX(), boundary.getCenter().getZ(), boundary.getSize(), boundary.getWarningDistance(), boundary.getDamageBuffer(), boundary.getDamageAmount()));')
    values.update({'null.policy':'leaveNativeBorderUnchanged','size.policy':'fullDiameter','validation.policy':'finiteNativeBounds','center.policy':'atomicPair'})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__':Path(sys.argv[6]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:6])))
