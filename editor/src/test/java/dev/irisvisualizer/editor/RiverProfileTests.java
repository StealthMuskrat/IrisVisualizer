package dev.irisvisualizer.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RiverProfileTests {
    private static final String PATH="dimensions/profiles.json";
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] bytes) { return new String(bytes,StandardCharsets.UTF_8); }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("River profile contract failed"); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError("Expected rejected river profile operation"); }
    private static byte[] document(String profiles) { return bytes("{\r\n \"hydrology\":{\"rivers\":{\"enabled\":false,\"profiles\":"+profiles+",\"future\":9007199254740993},\"deepFluids\":[]},\"riverPolicy\":{\"profiles\":[\"default\"]},\"escaped\":\"雪\\u0061\"\r\n}\r\n"); }
    private static LosslessJson rivers(byte[] value) { return HydrologyFields.rivers(HydrologyFields.hydrology(new LosslessJson(value))); }
    private static List<LosslessJson> profiles(byte[] value) { return RiverProfiles.entries(rivers(value)); }
    private static LosslessJson palette(byte[] value,int index) { return RiverProfiles.palette(profiles(value).get(index)); }
    private static LosslessJson fluid(byte[] value,int profile,int index) { return RiverProfiles.fluids(palette(value,profile)).get(index); }
    private static LosslessJson style(byte[] value,int index) { return NoiseStyleFields.object(palette(value,index),NoiseStyleFields.Target.profilePalette(index)); }
    static void run(Path temp) throws Exception {
        byte[] parent=bytes("{\"hydrology\":{\"rivers\":{\"enabled\":false}}}");
        byte[] enabled=RiverProfiles.enable(PATH,parent);
        check(profiles(enabled).size()==1 && profiles(enabled).get(0).fields().equals(Set.of("id","fluidPalette")));
        check(profiles(enabled).get(0).stringToken("id").equals("default") && fluid(enabled,0,0).stringToken("block").equals("water"));
        check(fluid(enabled,0,0).fields().equals(Set.of("block")) && rivers(enabled).token("enabled").equals("false"));
        rejects(() -> RiverProfiles.enable(PATH,enabled));
        rejects(() -> RiverProfiles.change(PATH,parent,RiverProfiles.Action.ADD,-1,"new"));
        rejects(() -> RiverProfiles.change(PATH,enabled,RiverProfiles.Action.REMOVE,0,null));
        for (String bad : List.of("","Upper","-prefix","_prefix","contains space","a:b","x/y","a".repeat(65))) {
            rejects(() -> RiverProfiles.setId(PATH,enabled,0,bad));
            rejects(() -> RiverProfiles.change(PATH,enabled,RiverProfiles.Action.ADD,-1,bad));
        }
        for (String good : List.of("a","0","a_b-c","a".repeat(64))) RiverProfiles.setId(PATH,enabled,0,good);
        String first="{\"id\":\"default\",\"fluidPalette\":{\"palette\":[ {\"block\":\"custom:fluid\",\"weight\":1,\"data\":{\"unknown\":\"雪\\u0061\"},\"tileData\":{},\"backup\":null,\"future\":9007199254740993}, {\"block\":\"lava\",\"weight\":2} ],\"style\":{\"style\":\"UNKNOWN\",\"zoom\":1e0,\"cellularFrequency\":0,\"cellularZoom\":1,\"expression\":\"kept\",\"fracture\":{}},\"zoom\":5e0,\"futurePalette\":true},\"futureProfile\":true}";
        String second="{\"id\":\"other\",\"fluidPalette\":{\"palette\":[{\"block\":\"water\"}]}}";
        byte[] original=document("[ "+first+",\r\n "+second+" ]"),copy=original.clone();
        byte[] renamed=RiverProfiles.setId(PATH,original,0,"renamed");
        check(text(renamed).equals(text(original).replace("\"id\":\"default\"","\"id\":\"renamed\"")));
        check(new LosslessJson(renamed).tokenPath("riverPolicy.profiles").equals("[\"default\"]"));
        rejects(() -> RiverProfiles.setId(PATH,original,0,"other"));
        rejects(() -> RiverProfiles.change(PATH,original,RiverProfiles.Action.ADD,-1,"default"));
        rejects(() -> RiverProfiles.change(PATH,original,RiverProfiles.Action.DUPLICATE,0,"other"));
        byte[] duplicated=RiverProfiles.change(PATH,original,RiverProfiles.Action.DUPLICATE,0,"copy");
        check(profiles(duplicated).size()==3 && profiles(duplicated).get(1).stringToken("id").equals("copy"));
        check(Arrays.equals(palette(original,0).sourceBytes(),palette(duplicated,1).sourceBytes()) && profiles(duplicated).get(1).token("futureProfile").equals("true"));
        byte[] moved=RiverProfiles.change(PATH,original,RiverProfiles.Action.DOWN,0,null);
        check(Arrays.equals(profiles(original).get(0).sourceBytes(),profiles(moved).get(1).sourceBytes()));
        check(Arrays.equals(profiles(original).get(1).sourceBytes(),profiles(moved).get(0).sourceBytes()));
        RiverProfiles.change(PATH,moved,RiverProfiles.Action.UP,1,null);
        check(profiles(RiverProfiles.change(PATH,original,RiverProfiles.Action.REMOVE,0,null)).get(0).stringToken("id").equals("other"));
        rejects(() -> RiverProfiles.change(PATH,original,RiverProfiles.Action.UP,0,null));
        rejects(() -> RiverProfiles.change(PATH,original,RiverProfiles.Action.DOWN,1,null));
        rejects(() -> RiverProfiles.change(PATH,original,RiverProfiles.Action.REMOVE,-1,null));
        check(profiles(RiverProfiles.change(PATH,document("[]"),RiverProfiles.Action.ADD,-1,"repair")).size()==1);
        byte[] missingId=document("[{}, {\"id\":\"other\"}]");
        rejects(() -> RiverProfiles.setId(PATH,missingId,1,"default"));
        check(profiles(RiverProfiles.setId(PATH,missingId,0,"explicit")).get(0).stringToken("id").equals("explicit"));
        check(profiles(RiverProfiles.setId(PATH,document("[{\"id\":\"same\"},{\"id\":\"same\"}]"),0,"fixed")).get(0).stringToken("id").equals("fixed"));
        byte[] water=RiverProfiles.createPalette(PATH,document("[{\"id\":\"missing\"}]"),0);
        check(fluid(water,0,0).stringToken("block").equals("water"));
        rejects(() -> RiverProfiles.createPalette(PATH,water,0));
        byte[] bare=document("[{\"id\":\"bare\",\"fluidPalette\":{\"future\":true}}]");
        byte[] initialized=RiverProfiles.initializeWater(PATH,bare,0);
        check(palette(initialized,0).token("future").equals("true") && fluid(initialized,0,0).stringToken("block").equals("water"));
        rejects(() -> RiverProfiles.initializeWater(PATH,initialized,0));
        check(text(RiverProfiles.setFluid(PATH,original,0,0,"Minecraft:WATER[level=0]")).equals(text(original).replace("\"block\":\"custom:fluid\"","\"block\":\"Minecraft:WATER[level=0]\"")));
        for (String valid : List.of("water"," lava ","minecraft:bubble_column[drag=true]","custom:unverified")) RiverProfiles.setFluid(PATH,original,0,0,valid);
        for (String bad : List.of(""," ","stone","minecraft:DIRT[foo=bar]")) {
            rejects(() -> RiverProfiles.setFluid(PATH,original,0,0,bad));
            rejects(() -> RiverProfiles.changeFluid(PATH,original,0,RiverProfiles.Action.ADD,-1,bad));
        }
        check(text(RiverProfiles.setWeight(PATH,original,0,0,"6.4e1")).equals(text(original).replace("\"weight\":1,","\"weight\":64,")));
        for (String bad : List.of("0","65","1.000000000000000001","2147483648","NaN","Infinity","")) rejects(() -> RiverProfiles.setWeight(PATH,original,0,0,bad));
        check(fluid(RiverProfiles.enableWeight(PATH,enabled,0,0),0,0).token("weight").equals("1"));
        rejects(() -> RiverProfiles.setWeight(PATH,enabled,0,0,"2"));
        byte[] more=RiverProfiles.changeFluid(PATH,original,0,RiverProfiles.Action.ADD,-1,"water");
        check(fluid(more,0,2).fields().equals(Set.of("block")));
        byte[] fluidCopy=RiverProfiles.changeFluid(PATH,original,0,RiverProfiles.Action.DUPLICATE,0,null);
        check(Arrays.equals(fluid(fluidCopy,0,0).sourceBytes(),fluid(fluidCopy,0,1).sourceBytes()));
        byte[] movedFluid=RiverProfiles.changeFluid(PATH,original,0,RiverProfiles.Action.DOWN,0,null);
        check(Arrays.equals(fluid(original,0,0).sourceBytes(),fluid(movedFluid,0,1).sourceBytes()));
        RiverProfiles.changeFluid(PATH,movedFluid,0,RiverProfiles.Action.UP,1,null);
        rejects(() -> RiverProfiles.changeFluid(PATH,enabled,0,RiverProfiles.Action.REMOVE,0,null));
        rejects(() -> RiverProfiles.changeFluid(PATH,original,0,RiverProfiles.Action.UP,0,null));
        rejects(() -> RiverProfiles.changeFluid(PATH,original,0,RiverProfiles.Action.DOWN,1,null));
        byte[] emptyFluid=document("[{\"fluidPalette\":{\"palette\":[]}}]");
        check(fluid(RiverProfiles.changeFluid(PATH,emptyFluid,0,RiverProfiles.Action.ADD,-1,"water"),0,0).stringToken("block").equals("water"));
        byte[] noBlock=document("[{\"fluidPalette\":{\"palette\":[{}]}}]");
        check(fluid(RiverProfiles.setFluid(PATH,noBlock,0,0,"water"),0,0).fields().equals(Set.of("block")));
        var styleTarget=NoiseStyleFields.Target.profilePalette(0);
        for (String choice : NoiseStyleFields.choices()) check(text(NoiseStyleFields.editStyle(PATH,original,styleTarget,choice)).equals(text(original).replace("\"UNKNOWN\"","\""+choice+"\"")));
        check(text(RiverProfiles.setZoom(PATH,original,0,"10")).equals(text(original).replace("\"zoom\":5e0","\"zoom\":10")));
        rejects(() -> RiverProfiles.setZoom(PATH,original,0,"0.00009"));
        check(style(NoiseStyleFields.enable(PATH,enabled,styleTarget,"style"),0).stringToken("style").equals("STATIC"));
        check(style(NoiseStyleFields.enable(PATH,bare,styleTarget,"style"),0).stringToken("style").equals("STATIC"));
        check(style(NoiseStyleFields.enable(PATH,document("[{\"fluidPalette\":{\"style\":{}}}]"),styleTarget,"style"),0).stringToken("style").equals("FLAT"));
        check(palette(RiverProfiles.enableZoom(PATH,enabled,0),0).token("zoom").equals("5"));
        byte[] zeroCell=NoiseStyleFields.editText(PATH,original,styleTarget,"cellularZoom","0");
        rejects(() -> NoiseStyleFields.editText(PATH,zeroCell,styleTarget,"cellularFrequency","1"));
        byte[] active=NoiseStyleFields.editText(PATH,original,styleTarget,"cellularFrequency","1");
        rejects(() -> NoiseStyleFields.editText(PATH,active,styleTarget,"cellularZoom","0"));
        byte[] secondStyle=NoiseStyleFields.enable(PATH,original,NoiseStyleFields.Target.profilePalette(1),"style");
        check(style(secondStyle,1).stringToken("style").equals("STATIC") && Arrays.equals(profiles(original).get(0).sourceBytes(),profiles(secondStyle).get(0).sourceBytes()));
        for (String field : List.of("zoom","exponent","cellularFrequency","cellularZoom")) {
            byte[] one=NoiseStyleFields.enable(PATH,enabled,styleTarget,field);
            check(style(one,0).stringToken("style").equals("STATIC") && style(one,0).token(field).equals(NoiseStyleFields.defaultToken(field)));
        }
        for (String value : List.of("NaN","Infinity","1e999","0")) rejects(() -> RiverProfiles.setZoom(PATH,original,0,value));
        var readOnly=DimensionFields.readOnlyPaths(new LosslessJson(original));
        check(readOnly.containsAll(List.of("hydrology.rivers.profiles[0].futureProfile","hydrology.rivers.profiles[0].fluidPalette.futurePalette","hydrology.rivers.profiles[0].fluidPalette.palette[0].data")));
        for (String bad : List.of("null","{}","1","true","\"snippet\"","[null]","[{},1]")) {
            byte[] invalid=document(bad);
            rejects(() -> RiverProfiles.enable(PATH,invalid));
            rejects(() -> RiverProfiles.change(PATH,invalid,RiverProfiles.Action.ADD,-1,"new"));
            rejects(() -> RiverProfiles.setId(PATH,invalid,0,"valid"));
            check(DimensionFields.readOnlyPaths(new LosslessJson(invalid)).contains("hydrology.rivers.profiles"));
        }
        for (String bad : List.of("null","[]","1","true","\"snippet\"")) {
            byte[] invalid=document("[{\"id\":"+bad+",\"fluidPalette\":"+bad+"}]");
            if (!bad.startsWith("\"")) rejects(() -> RiverProfiles.setId(PATH,invalid,0,"valid"));
            rejects(() -> RiverProfiles.createPalette(PATH,invalid,0));
            rejects(() -> RiverProfiles.initializeWater(PATH,invalid,0));
            rejects(() -> NoiseStyleFields.enable(PATH,invalid,styleTarget,"style"));
            byte[] badFluid=document("[{\"fluidPalette\":{\"palette\":"+bad+"}}]");
            if (!bad.equals("[]")) rejects(() -> RiverProfiles.changeFluid(PATH,badFluid,0,RiverProfiles.Action.ADD,-1,"water"));
            rejects(() -> RiverProfiles.initializeWater(PATH,badFluid,0));
            byte[] badEntry=document("[{\"fluidPalette\":{\"palette\":[{\"block\":"+bad+",\"weight\":"+bad+"}]}}]");
            if (!bad.startsWith("\"")) rejects(() -> RiverProfiles.setFluid(PATH,badEntry,0,0,"water"));
            if (!bad.equals("1")) rejects(() -> RiverProfiles.setWeight(PATH,badEntry,0,0,"1"));
            check(profiles(RiverProfiles.setId(PATH,badFluid,0,"independent")).get(0).stringToken("id").equals("independent"));
        }
        StringBuilder manyProfiles=new StringBuilder("["),manyFluids=new StringBuilder("[");
        for (int i=0;i<64;i++) { if (i>0) { manyProfiles.append(',');manyFluids.append(','); }manyProfiles.append("{\"id\":\"p").append(i).append("\"}");manyFluids.append("{\"block\":\"water\"}"); }
        byte[] full=document(manyProfiles+"]"),fullFluid=document("[{\"fluidPalette\":{\"palette\":"+manyFluids+"]}}]");
        rejects(() -> RiverProfiles.change(PATH,full,RiverProfiles.Action.ADD,-1,"extra"));
        rejects(() -> RiverProfiles.change(PATH,full,RiverProfiles.Action.DUPLICATE,0,"extra"));
        check(profiles(RiverProfiles.change(PATH,full,RiverProfiles.Action.REMOVE,0,null)).size()==63);
        rejects(() -> RiverProfiles.changeFluid(PATH,fullFluid,0,RiverProfiles.Action.ADD,-1,"water"));
        rejects(() -> RiverProfiles.changeFluid(PATH,fullFluid,0,RiverProfiles.Action.DUPLICATE,0,null));
        check(RiverProfiles.fluids(palette(RiverProfiles.changeFluid(PATH,fullFluid,0,RiverProfiles.Action.REMOVE,0,null),0)).size()==63);
        for (String missing : List.of("{}","{\"hydrology\":{}}","{\"hydrology\":{\"rivers\":null}}")) rejects(() -> RiverProfiles.enable(PATH,bytes(missing)));
        for (String path : List.of("regions/a.json","biomes/a.json","generators/a.json","dimensions/nested/a.json","a.json")) {
            rejects(() -> RiverProfiles.enable(path,parent));rejects(() -> RiverProfiles.setId(path,original,0,"new"));
            rejects(() -> RiverProfiles.changeFluid(path,original,0,RiverProfiles.Action.ADD,-1,"water"));
            rejects(() -> NoiseStyleFields.enable(path,enabled,styleTarget,"style"));
        }
        rejects(() -> NoiseStyleFields.Target.profilePalette(-1));
        rejects(() -> new NoiseStyleFields.Target("biomes",styleTarget.objectField(),null));
        rejects(() -> new NoiseStyleFields.Target("dimensions",styleTarget.objectField(),null,0));
        check(RiverProfiles.styleIndex("hydrology.rivers.profiles.01.fluidPalette.style")==-1 && RiverProfiles.styleIndex("hydrology.rivers.profiles.2147483648.fluidPalette.style")==-1);
        var drafts=new InspectorDrafts();
        String fluidKey=RiverProfiles.fluidPrefix(0)+"0.block",noiseKey=RiverProfiles.noisePrefix(0)+"zoom",idKey=RiverProfiles.profilePrefix(0)+"id",otherKey=RiverProfiles.fluidPrefix(1)+"0.block",routingKey=HydrologyFields.draftPrefix()+"tileSize";
        for (String key : List.of(fluidKey,noiseKey,idKey,otherKey,routingKey)) drafts.update(key,"a","b");
        check(!drafts.transitionUnder(RiverProfiles.draftPrefix(),() -> false,() -> { throw new AssertionError(); }) && drafts.count()==5);
        try { drafts.transitionUnder(RiverProfiles.draftPrefix(),() -> true,() -> { throw new IOException("failed write"); });throw new AssertionError(); } catch (IOException expected) { check(drafts.count()==5); }
        check(drafts.transitionUnder(RiverProfiles.fluidPrefix(0),() -> true,() -> {}) && drafts.count()==4 && drafts.contains(noiseKey) && drafts.contains(idKey));
        check(drafts.transitionUnder(RiverProfiles.draftPrefix(),() -> true,() -> {}) && drafts.count()==1 && drafts.contains(routingKey));
        check(Arrays.equals(original,copy));
        var workspace=PackWorkspace.newPack(Map.of(PATH,original,"dimensions/defaults.json",enabled));
        var cache=new RiverIdLists.CatalogCache();check(cache.inspect(workspace,PATH,null,"profiles").ids().equals(List.of("default","other")));
        workspace.replace(PATH,renamed);check(cache.inspect(workspace,PATH,null,"profiles").ids().equals(List.of("renamed","other")));
        check(new LosslessJson(workspace.read(PATH)).tokenPath("riverPolicy.profiles").equals("[\"default\"]"));
        workspace.save(temp.resolve("river-profiles"));workspace.exportZip(temp.resolve("river-profiles.zip"));
        var reopened=PackWorkspace.open(temp.resolve("river-profiles"));var imported=PackWorkspace.importZip(temp.resolve("river-profiles.zip"));
        for (String path : workspace.paths()) check(Arrays.equals(workspace.read(path),reopened.read(path)) && Arrays.equals(workspace.read(path),imported.read(path)));
        check(FieldCoverage.fields("riverProfile").size()==2 && FieldCoverage.fields("riverProfile").stream().allMatch(FieldCoverage.Field::implemented));
        check(FieldCoverage.fields("riverHydrology").stream().allMatch(FieldCoverage.Field::implemented));
        System.out.println("PASS: river profile defaults/unique IDs/list bounds, weighted fluid filters and operations, palette noise/styles, typed parent guards, catalog refresh/reference preservation, scoped drafts and lossless Save/ZIP contracts");
    }
}
