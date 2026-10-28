package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class StandingSurfacePoolTests {
    private static final String PATH="dimensions/standing-pools.json";
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] value) { return new String(value,StandardCharsets.UTF_8); }
    private static void check(boolean value) { if (!value) throw new AssertionError("Standing surface pool contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; }throw new AssertionError("Expected rejected standing pool operation"); }
    private static byte[] document(String pools) { return bytes("{\r\n \"hydrology\":{\"surfacePools\":"+pools+",\"rivers\":{\"enabled\":false,\"profiles\":[{\"id\":\"river\"}]},\"futureHydro\":9007199254740993},\"riverPolicy\":{\"surfacePools\":[\"hot\"]},\"escaped\":\"雪\\u0061\"\r\n}\r\n"); }
    private static LosslessJson hydro(byte[] value) { return HydrologyFields.hydrology(new LosslessJson(value)); }
    private static List<LosslessJson> pools(byte[] value) { return SurfacePools.entries(hydro(value)); }
    private static LosslessJson pool(byte[] value) { return pools(value).get(0); }
    private static LosslessJson palette(byte[] value) { return RiverProfiles.palette(pool(value)); }
    private static LosslessJson fluid(byte[] value,int index) { return RiverProfiles.fluids(palette(value)).get(index); }
    static void run(Path temp) throws Exception {
        byte[] parent=bytes("{\"hydrology\":{}}");byte[] enabled=SurfacePools.enable(PATH,parent);
        check(pools(enabled).isEmpty() && hydro(enabled).fields().equals(Set.of("surfacePools")));
        rejects(() -> SurfacePools.enable(PATH,enabled));
        rejects(() -> SurfacePools.change(PATH,parent,RiverProfiles.Action.ADD,-1,"new"));
        byte[] added=SurfacePools.change(PATH,enabled,RiverProfiles.Action.ADD,-1,"hot");
        check(pool(added).fields().equals(Set.of("id","fluidPalette")) && pool(added).stringToken("id").equals("hot"));
        check(fluid(added,0).fields().equals(Set.of("block")) && fluid(added,0).stringToken("block").equals("lava") && hydro(added).token("rivers")==null);
        check(pools(SurfacePools.change(PATH,added,RiverProfiles.Action.REMOVE,0,null)).isEmpty());
        for (String bad : List.of("","Upper","-first","_first","space here","id:key","a/b","a".repeat(65),"pool","surface","waterfall","sinkhole","underground","grotto","coastal_grotto","coastal-grotto","inland_grotto","mouth","deep","default")) rejects(() -> SurfacePools.change(PATH,enabled,RiverProfiles.Action.ADD,-1,bad));
        for (String good : List.of("a","0","a_b-c","a".repeat(64))) SurfacePools.setId(PATH,added,0,good);
        byte[] original=document("[{\"id\":\"hot\",\"density\":0.75,\"spacing\":384,\"minimumRadius\":4,\"maximumRadius\":7,\"depth\":2,\"biome\":\"missing\",\"fluidPalette\":{\"palette\":[ {\"block\":\"custom:stored\",\"weight\":1,\"data\":{\"future\":\"雪\\u0061\"},\"large\":9007199254740993}, {\"block\":\"lava\",\"weight\":2} ],\"zoom\":5e0,\"style\":{\"style\":\"UNKNOWN\",\"zoom\":1e0,\"cellularFrequency\":0,\"cellularZoom\":1,\"fracture\":{},\"expression\":\"kept\"},\"futurePalette\":true},\"futurePool\":true},{\"id\":\"cold\"}]");
        byte[] copy=original.clone();
        check(text(SurfacePools.setId(PATH,original,0,"renamed")).equals(text(original).replace("\"id\":\"hot\"","\"id\":\"renamed\"")));
        rejects(() -> SurfacePools.setId(PATH,original,0,"cold"));rejects(() -> SurfacePools.setId(PATH,original,0,"river"));
        rejects(() -> SurfacePools.change(PATH,original,RiverProfiles.Action.DUPLICATE,0,"cold"));
        byte[] duplicate=SurfacePools.change(PATH,original,RiverProfiles.Action.DUPLICATE,0,"copy");
        check(pools(duplicate).size()==3 && pools(duplicate).get(1).stringToken("id").equals("copy") && Arrays.equals(palette(original).sourceBytes(),RiverProfiles.palette(pools(duplicate).get(1)).sourceBytes()));
        byte[] moved=SurfacePools.change(PATH,original,RiverProfiles.Action.DOWN,0,null);
        check(Arrays.equals(pool(original).sourceBytes(),pools(moved).get(1).sourceBytes()));
        SurfacePools.change(PATH,moved,RiverProfiles.Action.UP,1,null);
        rejects(() -> SurfacePools.change(PATH,original,RiverProfiles.Action.UP,0,null));rejects(() -> SurfacePools.change(PATH,original,RiverProfiles.Action.DOWN,1,null));
        check(new LosslessJson(SurfacePools.change(PATH,original,RiverProfiles.Action.REMOVE,0,null)).tokenPath("riverPolicy.surfacePools").equals("[\"hot\"]"));
        rejects(() -> SurfacePools.setId(PATH,document("[{}, {\"id\":\"other\"}]"),1,"lava_pool"));
        check(pool(SurfacePools.setId(PATH,document("[{}]"),0,"explicit")).stringToken("id").equals("explicit"));
        check(pool(SurfacePools.setId(PATH,document("[{\"id\":\"same\"},{\"id\":\"same\"}]"),0,"fixed")).stringToken("id").equals("fixed"));
        Map<String,String> defaults=Map.of("density","0.75","spacing","384","minimumRadius","4","maximumRadius","7","depth","2");
        Map<String,int[]> bounds=Map.of("density",new int[]{0,64},"spacing",new int[]{32,8192},"minimumRadius",new int[]{2,16},"maximumRadius",new int[]{2,16},"depth",new int[]{1,8});
        byte[] bare=document("[{\"id\":\"bare\"}]");
        for (String field : defaults.keySet()) {
            check(SurfacePools.defaultToken(field).equals(defaults.get(field)) && !SurfacePools.help(field).isBlank());
            byte[] one=SurfacePools.enableScalar(PATH,bare,0,field);check(pool(one).token(field).equals(defaults.get(field)));
            rejects(() -> SurfacePools.enableScalar(PATH,one,0,field));rejects(() -> SurfacePools.setScalar(PATH,bare,0,field,defaults.get(field)));
            for (String invalid : List.of(Integer.toString(bounds.get(field)[0]-1),Integer.toString(bounds.get(field)[1]+1),"NaN","Infinity","1e999","")) rejects(() -> SurfacePools.setScalar(PATH,original,0,field,invalid));
            if (!field.equals("density")) rejects(() -> SurfacePools.setScalar(PATH,original,0,field,defaults.get(field)+".000000000000000001"));
            if (!field.endsWith("Radius")) {
                SurfacePools.setScalar(PATH,original,0,field,Integer.toString(bounds.get(field)[0]));SurfacePools.setScalar(PATH,original,0,field,Integer.toString(bounds.get(field)[1]));
            }
        }
        check(text(SurfacePools.setScalar(PATH,original,0,"spacing","5.12e2")).equals(text(original).replace("\"spacing\":384","\"spacing\":512")));
        SurfacePools.setScalar(PATH,original,0,"minimumRadius","7");SurfacePools.setScalar(PATH,original,0,"maximumRadius","4");
        rejects(() -> SurfacePools.setScalar(PATH,original,0,"minimumRadius","8"));rejects(() -> SurfacePools.setScalar(PATH,original,0,"maximumRadius","3"));
        byte[] paired=SurfacePools.setRadii(PATH,original,0,"1.6e1","16");
        check(pool(paired).token("minimumRadius").equals("16") && pool(paired).token("maximumRadius").equals("16"));
        check(pool(SurfacePools.setRadii(PATH,bare,0,"2","2")).token("minimumRadius").equals("2"));
        rejects(() -> SurfacePools.setRadii(PATH,original,0,"8","7"));rejects(() -> SurfacePools.setRadii(PATH,original,0,"1","2"));
        rejects(() -> SurfacePools.enableScalar(PATH,document("[{\"maximumRadius\":2}]"),0,"minimumRadius"));
        rejects(() -> SurfacePools.enableScalar(PATH,document("[{\"minimumRadius\":16}]"),0,"maximumRadius"));
        var workspace=PackWorkspace.newPack(Map.of(PATH,original,"biomes/nested/bed.json",bytes("{}"),"biomes/other.json",bytes("{}")));
        check(pool(SurfacePools.setBiome(PATH,original,0,"nested/bed",workspace)).stringToken("biome").equals("nested/bed"));
        check(pool(SurfacePools.setBiome(PATH,bare,0,"",workspace)).stringToken("biome").equals(""));
        rejects(() -> SurfacePools.setBiome(PATH,original,0,"missing",workspace));rejects(() -> SurfacePools.setBiome(PATH,original,0,"bed",workspace));
        var target=new HydrologyFluidPalettes.Target(true,0);var styleTarget=target.styleTarget();
        check(target.defaultFluid().equals("lava") && !target.fluidPrefix().startsWith(RiverProfiles.draftPrefix()));
        byte[] created=target.createPalette(PATH,bare);check(fluid(created,0).stringToken("block").equals("lava"));
        rejects(() -> target.createPalette(PATH,created));
        byte[] emptyPalette=document("[{\"fluidPalette\":{\"future\":true}}]");
        check(fluid(target.initialize(PATH,emptyPalette),0).stringToken("block").equals("lava"));
        check(palette(target.enableZoom(PATH,emptyPalette)).token("palette")==null);
        check(text(target.setZoom(PATH,original,"10")).equals(text(original).replace("\"zoom\":5e0","\"zoom\":10")));
        rejects(() -> target.setZoom(PATH,original,"0.00009"));
        for (String choice : NoiseStyleFields.choices()) check(text(NoiseStyleFields.editStyle(PATH,original,styleTarget,choice)).equals(text(original).replace("\"UNKNOWN\"","\""+choice+"\"")));
        byte[] seeded=NoiseStyleFields.enable(PATH,emptyPalette,styleTarget,"style");
        check(NoiseStyleFields.object(palette(seeded),styleTarget).stringToken("style").equals("STATIC"));
        byte[] classStyle=document("[{\"fluidPalette\":{\"style\":{}}}]");
        check(NoiseStyleFields.object(palette(NoiseStyleFields.enable(PATH,classStyle,styleTarget,"style")),styleTarget).stringToken("style").equals("FLAT"));
        byte[] zeroCell=NoiseStyleFields.editText(PATH,original,styleTarget,"cellularZoom","0");rejects(() -> NoiseStyleFields.editText(PATH,zeroCell,styleTarget,"cellularFrequency","1"));
        byte[] active=NoiseStyleFields.editText(PATH,original,styleTarget,"cellularFrequency","1");rejects(() -> NoiseStyleFields.editText(PATH,active,styleTarget,"cellularZoom","0"));
        check(text(target.setFluid(PATH,original,0,"Minecraft:WATER[level=0]")).equals(text(original).replace("\"block\":\"custom:stored\"","\"block\":\"Minecraft:WATER[level=0]\"")));
        for (String valid : List.of("water"," lava ","minecraft:bubble_column[drag=true]","custom:unverified")) target.setFluid(PATH,original,0,valid);
        for (String invalid : List.of(""," ","stone","minecraft:dirt")) rejects(() -> target.setFluid(PATH,original,0,invalid));
        check(text(target.setWeight(PATH,original,0,"6.4e1")).equals(text(original).replace("\"weight\":1,","\"weight\":64,")));
        for (String invalid : List.of("0","65","1.000000000000000001","2147483648","NaN")) rejects(() -> target.setWeight(PATH,original,0,invalid));
        check(fluid(target.enableWeight(PATH,added,0),0).token("weight").equals("1"));
        byte[] fluidCopy=target.change(PATH,original,RiverProfiles.Action.DUPLICATE,0,null);check(Arrays.equals(fluid(fluidCopy,0).sourceBytes(),fluid(fluidCopy,1).sourceBytes()));
        check(fluid(target.change(PATH,original,RiverProfiles.Action.ADD,-1,"water"),2).fields().equals(Set.of("block")));
        byte[] fluidMoved=target.change(PATH,original,RiverProfiles.Action.DOWN,0,null);check(Arrays.equals(fluid(original,0).sourceBytes(),fluid(fluidMoved,1).sourceBytes()));
        target.change(PATH,fluidMoved,RiverProfiles.Action.UP,1,null);target.change(PATH,original,RiverProfiles.Action.REMOVE,0,null);
        rejects(() -> target.change(PATH,added,RiverProfiles.Action.REMOVE,0,null));
        check(fluid(target.change(PATH,document("[{\"fluidPalette\":{\"palette\":[]}}]"),RiverProfiles.Action.ADD,-1,"water"),0).stringToken("block").equals("water"));
        var readOnly=DimensionFields.readOnlyPaths(new LosslessJson(original));
        check(readOnly.containsAll(List.of("hydrology.surfacePools[0].futurePool","hydrology.surfacePools[0].fluidPalette.futurePalette","hydrology.surfacePools[0].fluidPalette.palette[0].large")));
        for (String bad : List.of("null","{}","true","1","\"snippet\"","[null]","[{},1]")) {
            byte[] invalid=document(bad);rejects(() -> SurfacePools.enable(PATH,invalid));rejects(() -> SurfacePools.change(PATH,invalid,RiverProfiles.Action.ADD,-1,"valid"));
            check(DimensionFields.readOnlyPaths(new LosslessJson(invalid)).contains("hydrology.surfacePools"));
        }
        for (String bad : List.of("null","[]","{}","true","\"snippet\"")) {
            byte[] invalid=document("[{\"id\":"+bad+",\"density\":"+bad+",\"biome\":"+bad+",\"fluidPalette\":"+bad+",\"minimumRadius\":4,\"maximumRadius\":"+bad+"}]");
            if (!bad.startsWith("\"")) rejects(() -> SurfacePools.setId(PATH,invalid,0,"valid"));
            rejects(() -> SurfacePools.setScalar(PATH,invalid,0,"density","1"));rejects(() -> SurfacePools.setRadii(PATH,invalid,0,"4","7"));
            if (!bad.startsWith("\"")) rejects(() -> SurfacePools.setBiome(PATH,invalid,0,"",workspace));
            rejects(() -> target.createPalette(PATH,invalid));
            if (bad.equals("{}")) {
                check(fluid(target.initialize(PATH,invalid),0).stringToken("block").equals("lava"));
                check(NoiseStyleFields.object(palette(NoiseStyleFields.enable(PATH,invalid,styleTarget,"style")),styleTarget).stringToken("style").equals("STATIC"));
            } else { rejects(() -> target.initialize(PATH,invalid));rejects(() -> NoiseStyleFields.enable(PATH,invalid,styleTarget,"style")); }
            check(pool(SurfacePools.enableScalar(PATH,invalid,0,"depth")).token("density").equals(bad));
        }
        byte[] uninspectable=document("[{\"density\":1}]");uninspectable=bytes(text(uninspectable).replace("\"profiles\":[{\"id\":\"river\"}]","\"profiles\":null"));byte[] invalidCatalog=uninspectable;
        rejects(() -> SurfacePools.setId(PATH,invalidCatalog,0,"new"));check(pool(SurfacePools.setScalar(PATH,invalidCatalog,0,"density","2")).token("density").equals("2"));
        StringBuilder fullList=new StringBuilder("[");for (int i=0;i<64;i++) { if (i>0) fullList.append(',');fullList.append("{\"id\":\"p").append(i).append("\"}"); }
        byte[] full=document(fullList+"]");rejects(() -> SurfacePools.change(PATH,full,RiverProfiles.Action.ADD,-1,"extra"));rejects(() -> SurfacePools.change(PATH,full,RiverProfiles.Action.DUPLICATE,0,"extra"));check(pools(SurfacePools.change(PATH,full,RiverProfiles.Action.REMOVE,0,null)).size()==63);
        for (String missing : List.of("{}","{\"hydrology\":null}")) rejects(() -> SurfacePools.enable(PATH,bytes(missing)));
        for (String path : List.of("regions/a.json","biomes/a.json","generators/a.json","dimensions/nested/a.json","a.json")) {
            rejects(() -> SurfacePools.enable(path,parent));rejects(() -> SurfacePools.setRadii(path,original,0,"4","7"));rejects(() -> target.setFluid(path,original,0,"water"));rejects(() -> NoiseStyleFields.enable(path,created,styleTarget,"style"));
        }
        rejects(() -> NoiseStyleFields.Target.poolPalette(-1));rejects(() -> new NoiseStyleFields.Target("biomes",styleTarget.objectField(),null));rejects(() -> new NoiseStyleFields.Target("dimensions",styleTarget.objectField(),null,0));
        check(SurfacePools.styleIndex("hydrology.surfacePools.01.fluidPalette.style")==-1 && SurfacePools.styleIndex("hydrology.surfacePools.2147483648.fluidPalette.style")==-1);
        var drafts=new InspectorDrafts();String entryKey=target.fluidPrefix()+"0.block",noiseKey=target.noisePrefix()+"zoom",radiusKey=SurfacePools.poolPrefix(0)+"minimumRadius",profileKey=RiverProfiles.profilePrefix(0)+"id";
        for (String key : List.of(entryKey,noiseKey,radiusKey,profileKey)) drafts.update(key,"1","2");
        check(!drafts.transitionUnder(SurfacePools.draftPrefix(),() -> false,() -> { throw new AssertionError(); }) && drafts.count()==4);
        try { drafts.transitionUnder(SurfacePools.draftPrefix(),() -> true,() -> { throw new IOException("failed write"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==4); }
        check(drafts.transitionUnder(target.fluidPrefix(),() -> true,() -> {}) && drafts.count()==3 && drafts.contains(noiseKey));
        check(drafts.transitionUnder(SurfacePools.draftPrefix(),() -> true,() -> {}) && drafts.count()==1 && drafts.contains(profileKey));
        check(Arrays.equals(original,copy));var cache=new RiverIdLists.CatalogCache();check(cache.inspect(workspace,PATH,null,"surfacePools").ids().equals(List.of("hot","cold")));
        workspace.replace(PATH,SurfacePools.setId(PATH,original,0,"renamed"));check(cache.inspect(workspace,PATH,null,"surfacePools").ids().equals(List.of("renamed","cold")));
        workspace.save(temp.resolve("standing-pools"));workspace.exportZip(temp.resolve("standing-pools.zip"));var reopened=PackWorkspace.open(temp.resolve("standing-pools"));var imported=PackWorkspace.importZip(temp.resolve("standing-pools.zip"));
        for (String path : workspace.paths()) check(Arrays.equals(workspace.read(path),reopened.read(path)) && Arrays.equals(workspace.read(path),imported.read(path)));
        check(FieldCoverage.fields("standingSurfacePool").size()==8 && FieldCoverage.fields("standingSurfacePool").stream().allMatch(FieldCoverage.Field::implemented));
        System.out.println("PASS: standing surface pool defaults/native bounds, ID uniqueness/reserved/profile guards, atomic radii, exact biomes, shared weighted fluids/styles, catalog/draft isolation and lossless Save/ZIP contracts");
    }
}
