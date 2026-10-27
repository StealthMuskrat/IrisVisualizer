"""Reject drift in dimension ID catalogs, class defaults and policy empty-list meanings."""
from extract_river_id_fields import extract

policy = b'''    @ArrayType(type = String.class)
    @Description("An empty list clears inheritance and resolves to the first configured dimension profile.")
    private KList<String> profiles = null;
    @ArrayType(type = String.class)
    @Description("An empty list explicitly clears the inherited selection.")
    private KList<String> surfacePools = null;
'''
dimension = b'private IrisHydrology hydrology = new IrisHydrology();'
hydrology = b'''@NoArgsConstructor
private IrisRiverHydrology rivers = new IrisRiverHydrology();
@ArrayType(type = IrisSurfacePoolConfig.class, min = 0)
@Description("Pools")
private KList<IrisSurfacePoolConfig> surfacePools = new KList<>();'''
rivers = b'''@NoArgsConstructor
@ArrayType(min = 1, type = IrisRiverProfile.class)
@Description("Profiles")
private KList<IrisRiverProfile> profiles = new KList<IrisRiverProfile>().qadd(new IrisRiverProfile());'''
profile = b'@NoArgsConstructor @Required @Description("ID") private String id = "default";'
pool = b'@NoArgsConstructor @Required @Description("ID") private String id = "lava_pool";'
data = extract(policy,dimension,hydrology,rivers,profile,pool).decode('ascii')
for item in ['implicitProfileCount=1\n','implicitPoolCount=0\n','profiles.default=null\n','surfacePools.default=null\n',
             'profiles.idDefault=default\n','surfacePools.idDefault=lava_pool\n','profiles.catalogPath=hydrology.rivers.profiles\n',
             'surfacePools.catalogPath=hydrology.surfacePools\n','profiles.emptyMeaning=firstConfiguredProfile\n','surfacePools.emptyMeaning=clearInheritedSelection\n']:
    assert item in data


def rejects(p=policy,d=dimension,h=hydrology,r=rivers,a=profile,b=pool):
    try:
        extract(p,d,h,r,a,b)
    except ValueError:
        return
    raise AssertionError('Changed river ID catalog contract silently accepted')


rejects(p=policy.replace(b'profiles = null',b'profiles = new KList<>()'))
rejects(p=policy.replace(b'@ArrayType(type = String.class)',b'@Required\n    @ArrayType(type = String.class)',1))
rejects(p=policy.replace(b'resolves to the first configured dimension profile',b'resolves to no profile'))
rejects(d=dimension.replace(b'new IrisHydrology()',b'null'))
rejects(h=hydrology.replace(b'new IrisRiverHydrology()',b'null'))
rejects(h=hydrology.replace(b'new KList<>()',b'new KList<>().qadd(new IrisSurfacePoolConfig())'))
rejects(r=rivers.replace(b'.qadd(new IrisRiverProfile())',b''))
rejects(a=profile.replace(b'@NoArgsConstructor',b''))
rejects(a=profile.replace(b'"default"',b'null'))
rejects(b=pool.replace(b'"lava_pool"',b'" "'))
print('PASS: river ID catalogs, implicit lists, required ID defaults and empty-selection meanings reject drift')
