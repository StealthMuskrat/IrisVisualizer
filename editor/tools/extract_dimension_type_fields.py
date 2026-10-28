"""Pin dimension-type sentinels/null states, native declarations and resolution consumers."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact

def extract(dimension,options,environment,fixer,runtime):
    sources=[dimension,options,environment,fixer,runtime];d,o,e,f,r=[raw.decode('utf-8') for raw in sources]
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f'}
    for name,raw in zip(['dimension','options','environment','fixer','runtime'],sources):values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text):raise ValueError('Changed dimension-type contract: '+contract)
    def declaration(text,name,kind,default,prefix=''):
        match=re.search(prefix+r'\s*@Description\(("(?:[^"\\]|\\.)*")\)\s*private '+kind+' '+name+' = '+re.escape(default)+r';',text)
        if not match:raise ValueError('Changed dimension-type declaration '+name)
        values[name+'.description']=json.loads(match.group(1))
    declaration(d,'dimensionOptions','IrisDimensionTypeOptions','new IrisDimensionTypeOptions()')
    declaration(d,'environment','IrisEnvironment','IrisEnvironment.NORMAL');values['environment.default']='NORMAL'
    declaration(d,'fullbright','boolean','false');values['fullbright.default']='false'
    specs=[('coordinateScale','double','-1','0.00001','30000000',False),('ambientLight','float','-1','0','1',False),
           ('fixedTime','Long','-1L','0','Long.MAX_VALUE',True),('cloudHeight','Integer','-1','-2032','2031',True),
           ('monsterSpawnBlockLightLimit','int','-1','0','15',False)]
    for name,kind,default,minimum,maximum,nullable in specs:
        prefix=(r'@Nullable\s*' if nullable else '')+r'@MinNumber\('+re.escape(minimum)+r'\)\s*@MaxNumber\('+re.escape(maximum)+r'\)'
        declaration(o,name,kind,default,prefix);values[name+'.default']='-1';values[name+'.minimum']=minimum;values[name+'.maximum']='9223372036854775807' if maximum=='Long.MAX_VALUE' else maximum
        require(o,'if ('+name+(' != null && '+name+' == -1L)' if name=='fixedTime' else ' != null && '+name+' == -1)' if name=='cloudHeight' else ' == -1)')+' '+name+' = other.'+name+';')
    states=['ultrawarm','natural','piglinSafe','respawnAnchorWorks','bedWorks','raids','skylight','ceiling']
    for name in states:
        declaration(o,name,'TriState','DEFAULT',r'@NonNull');values[name+'.default']='DEFAULT';require(o,'if ('+name+' == DEFAULT) '+name+' = other.'+name+';')
    tri=re.search(r'public enum TriState \{(.*?)public boolean bool\(\)',o,re.S)
    if not tri or re.findall(r'@Description\("[^"\n]*"\)\s*(\w+)[,;]',tri.group(1))!=['DEFAULT','TRUE','FALSE']:raise ValueError('Changed TriState choices')
    env=re.findall(r'@Description\("[^"\n]*"\)\s*(\w+)[,;]?\s*(?=\n|\})',e)
    if env!=['NORMAL','NETHER','THE_END','CUSTOM']:raise ValueError('Changed environment choices')
    require(d,'return switch (environment) { case NETHER -> Dimension.NETHER; case THE_END -> Dimension.END; default -> Dimension.OVERWORLD; };',
            'IrisDimensionTypeOptions options = getDimensionOptions(); if (fullbright) { options = options.copy().ambientLight(1.0f); } return new IrisDimensionType(getBaseDimension(), options, getLogicalHeight(), getMaxHeight() - getMinHeight(), getMinHeight());')
    require(o,'if (fixedTime != null) json.put("fixed_time", fixedTime);','if (cloudHeight != null) json.put("cloud_height", cloudHeight);',
            'if (!isComplete()) throw new IllegalStateException("Cannot serialize incomplete options");',
            '&& (fixedTime == null || fixedTime != -1L) && (cloudHeight == null || cloudHeight != -1);')
    require(f,'return options == null ? OPTIONS.get(dimension).toJson() : options.resolve(OPTIONS.get(dimension)).toJson();')
    require(r,'if (!Objects.equals(active.getEnvironment(), replacement.getEnvironment())) { throw hotloadMismatch(runtimeName, active, replacement); }',
            'JSONObject activeType = effectiveDimensionType(active); JSONObject replacementType = effectiveDimensionType(replacement); if (!replacementType.similar(activeType)) { throw hotloadMismatch(runtimeName, active, replacement); }')
    values.update({'states':'DEFAULT,TRUE,FALSE','environments':','.join(env),'inherit.number':'-1','fixedTime.null':'timeCycle','cloudHeight.null':'cloudsDisabled','base.policy':'environmentFallbackOverworld','fullbright.policy':'copyAmbientOne','hotload.policy':'nativeCompatibilityCheck'})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__':Path(sys.argv[6]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:6])))
