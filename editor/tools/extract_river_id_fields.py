"""Verify literal dimension-owned river ID catalogs and nullable policy-list meanings."""
import hashlib
import json
import re
import sys
from pathlib import Path


def extract(policy, dimension, hydrology, rivers, profile, pool):
    texts = [raw.decode('utf-8') for raw in [policy,dimension,hydrology,rivers,profile,pool]]
    source, owner, hydro, river, profile_text, pool_text = texts
    for text in [hydro,river,profile_text,pool_text]:
        if '@NoArgsConstructor' not in text:
            raise ValueError('Changed hydrology constructor defaults')
    expectations = [(owner,r'private IrisHydrology hydrology = new IrisHydrology\(\);'),
                    (hydro,r'private IrisRiverHydrology rivers = new IrisRiverHydrology\(\);'),
                    (hydro,r'@ArrayType\(type = IrisSurfacePoolConfig\.class, min = 0\)\s+@Description\("[^\n]+"\)\s+private KList<IrisSurfacePoolConfig> surfacePools = new KList<>\(\);'),
                    (river,r'@ArrayType\(min = 1, type = IrisRiverProfile\.class\)\s+@Description\("[^\n]+"\)\s+private KList<IrisRiverProfile> profiles = new KList<IrisRiverProfile>\(\)\.qadd\(new IrisRiverProfile\(\)\);')]
    if any(not re.search(pattern,text) for text,pattern in expectations):
        raise ValueError('Changed hydrology parent/list defaults')
    values = dict(irisRevision='c3359e0a16438b1bb8468901c6171dcefdf63f7f', implicitProfileCount='1', implicitPoolCount='0')
    for key, raw in [('policy',policy),('dimension',dimension),('hydrology',hydrology),('rivers',rivers),('profile',profile),('pool',pool)]:
        values[key+'SourceSha256'] = hashlib.sha256(raw).hexdigest()
    for name, text, catalog, meaning, phrase in [
        ('profiles',profile_text,'hydrology.rivers.profiles','firstConfiguredProfile','An empty list clears inheritance and resolves to the first configured dimension profile.'),
        ('surfacePools',pool_text,'hydrology.surfacePools','clearInheritedSelection','An empty list explicitly clears the inherited selection.')]:
        field = re.search(r'((?:    @[^\n]+\n)+)    private KList<String> '+name+r' = null;',source)
        annotations = re.fullmatch(r'    @ArrayType\(type = String\.class\)\n    @Description\(("(?:[^"\\]|\\.)*")\)\n',field.group(1)) if field else None
        identifier = re.search(r'@Required\s+@Description\(("(?:[^"\\]|\\.)*")\)\s+private String id = ("(?:[^"\\]|\\.)*");',text)
        if not annotations or not identifier:
            raise ValueError('Changed ID-list or catalog identifier declaration')
        description, default_id = json.loads(annotations.group(1)), json.loads(identifier.group(2))
        if phrase not in description or not default_id.strip():
            raise ValueError('Changed empty selection meaning or blank ID default')
        values[name+'.default'] = 'null'
        values[name+'.catalogPath'] = catalog
        values[name+'.emptyMeaning'] = meaning
        values[name+'.description'] = description
        values[name+'.idDefault'] = default_id
    return ''.join(key+'='+value.replace('\\','\\\\').replace('\n','\\n').replace('\r','\\r')+'\n' for key,value in values.items()).encode('ascii')


if __name__ == '__main__':
    Path(sys.argv[7]).write_bytes(extract(*(Path(value).read_bytes() for value in sys.argv[1:7])))
