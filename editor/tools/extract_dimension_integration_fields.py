"""Pin borrowed type, source URL and mantle flag formats without loading/fetching integrations."""
import hashlib
import json
import re
import sys
from pathlib import Path
from extract_river_material_fields import compact

def extract(*sources):
    d,v,r,g,m,i,a,s,e,c,f,x,z,j=[raw.decode('utf-8') for raw in sources]
    values={'irisRevision':'c3359e0a16438b1bb8468901c6171dcefdf63f7f','volmLibRevision':'210705c5ab1321dc74b86b48457fd6d5e0452944'}
    for name,raw in zip(['dimension','validator','runtime','registry','moddedRegistry','imports','archive','startup','engineMantle','componentFlags','mantleFlag','flagAdapter','reservedFlag','irisData'],sources):values[name+'SourceSha256']=hashlib.sha256(raw).hexdigest()
    def require(text,*contracts):
        for contract in contracts:
            if compact(contract) not in compact(text):raise ValueError('Changed dimension integration contract: '+contract)
    for field,kind,default in [('borrowedDimensionType','String','""'),('datapackImports','KList<String>','new KList<>()'),('disabledComponents','KList<MantleFlag>','new KList<>()')]:
        match=re.search(r'(?P<annotations>(?:\s*@[^\n]+\n)+)\s*private '+re.escape(kind)+' '+field+r' = (?P<default>[^;]+);',d)
        if not match or match['default']!=default:raise ValueError('Changed integration declaration '+field)
        description=re.search(r'@Description\(("(?:[^"\\]|\\.)*")\)',match['annotations'])
        if not description:raise ValueError('Missing integration description')
        values[field+'.description']=json.loads(description.group(1))
    require(d,'return borrowedDimensionType != null && !borrowedDimensionType.isBlank();','return hasBorrowedDimensionType() && registry.supportsBorrowedDimensionTypes();','if (borrowsDimensionType(registry)) { return borrowedDimensionType.trim(); }','!disabledComponents.contains(ReservedFlag.CARVED)')
    pattern=re.search(r'private static final Pattern RESOURCE_KEY = Pattern.compile\(("(?:[^"\\]|\\.)*")\);',v)
    if not pattern or json.loads(pattern.group(1))!='[a-z0-9_.-]+:[a-z0-9/._-]+':raise ValueError('Changed borrowed type key syntax')
    values['borrowed.pattern']=json.loads(pattern.group(1))
    require(v,'if (!dimension.has(BORROWED_DIMENSION_TYPE) || dimension.isNull(BORROWED_DIMENSION_TYPE)) { return; }','if (value instanceof String key && key.isEmpty()) { return; }','!RESOURCE_KEY.matcher(key).matches()','if (!borrowingSupported)')
    require(r,'if (minHeight == actual.minHeight() && maxHeight() <= actual.maxHeight())','if (activeBorrowed.equals(replacementBorrowed)) { return; }','Close and reopen Studio after changing borrowedDimensionType.')
    require(g,'default boolean supportsBorrowedDimensionTypes() { return false; }');require(m,'public boolean supportsBorrowedDimensionTypes() { return true; }')
    require(i,'if (explicit != null) { addImports(explicit, sources); }','if (discovered != null) { addImports(discovered, sources); }','if (imports == null) { continue; }',
            'if (source != null && !source.isBlank()) { sources.add(normalizeConfiguredSource(source)); }','String normalized = source.trim();','"file".equalsIgnoreCase(uri.getScheme())','return Path.of(uri).toAbsolutePath().normalize().toUri().toASCIIString();','return sources.isEmpty() ? Set.of() : Set.copyOf(sources);', 'LOCAL_IMPORT_DIRECTORY = "imports";')
    require(s,'if (source == null || source.isBlank()) { return null; }','return new URI(source.trim());')
    require(a,'if ("file".equalsIgnoreCase(current.getScheme())) { return copyLocalDatapack(current, dest); }','if (!"http".equalsIgnoreCase(current.getScheme()) && !"https".equalsIgnoreCase(current.getScheme()))',
            'source.isOpaque()','source.getRawAuthority() != null','source.getRawQuery() != null','source.getRawFragment() != null','Path.of(source).toAbsolutePath().normalize()')
    require(e,'disabled.addAll(getDimension().getDisabledComponents());','component.setEnabled(!getDisabledFlags().contains(component.getFlag()));',
            'if (disabled.contains(ReservedFlag.CARVED) || !isHydrologyEnabled(getDimension())) { disabled.addIfMissing(ReservedFlag.RIVER_HYDROLOGY); }')
    require(c,'if (engine != null) return engine.getMantle().getComponentFlags().toStringList();','c.isAnnotationPresent(ComponentFlag.class)','MantleComponent.class::isAssignableFrom','.map(MantleFlag::toString)')
    require(f,'int MIN_ORDINAL = 64;','int MAX_ORDINAL = 255;','if (ordinal < MIN_ORDINAL || ordinal > MAX_ORDINAL)','return new CustomFlag("CUSTOM:" + ordinal, ordinal);')
    require(x,'private static final String CUSTOM = "CUSTOM:";','return MantleFlag.of(Integer.parseInt(s.substring(CUSTOM_LENGTH)));','return ReservedFlag.valueOf(s);')
    names=re.findall(r'^\s*([A-Z_]+)\s*[,;]',z,re.M)
    expected=['OBJECT','UPDATE','JIGSAW','FEATURE','INITIAL_SPAWNED','REAL','CARVED','FLUID_BODIES','INITIAL_SPAWNED_MARKER','CLEANED','PLANNED','ETCHED','TILE','CUSTOM','DISCOVERED','CUSTOM_ACTIVE','SCRIPT','FLOATING_OBJECT','RIVER_HYDROLOGY']
    if names!=expected:raise ValueError('Changed reserved mantle flags')
    require(j,'.registerTypeAdapter(MantleFlag.class, new MantleFlagAdapter())')
    values.update({'reservedFlags':','.join(names),'custom.minimum':'64','custom.maximum':'255','borrowed.policy':'moddedOnly','datapackImports.null':'emptySources','disabledComponents.null':'unsupported','registry.policy':'unverified','source.policy':'syntaxOnlyNoIO'})
    return ''.join(key+'='+json.dumps(value,ensure_ascii=True)[1:-1]+'\n' for key,value in values.items()).encode('ascii')

if __name__=='__main__':Path(sys.argv[15]).write_bytes(extract(*(Path(path).read_bytes() for path in sys.argv[1:15])))
